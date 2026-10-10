package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.measurement.HealthSummary
import com.akane.voltwise.battery.util.BatteryStatsParser
import java.time.ZoneId
import java.time.ZoneOffset

/** Maps stored measurements without replacing unsupported (null) counters with zero. */
object InsightInputsBuilder {
    const val HISTORY_MS = 90L * 24 * 60 * 60 * 1_000
    const val HISTORY_DAYS = 90L

    fun build(
        nowMs: Long,
        todayEpochDay: Long,
        fullUah: Long?,
        sessions: List<ChargeSession>,
        days: List<DailySummary>,
        appRows: List<SessionAppUsage>,
        wakers: List<SessionDeviceWaker>,
        capacity: List<CapacityEstimateRow>,
        dozeWhitelist: Set<String>?,
        actions: List<InsightActionEntity>,
        findings: List<InsightFindingEntity>,
        zone: ZoneId = ZoneOffset.UTC,
    ): InsightInputs {
        val rowsBySession = appRows.groupBy { it.sessionId }
        val findingsByKey = findings.associateBy { it.key }
        val mappedSessions = sessions.mapNotNull { session ->
            val end = session.endTime?.takeIf { it in (nowMs - HISTORY_MS)..nowMs } ?: return@mapNotNull null
            val kind = enumName<SessionKind>(session.type.name) ?: return@mapNotNull null
            val rows = rowsBySession[session.sessionId].orEmpty()
            val window = if (session.appUsageStatus == AppUsageStatus.READY) {
                val basis = enumName<WindowBasis>(session.appUsageBasis?.name)
                val start = session.appCaptureStartMs
                val finish = session.appCaptureEndMs
                if (basis != null && start != null && finish != null) {
                    AppWindowInput(
                        basis, start, finish, rows.any { it.isOthers },
                        // Profile filtering must not turn filled storage slots into exact-zero evidence.
                        wakersStored = rows.count { !it.isOthers && it.rank >= 30 },
                    )
                } else null
            } else null
            SessionInput(
                session.sessionId, kind, session.startTime, end, session.observedMs,
                session.screenOnMs, session.screenOffMs, session.screenOnCoveredMs, session.screenOffCoveredMs,
                session.screenOnUah, session.screenOffUah, session.cpuSuspendMs, session.screenOffSuspendMs,
                session.dozeMs, session.screenOffDozeMs, session.startLevel, session.endLevel,
                session.peakTemperatureDeciC,
                // ExportImport marks imported sessions; do not mistake legacy local observations for imports.
                session.source.startsWith("import:") || session.sessionId.startsWith("import:"), window,
            )
        }
        val ids = mappedSessions.map { it.id }.toSet()
        return InsightInputs(
            nowMs, todayEpochDay, fullUah, mappedSessions,
            days.filter { it.epochDay in (todayEpochDay - HISTORY_DAYS)..todayEpochDay }.map {
                DayInput(it.epochDay, it.screenOnMs, it.screenOffMs, it.screenOnCoveredMs, it.screenOffCoveredMs,
                    it.screenOnDischargeUah, it.screenOffDischargeUah, it.chargedUah, it.cpuSuspendMs,
                    it.screenOffSuspendMs, it.dozeMs, it.screenOffDozeMs, it.peakTemperatureDeciC)
            },
            appRows.filter {
                it.sessionId in ids && (it.isOthers || it.uid / BatteryStatsParser.PER_USER_RANGE == 0)
            }.map {
                AppSessionInput(it.sessionId, it.uid, it.packageName, it.rank, it.powerMah, it.cpuTimeMs,
                    it.foregroundTimeMs, it.backgroundTimeMs, it.wakelockTimeMs, it.mobileBytes, it.wifiBytes,
                    it.wakeupAlarms, it.partialWakelockCount, it.partialWakelockBgMs, it.jobCount, it.jobMs,
                    it.syncCount, it.fgServiceMs, it.topMs, it.mobileActiveMs, it.gpsMs, it.sensorMs,
                    it.isOthers, it.topWakelockTag, it.topAlarmTag, it.topJobName)
            },
            wakers.filter { it.sessionId in ids }.mapNotNull {
                val kind = enumName<WakerKind>(it.kind) ?: return@mapNotNull null
                DeviceWakerInput(it.sessionId, kind, it.name, it.count, it.totalMs)
            },
            capacity.mapNotNull {
                if (it.source.startsWith("import:") || it.sessionId.startsWith("import:")) return@mapNotNull null
                val at = it.endTime ?: return@mapNotNull null
                if (at !in (nowMs - HISTORY_MS)..nowMs) return@mapNotNull null
                val estimate = HealthSummary.storedEstimate(it.capacityEstimateMah, it.capacityConfidence, it.capacityBasis)
                    ?: return@mapNotNull null
                CapacityPointInput(at, estimate.fullUah / 1_000.0, estimate.confidence.ordinal + 1)
            },
            dozeWhitelist,
            actions.mapNotNull {
                if (it.appliedAt == null || it.status !in appliedStatuses) return@mapNotNull null
                val type = enumName<ActionType>(it.type) ?: return@mapNotNull null
                val status = enumName<ActionStatus>(it.status.name) ?: return@mapNotNull null
                val metric = if (it.metric != null) enumName<Metric>(it.metric) else
                    findingsByKey[it.findingKey]?.let(FindingCodec::decode)?.evidence?.firstOrNull()?.metric
                AppliedActionInput(it.id, it.findingKey, type, it.packageName, it.uid, it.appliedAt, status, metric)
            },
            findings.associate { it.key to it.feedbackMultiplier },
            zone = zone,
        )
    }

    private val appliedStatuses = setOf(InsightActionStatus.APPLIED, InsightActionStatus.REVERTED, InsightActionStatus.ONE_SHOT)
}

internal inline fun <reified T : Enum<T>> enumName(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }
