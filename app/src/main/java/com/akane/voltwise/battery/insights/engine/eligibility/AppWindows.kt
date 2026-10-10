package com.akane.voltwise.battery.insights.engine.eligibility

import com.akane.voltwise.battery.insights.model.AppSessionInput
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionInput
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.insights.model.WindowBasis
import com.akane.voltwise.battery.util.BatteryStatsParser
import kotlin.math.abs
import kotlin.math.max

/** A censored absence has only an upper bound, never an invented measured value. */
data class AppMetricPoint(
    val sessionId: String,
    val atMs: Long,
    val value: Double?,
    val upperBound: Double?,
    val present: Boolean,
) {
    val censored: Boolean get() = upperBound != null
}

data class EligibleAppWindow(val session: SessionInput, val durationMs: Double, val rows: List<AppSessionInput>) {
    val hours: Double get() = durationMs / AppWindows.HOUR_MS
    val atMs: Long get() = session.appWindow?.captureEndMs ?: session.endMs
    fun row(subject: Subject.App): AppSessionInput? =
        rows.firstOrNull { !it.isOthers && it.uid == subject.uid && it.packageName == subject.packageName }
}

object AppWindows {
    const val HOUR_MS = 3_600_000.0
    const val SPAN_TOLERANCE = 0.10
    // A one-minute-per-hour denominator floor keeps no-foreground FGS ratios independent of window length.
    const val FOREGROUND_FLOOR_MS = 60_000.0

    fun select(inputs: InsightInputs): List<EligibleAppWindow> {
        val rows = inputs.appSessions.groupBy { it.sessionId }
        return inputs.sessions.mapNotNull { session ->
            val window = session.appWindow ?: return@mapNotNull null
            if (session.kind != SessionKind.DISCHARGE || session.imported || window.basis != WindowBasis.DELTA) {
                return@mapNotNull null
            }
            if (session.endMs <= session.startMs || session.endMs > inputs.nowMs || window.captureEndMs > inputs.nowMs) {
                return@mapNotNull null
            }
            val span = session.endMs.toDouble() - session.startMs.toDouble()
            val duration = window.captureEndMs.toDouble() - window.captureStartMs.toDouble()
            val tolerance = span * SPAN_TOLERANCE
            if (duration < HOUR_MS || abs(duration - span) > tolerance ||
                abs(window.captureStartMs.toDouble() - session.startMs.toDouble()) > tolerance ||
                abs(window.captureEndMs.toDouble() - session.endMs.toDouble()) > tolerance
            ) return@mapNotNull null
            EligibleAppWindow(session, duration, rows[session.id].orEmpty())
        }.sortedWith(compareBy({ it.atMs }, { it.session.id }))
    }

    fun series(windows: List<EligibleAppWindow>, subject: Subject.App, metric: Metric): List<AppMetricPoint> =
        windows.mapNotNull { point(it, subject, metric) }

    fun point(window: EligibleAppWindow, subject: Subject.App, metric: Metric): AppMetricPoint? {
        val row = window.row(subject)
        if (row != null) {
            val value = value(window, row, metric) ?: return null
            return AppMetricPoint(window.session.id, window.atMs, value, null, present = true)
        }
        // Stored ranks 0–29 are power leaders; later ranks are additional waker candidates.
        val supported = window.rows.filter {
            !it.isOthers && (metric != Metric.POWER_MAH_PER_H || it.rank < 30)
        }.mapNotNull { value(window, it, metric) }
        // No stored support for a nullable metric means unsupported, even for an absent app.
        if (supported.isEmpty() && (metric != Metric.POWER_MAH_PER_H || window.rows.isNotEmpty())) return null
        if (window.session.appWindow?.fullRowSet != true) {
            return AppMetricPoint(window.session.id, window.atMs, 0.0, null, present = false)
        }
        val tail = window.rows.firstOrNull { it.isOthers }
        val tailBound = when (metric) {
            // The tail's aggregate ratio cannot bound an app with less foreground time.
            Metric.FGS_TO_FOREGROUND_RATIO -> tail?.fgServiceMs
                ?.takeIf { it >= 0 }?.toDouble()?.div(FOREGROUND_FLOOR_MS * window.hours)
            else -> tail?.let { value(window, it, metric) }
        }
        // Unknown contributors cannot establish a total, a waker cutoff, or an exact zero.
        if (metric != Metric.POWER_MAH_PER_H && tailBound == null) return null
        val wakers = window.rows.filter { !it.isOthers && it.rank >= 30 }
        // Spare waker slots mean every app with alarms or background wakelock time was kept.
        if ((window.session.appWindow.wakersStored ?: wakers.size) < 10 &&
            (metric == Metric.WAKEUP_ALARMS_PER_H || metric == Metric.PARTIAL_WAKELOCK_BG_SHARE)
        ) {
            return AppMetricPoint(window.session.id, window.atMs, 0.0, null, present = false)
        }
        val cutoff = when (metric) {
            Metric.POWER_MAH_PER_H -> supported.minOrNull()
            Metric.WAKEUP_ALARMS_PER_H -> wakers.mapNotNull { value(window, it, metric) }.minOrNull()
            // Remaining supported metrics are additive totals divided by a shared window duration.
            else -> tailBound
        } ?: return null
        if (cutoff == 0.0) {
            return AppMetricPoint(window.session.id, window.atMs, 0.0, null, present = false)
        }
        return AppMetricPoint(window.session.id, window.atMs, null, cutoff, present = false)
    }

    fun value(window: EligibleAppWindow, row: AppSessionInput, metric: Metric): Double? {
        fun rate(value: Long?): Double? = value?.takeIf { it >= 0 }?.toDouble()?.div(window.hours)
        fun share(value: Long?): Double? = value?.takeIf { it >= 0 }?.toDouble()?.div(window.durationMs)
        val value = when (metric) {
            Metric.POWER_MAH_PER_H -> row.powerMah / window.hours
            Metric.CPU_MS_PER_H -> rate(row.cpuMs)
            Metric.FOREGROUND_MS_PER_H -> rate(row.fgMs)
            Metric.TOP_MS_PER_H -> rate(row.topMs)
            Metric.FGS_MS_PER_H -> rate(row.fgServiceMs)
            Metric.BG_TIME_SHARE -> share(row.bgMs)
            Metric.PARTIAL_WAKELOCK_BG_SHARE -> share(row.partialWakelockBgMs)
            Metric.WAKELOCK_MS_PER_H -> rate(row.wakelockMs)
            Metric.WAKEUP_ALARMS_PER_H -> rate(row.wakeupAlarms)
            Metric.PARTIAL_WAKELOCKS_PER_H -> rate(row.partialWakelockCount)
            Metric.JOBS_PER_H -> rate(row.jobCount)
            Metric.JOB_MS_PER_H -> rate(row.jobMs)
            Metric.SYNCS_PER_H -> rate(row.syncCount)
            Metric.GPS_MS_PER_H -> rate(row.gpsMs)
            Metric.SENSOR_MS_PER_H -> rate(row.sensorMs)
            Metric.RADIO_ACTIVE_MS_PER_H -> rate(row.mobileActiveMs)
            Metric.MOBILE_BYTES_PER_H -> rate(row.mobileBytes)
            Metric.WIFI_BYTES_PER_H -> rate(row.wifiBytes)
            Metric.FGS_TO_FOREGROUND_RATIO -> {
                val fgs = row.fgServiceMs?.takeIf { it >= 0 } ?: return null
                val foreground = foregroundMs(row) ?: return null
                fgs.toDouble() / max(FOREGROUND_FLOOR_MS * window.hours, foreground)
            }
            else -> null
        }
        return value?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun foregroundMs(row: AppSessionInput): Double? {
        val top = row.topMs?.takeIf { it >= 0 } ?: return null
        // System/native UIDs without positive recorded foreground time stay unknown, whether the
        // timer is absent or zero (proto omits zero timers): non-TOP foreground states aren't stored.
        // whittle: App-UID keyboards (IMEs) also use FOREGROUND, not TOP, and can look background-only.
        // Upgrade when the st FOREGROUND column is stored (schema change).
        if (BatteryStatsParser.isSystemUid(row.uid) && (row.fgMs ?: 0L) <= 0L) return null
        val fg = (row.fgMs ?: 0L).takeIf { it >= 0 } ?: return null
        return fg.toDouble() + top.toDouble()
    }

    /** Prefer covered device energy, then capacity × level drop; never a stored top-app subtotal. */
    fun drainMah(window: EligibleAppWindow, fullUah: Long?): Double? {
        val session = window.session
        val on = session.screenOnUah ?: if (session.screenOnMs == 0L) 0L else null
        val off = session.screenOffUah ?: if (session.screenOffMs == 0L) 0L else null
        val onCoverage = session.screenOnCoveredMs ?: if (session.screenOnMs == 0L) 0L else null
        val offCoverage = session.screenOffCoveredMs ?: if (session.screenOffMs == 0L) 0L else null
        if (on != null && off != null && on >= 0 && off >= 0 && onCoverage != null && offCoverage != null &&
            onCoverage >= session.screenOnMs && offCoverage >= session.screenOffMs
        ) {
            val drain = (on.toDouble() + off.toDouble()) / 1_000.0
            if (drain > 0.0) return drain
        }
        val start = session.startLevel
        val end = session.endLevel
        if (fullUah != null && fullUah > 0 && start != null && end != null && start in 0..100 && end in 0..100 && start > end) {
            return fullUah.toDouble() / 1_000.0 * (start - end) / 100.0
        }
        // No complete device drain measurement: do not infer a percentage from stored top rows.
        return null
    }
}
