package com.akane.voltwise.battery.data

import android.os.BatteryManager
import androidx.room.withTransaction
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.measurement.BatteryReading
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

internal enum class ImportSessionDisposition(val added: Int = 0, val updated: Int = 0, val unchanged: Int = 0) {
    ADDED(added = 1),
    UPDATED(updated = 1),
    UNCHANGED(unchanged = 1),
    STALE(unchanged = 1),
}

internal data class SessionImportPlan(val session: ChargeSession, val disposition: ImportSessionDisposition)

/** Imported records are historical evidence, never a resumed live observation. */
object HistoryPolicy {
    private val canonicalJson = Json { encodeDefaults = true }
    private const val MAX_TIMESTAMP = 253402300799999L // end of year9999, milliseconds since Unix epoch
    private const val MAX_IMPORT_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L
    fun originalId(value: String) = value.removePrefix("import:")
    private fun requiredIdentity(value: String): String {
        require(value.isNotBlank() && originalId(value).length <= 240 && value.none { c -> c.isISOControl() }) { "Invalid history identity" }
        return "import:${originalId(value)}"
    }
    private fun identity(value: String?): String? = value?.let { requiredIdentity(it) }
    private fun source(value: String) = "import:${value.removePrefix("import:").take(128)}"
    private fun requiredText(value: String): String = value.also { require(it.length <= 512 && it.trimStart().firstOrNull() !in listOf('=', '+', '-', '@') && '\u0000' !in it) { "Invalid history text" } }
    private fun text(value: String?): String? = value?.let { requiredText(it) }
    private fun epoch(value: Long, importTimeMs: Long) {
        require(value in 0..MAX_TIMESTAMP) { "Invalid timestamp; expected Unix milliseconds" }
        require(value <= importTimeMs + MAX_IMPORT_CLOCK_SKEW_MS) { "Invalid timestamp; exceeds import time allowance" }
    }
    private fun charge(value: Long?): Long? {
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) return null
        return value?.let(BatteryReading::chargeUah).also { require(value == null || it != null) { "Invalid charge counter; expected µAh" } }
    }
    private fun current(value: Long?): Long? {
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) return null
        return value?.let(BatteryReading::currentUa).also { require(value == null || it != null) { "Invalid current; expected µA" } }
    }
    fun sample(input: BatterySample): BatterySample = sample(input, Clock.systemUTC())

    fun sample(input: BatterySample, clock: Clock): BatterySample {
        epoch(input.timestamp, clock.millis())
        require(input.levelPercent == null || input.levelPercent in 0..100) { "Invalid battery percentage" }
        require(input.elapsedMs == null || input.elapsedMs >= 0) { "Invalid elapsed time" }
        require(input.uptimeMs == null || input.elapsedMs != null && input.uptimeMs in 0..input.elapsedMs) { "Invalid uptime" }
        val voltage = input.voltageMv?.takeUnless { it == 0 || it == Int.MIN_VALUE }
        require(voltage == null || BatteryReading.voltageMv(voltage) != null) { "Invalid voltage; expected mV" }
        val temperature = input.temperatureDeciC?.takeUnless { it == Int.MIN_VALUE }
        require(temperature == null || BatteryReading.temperatureDeciC(temperature) != null) { "Invalid temperature; expected tenths Celsius" }
        require(input.cycleCount == null || input.cycleCount >= 0) { "Invalid cycle count" }
        require(input.energyNwh == null || BatteryReading.energyNwh(input.energyNwh) != null) { "Invalid energy; expected nWh" }
        require(input.etaMs == null || input.etaMs in 1..604_800_000L) { "Invalid remaining-time estimate" }
        val normalized = input.copy(id = 0, source = source(requiredText(input.source)),
            sessionId = identity(input.sessionId), observationId = identity(input.observationId),
            currentNowUa = current(input.currentNowUa), currentAverageUa = current(input.currentAverageUa),
            chargeCounterUah = charge(input.chargeCounterUah), voltageMv = voltage, temperatureDeciC = temperature,
            status = input.status.takeIf { it in 1..5 } ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
            plugged = input.plugged?.takeIf { it in 0..15 },
            health = input.health?.takeIf { it in 1..7 },
            etaBasis = text(input.etaBasis), boundaryReason = text(input.boundaryReason))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.encodeToString(BatterySample.serializer(), normalized).toByteArray())
        // Local AUTOINCREMENT IDs are positive. A collision is checked against complete row content before insert.
        val id = ByteBuffer.wrap(digest).long or Long.MIN_VALUE
        return normalized.copy(id = if (id == -1L) -2L else id)
    }
    fun sameSample(first: BatterySample, second: BatterySample): Boolean = sample(first) == sample(second)
    // A session can accumulate several sub-5-second wall-clock corrections without an observation gap.
    private fun clockCorrectionAllowance(span: Long): Long = 5_000 + minOf(span / 10, 15 * 60 * 1000L)

    internal fun sampleInSessionWindow(timestamp: Long, session: ChargeSession,
        end: Long = session.endTime ?: session.lastSampleTime ?: session.startTime): Boolean =
        timestamp >= session.startTime && timestamp - end <= clockCorrectionAllowance(end - session.startTime)

    private fun normalizeCoverage(input: ChargeSession, span: Long): ChargeSession {
        if (input.observedMs <= span) return input
        fun duration(ms: Long): Long = (ms.toDouble() / input.observedMs * span).toLong()
        val counter = duration(input.counterCoveredMs)
        val screenOn = duration(input.screenOnMs)
        val screenOff = duration(input.screenOffMs).coerceAtMost(span - screenOn)
        val screenOnCovered = input.screenOnCoveredMs?.let(::duration)?.coerceAtMost(screenOn)
        val screenOffCovered = input.screenOffCoveredMs?.let(::duration)?.coerceAtMost(screenOff)
        // Keep bucket rates tied to their rounded durations; the measured session total stays intact.
        fun bucketCharge(uah: Long?, originalMs: Long, normalizedMs: Long): Long? =
            uah?.takeUnless { normalizedMs == 0L }?.let { (it.toDouble() / originalMs * normalizedMs).toLong() }
        return input.copy(observedMs = span, counterCoveredMs = counter,
            screenOnMs = screenOn, screenOffMs = screenOff,
            screenOnCoveredMs = screenOnCovered, screenOffCoveredMs = screenOffCovered,
            dozeMs = input.dozeMs?.let(::duration),
            screenOffDozeMs = input.screenOffDozeMs?.let(::duration)?.coerceAtMost(screenOff),
            cpuSuspendMs = input.cpuSuspendMs?.let(::duration),
            screenOffSuspendMs = input.screenOffSuspendMs?.let(::duration),
            deltaUah = input.deltaUah.takeUnless { counter == 0L },
            screenOnUah = bucketCharge(input.screenOnUah, input.screenOnCoveredMs ?: input.screenOnMs, screenOnCovered ?: screenOn),
            screenOffUah = bucketCharge(input.screenOffUah, input.screenOffCoveredMs ?: input.screenOffMs, screenOffCovered ?: screenOff))
    }

    fun session(input: ChargeSession, formatVersion: Int = HISTORY_FORMAT_VERSION, clock: Clock = Clock.systemUTC()): ChargeSession {
        val importTimeMs = clock.millis()
        epoch(input.startTime, importTimeMs)
        input.endTime?.let { epoch(it, importTimeMs) }; input.lastSampleTime?.let { epoch(it, importTimeMs) }
        val end = input.endTime ?: input.lastSampleTime ?: input.startTime
        require(end >= input.startTime) { "Session ends before it starts" }
        require(input.startLevel == null || input.startLevel in 0..100) { "Invalid start level" }
        require(input.endLevel == null || input.endLevel in 0..100) { "Invalid end level" }
        val span = end - input.startTime
        require(input.observedMs >= 0 && input.observedMs - span <= clockCorrectionAllowance(span)) { "Invalid observed interval" }
        require(input.counterCoveredMs in 0..input.observedMs && input.screenOnMs in 0..input.observedMs &&
            input.screenOffMs in 0..(input.observedMs - input.screenOnMs)) { "Incompatible session coverage" }
        require(input.cpuSuspendMs == null || input.cpuSuspendMs in 0..input.observedMs) { "Invalid CPU suspend interval" }
        require(input.deltaUah == null || input.deltaUah in 0..1_000_000_000_000_000L) { "Invalid session charge change" }
        require(input.screenOnUah == null || input.screenOnUah in 0..1_000_000_000_000_000L) { "Invalid screen-on charge" }
        require(input.screenOffUah == null || input.screenOffUah in 0..1_000_000_000_000_000L) { "Invalid screen-off charge" }
        require(input.screenOnMs > 0 || input.screenOnUah == null || input.screenOnUah == 0L) { "Screen-on charge without an observed screen-on interval" }
        require(input.screenOffMs > 0 || input.screenOffUah == null || input.screenOffUah == 0L) { "Screen-off charge without an observed screen-off interval" }
        if (input.observationId != null) {
            require(input.counterCoveredMs > 0 || input.deltaUah == null) { "Charge total without counter coverage" }
            require(input.deltaUah == null || (input.screenOnUah ?: 0) + (input.screenOffUah ?: 0) <= input.deltaUah) { "Screen charge exceeds the session total" }
        }
        require(input.estCapacityMah == null || input.estCapacityMah in 1..200_000) { "Invalid legacy capacity estimate" }
        require(input.capacityEstimateMah == null || input.capacityEstimateMah in 1..200_000) { "Invalid capacity estimate; expected mAh" }
        require(input.energyNwh == null || input.energyNwh in 0..1_000_000_000_000_000L) { "Invalid session energy; expected nWh" }
        require(input.peakTemperatureDeciC == null || BatteryReading.temperatureDeciC(input.peakTemperatureDeciC) != null) { "Invalid peak temperature; expected tenths Celsius" }
        require(input.screenOffSuspendMs == null || input.screenOffSuspendMs in 0..input.observedMs) { "Invalid screen-off suspend interval" }
        require(input.lastSampleTime == null || sampleInSessionWindow(input.lastSampleTime, input, end)) { "Invalid session sample time" }
        require(input.screenOnCoveredMs == null || input.screenOnCoveredMs >= 0) { "Invalid screen-on counter coverage" }
        require(input.screenOffCoveredMs == null || input.screenOffCoveredMs >= 0) { "Invalid screen-off counter coverage" }
        val covered = input.copy(
            dozeMs = input.dozeMs?.takeIf { formatVersion >= 5 && it >= 0 }?.coerceAtMost(input.observedMs),
            screenOffDozeMs = input.screenOffDozeMs?.takeIf { formatVersion >= 5 && it >= 0 }?.coerceAtMost(input.screenOffMs),
            screenOnCoveredMs = input.screenOnCoveredMs?.coerceAtMost(input.screenOnMs),
            screenOffCoveredMs = input.screenOffCoveredMs?.coerceAtMost(input.screenOffMs),
        )
        return normalizeCoverage(covered, span).copy(sessionId = requiredIdentity(input.sessionId), observationId = identity(input.observationId),
            lastSampleTime = input.lastSampleTime?.coerceAtMost(end),
            endTime = end, activeKey = null, source = source(requiredText(input.source)), avgCurrentUa = current(input.avgCurrentUa),
            closeReason = if (input.endTime == null) "Imported snapshot; monitoring was not resumed" else text(input.closeReason),
            peakPowerMw = input.peakPowerMw?.takeIf { it in 0..1_000_000L },
            chargerType = text(input.chargerType), capacityConfidence = text(input.capacityConfidence), capacityBasis = text(input.capacityBasis),
            appCaptureStartMs = null, appCaptureEndMs = null,
            // An imported record never gets its end snapshot.
            appUsageStatus = input.appUsageStatus.takeUnless { it == AppUsageStatus.PENDING })
    }
    /** Values written after a session closes (per-app status, capacity estimate) and explanatory text are not its measurement. */
    private fun measured(s: ChargeSession) = s.copy(closeReason = null, appUsageStatus = null, appUsageBasis = null,
        capacityEstimateMah = null, capacityConfidence = null, capacityBasis = null, dozeMs = null, screenOffDozeMs = null)
    // Formats 1–4 have no Doze evidence. Unknown values may be enriched, not treated as zero.
    fun sameMeasurement(first: ChargeSession, second: ChargeSession): Boolean = measured(first) == measured(second) &&
        (first.dozeMs == null || second.dozeMs == null || first.dozeMs == second.dozeMs) &&
        (first.screenOffDozeMs == null || second.screenOffDozeMs == null || first.screenOffDozeMs == second.screenOffDozeMs)
    /** [incoming], keeping [previous]'s after-close values where the file has none (e.g. an export made before them). */
    fun mergeDerived(previous: ChargeSession, incoming: ChargeSession): ChargeSession {
        val usage = if (incoming.appUsageStatus != null) incoming else previous
        val capacity = if (incoming.capacityEstimateMah != null) incoming else previous
        return incoming.copy(appUsageStatus = usage.appUsageStatus, appUsageBasis = usage.appUsageBasis,
            dozeMs = incoming.dozeMs ?: previous.dozeMs.takeIf { previous.endTime == incoming.endTime },
            screenOffDozeMs = incoming.screenOffDozeMs ?: previous.screenOffDozeMs.takeIf { previous.endTime == incoming.endTime },
            capacityEstimateMah = capacity.capacityEstimateMah, capacityConfidence = capacity.capacityConfidence, capacityBasis = capacity.capacityBasis)
    }
    /** Plans raw rows before normalization can shrink coverage; keeps stale skips distinct from enrichment. */
    internal fun planSessionImport(previous: ChargeSession?, incoming: ChargeSession,
        formatVersion: Int = HISTORY_FORMAT_VERSION): SessionImportPlan {
        // whittle: imports omit per-app evidence, so strip the incoming READY status/basis.
        // Revisit only if portable per-app evidence is supported; mergeDerived keeps existing local evidence.
        val candidate = session(if (incoming.appUsageStatus == AppUsageStatus.READY)
            incoming.copy(appUsageStatus = null, appUsageBasis = null) else incoming, formatVersion)
        val stored = previous?.let { session(it) } ?: return SessionImportPlan(candidate, ImportSessionDisposition.ADDED)
        val merged = mergeDerived(stored, candidate)
        if (stored == merged) return SessionImportPlan(stored, ImportSessionDisposition.UNCHANGED)
        if (sameMeasurement(stored, candidate)) return SessionImportPlan(merged, ImportSessionDisposition.UPDATED)
        require(previous.source.startsWith("import:") && sameOrigin(stored, candidate)) { "Conflicting imported session" }
        require(candidate.endTime != stored.endTime) { "Conflicting values for one imported session window" }
        // Imported and stored rows always carry an end (session sets one).
        val incomingEnd = checkNotNull(candidate.endTime) { "Imported session without an end" }
        val storedEnd = checkNotNull(stored.endTime) { "Imported session without an end" }
        if (incomingEnd < storedEnd) return SessionImportPlan(stored, ImportSessionDisposition.STALE)
        require(incoming.observedMs >= previous.observedMs && incoming.counterCoveredMs >= previous.counterCoveredMs) { "Incompatible imported session coverage" }
        return SessionImportPlan(merged, ImportSessionDisposition.UPDATED)
    }

    fun sameOrigin(first: ChargeSession, second: ChargeSession): Boolean =
        originalId(first.sessionId) == originalId(second.sessionId) && first.startTime == second.startTime && first.type == second.type &&
            first.observationId?.let(::originalId) == second.observationId?.let(::originalId)

    /** Day summaries are kept for every local day that ends after the cutoff: the cutoff's own day stays. */
    fun retentionCutoffDay(cutoffMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(cutoffMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Retention: samples and closed sessions older than [cutoffMs] (their app usage cascades), day summaries of days
     * before the cutoff's day, snapshots whose session is gone, expired findings, and terminal actions.
     * Pending/applied/unknown actions remain Undo/reconciliation authority. One transaction.
     */
    suspend fun purgeExpired(db: BatteryDatabase, cutoffMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        db.withTransaction {
            db.batteryDao().purge(cutoffMs)
            db.sessionDao().purge(cutoffMs)
            db.dailySummaryDao().purgeBefore(retentionCutoffDay(cutoffMs, zone))
            db.appUsageDao().pruneOrphanSnapshots()
            db.insightDao().purgeFindingsSeenBefore(cutoffMs)
            db.insightDao().purgeTerminalActionsBefore(cutoffMs)
        }
    }
}
