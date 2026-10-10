package com.akane.voltwise.battery.apps

/** Result of comparing a discharge session's start/end app-usage snapshots. */
data class AppUsageDeltaResult(
    val basis: AppUsageBasis,
    val rows: List<AppUsageRow>,
    val deviceWakers: List<DeviceWaker> = emptyList(),
    val captureStartMs: Long? = null,
    val captureEndMs: Long? = null,
)

/** Turns two `AppUsageSnapshot`s (or just the ended one) into the per-app rows a session shows. */
object AppUsageDelta {
    private const val POWER_EPSILON = 1e-6

    fun compute(baseline: AppUsageSnapshot?, end: AppUsageSnapshot, topN: Int = 30): AppUsageDeltaResult {
        val (basis, selected) = when {
            baseline == null -> AppUsageBasis.ABSOLUTE to TopApps.selectSessionRows(end.rows, topN)
            !sameWindow(baseline, end) || totalDecreased(baseline.rows, end.rows) ->
                AppUsageBasis.WINDOW_RESET to TopApps.selectSessionRows(end.rows, topN)
            else -> AppUsageBasis.DELTA to clampedDelta(baseline.rows, end.rows, topN)
        }
        val rows = selected.map { row ->
            val hints = end.tagHints[row.uid]?.takeUnless { row.isOthers }
            row.copy(topWakelockTag = hints?.wakelock, topAlarmTag = hints?.alarm, topJobName = hints?.job)
        }
        // A baseline UID can disappear because its end power record was rejected. Keep the
        // accepted deltas for browsing, but do not certify complete capture coverage for Insights.
        val captureStartMs = baseline?.let { start ->
            val endUids = end.rows.map { it.uid }.toSet()
            start.capturedAt.takeIf { basis != AppUsageBasis.DELTA || start.rows.all { it.uid in endUids } }
        }
        return AppUsageDeltaResult(basis, rows, wakerDelta(baseline, end, basis), captureStartMs, end.capturedAt)
    }

    private fun sameWindow(baseline: AppUsageSnapshot, end: AppUsageSnapshot): Boolean =
        baseline.windowStartedAt != null && end.windowStartedAt != null &&
            baseline.windowStartedAt == end.windowStartedAt &&
            baseline.windowStartCount != null && end.windowStartCount != null &&
            baseline.windowStartCount == end.windowStartCount

    /** A same-window dump whose total power went backwards (beyond floating-point rounding)
     * didn't really keep its window; treat it like a reset instead of reporting a bogus
     * negative-turned-zero delta. Per-uid fields are allowed to dip within a stable window
     * (batterystats redistributes/rounds them) — that's exactly what the clamp below is for.
     *
     * The totals are summed only over uids present in *both* snapshots: a uid that was
     * uninstalled between baseline and end drops out of `end.rows` entirely, and would otherwise
     * make the total look like it went backwards even though every remaining app's power only
     * rose — a false reset, not a real one. */
    private fun totalDecreased(baseline: List<AppUsageRow>, end: List<AppUsageRow>): Boolean {
        val baselineByUid = baseline.associateBy { it.uid }
        val endByUid = end.associateBy { it.uid }
        val commonUids = baselineByUid.keys intersect endByUid.keys
        val baselineTotal = commonUids.sumOf { baselineByUid.getValue(it).powerMah }
        val endTotal = commonUids.sumOf { endByUid.getValue(it).powerMah }
        return endTotal < baselineTotal - POWER_EPSILON
    }

    /** Legacy fields clamp decreases to zero; extended fields require baseline support and
     * observation of that counter in either dump before sparse absence can mean zero. */
    private fun clampedDelta(baselineRows: List<AppUsageRow>, endRows: List<AppUsageRow>, topN: Int): List<AppUsageRow> {
        val baselineByUid = baselineRows.associateBy { it.uid }
        // Schema-v6 baselines have every extended column null. Without a support marker,
        // keep those snapshots unknown even when the end reports extended counters.
        val extendedCountersSupported = baselineRows.any { row ->
            row.wakeupAlarms != null || row.partialWakelockCount != null || row.partialWakelockBgMs != null ||
                row.jobCount != null || row.jobMs != null || row.syncCount != null || row.fgServiceMs != null ||
                row.topMs != null || row.mobileActiveMs != null || row.gpsMs != null || row.sensorMs != null
        }
        // whittle: a counter no app used in either dump stays unknown; upgrade with a per-SDK support table.
        fun counterSupported(field: (AppUsageRow) -> Long?): Boolean = extendedCountersSupported &&
            (baselineRows.any { field(it) != null } || endRows.any { field(it) != null })
        val alarmsSupported = counterSupported(AppUsageRow::wakeupAlarms)
        val wakelockCountSupported = counterSupported(AppUsageRow::partialWakelockCount)
        val wakelockBgSupported = counterSupported(AppUsageRow::partialWakelockBgMs)
        val jobCountSupported = counterSupported(AppUsageRow::jobCount)
        val jobMsSupported = counterSupported(AppUsageRow::jobMs)
        val syncSupported = counterSupported(AppUsageRow::syncCount)
        val fgsSupported = counterSupported(AppUsageRow::fgServiceMs)
        val topSupported = counterSupported(AppUsageRow::topMs)
        val mobileActiveSupported = counterSupported(AppUsageRow::mobileActiveMs)
        val gpsSupported = counterSupported(AppUsageRow::gpsMs)
        val sensorSupported = counterSupported(AppUsageRow::sensorMs)
        val rows = endRows.map { end ->
            val base = baselineByUid[end.uid]
            end.copy(
                powerMah = (end.powerMah - (base?.powerMah ?: 0.0)).coerceAtLeast(0.0),
                cpuTimeMs = clampField(end.cpuTimeMs, base?.cpuTimeMs),
                foregroundTimeMs = clampField(end.foregroundTimeMs, base?.foregroundTimeMs),
                backgroundTimeMs = clampField(end.backgroundTimeMs, base?.backgroundTimeMs),
                wakelockTimeMs = clampField(end.wakelockTimeMs, base?.wakelockTimeMs),
                mobileBytes = clampField(end.mobileBytes, base?.mobileBytes),
                wifiBytes = clampField(end.wifiBytes, base?.wifiBytes),
                wakeupAlarms = nullableDelta(end.wakeupAlarms, base?.wakeupAlarms, alarmsSupported),
                partialWakelockCount = nullableDelta(end.partialWakelockCount, base?.partialWakelockCount, wakelockCountSupported),
                partialWakelockBgMs = nullableDelta(end.partialWakelockBgMs, base?.partialWakelockBgMs, wakelockBgSupported),
                jobCount = nullableDelta(end.jobCount, base?.jobCount, jobCountSupported),
                jobMs = nullableDelta(end.jobMs, base?.jobMs, jobMsSupported),
                syncCount = nullableDelta(end.syncCount, base?.syncCount, syncSupported),
                fgServiceMs = nullableDelta(end.fgServiceMs, base?.fgServiceMs, fgsSupported),
                topMs = nullableDelta(end.topMs, base?.topMs, topSupported),
                mobileActiveMs = nullableDelta(end.mobileActiveMs, base?.mobileActiveMs, mobileActiveSupported),
                gpsMs = nullableDelta(end.gpsMs, base?.gpsMs, gpsSupported),
                sensorMs = nullableDelta(end.sensorMs, base?.sensorMs, sensorSupported),
            )
        }
        // A supported counter that lost its end record or decreased is unknown, even if
        // every other counter is zero. Keep that row so absence cannot invent a zero.
        return TopApps.selectSessionRows(rows, topN) { row ->
            (alarmsSupported && row.wakeupAlarms == null) ||
                (wakelockCountSupported && row.partialWakelockCount == null) ||
                (wakelockBgSupported && row.partialWakelockBgMs == null) ||
                (jobCountSupported && row.jobCount == null) ||
                (jobMsSupported && row.jobMs == null) ||
                (syncSupported && row.syncCount == null) ||
                (fgsSupported && row.fgServiceMs == null) ||
                (topSupported && row.topMs == null) ||
                (mobileActiveSupported && row.mobileActiveMs == null) ||
                (gpsSupported && row.gpsMs == null) ||
                (sensorSupported && row.sensorMs == null)
        }
    }

    /** `max(0, end - base)`; null only when both sides are unknown, missing side counts as zero. */
    private fun clampField(end: Long?, base: Long?): Long? = when {
        end == null && base == null -> null
        else -> ((end ?: 0L) - (base ?: 0L)).coerceAtLeast(0L)
    }

    /** For a supported, observed counter, sparse absence on both sides is zero and a missing
     * baseline subtracts zero. Unsupported counters, lost end records and decreases stay unknown. */
    private fun nullableDelta(end: Long?, base: Long?, counterSupported: Boolean): Long? = when {
        !counterSupported -> null
        end == null -> if (base == null) 0L else null
        else -> (end - (base ?: 0L)).takeIf { it >= 0L }
    }

    private fun wakerDelta(baseline: AppUsageSnapshot?, end: AppUsageSnapshot, basis: AppUsageBasis): List<DeviceWaker> {
        if (baseline == null || basis != AppUsageBasis.DELTA) return emptyList()
        val byName = baseline.deviceWakers.associateBy { it.kind to it.name }
        val (kernels, reasons) = end.deviceWakers.mapNotNull { waker ->
            val base = byName[waker.kind to waker.name]
            if (base == null && baseline.wakersComplete != true) return@mapNotNull null
            waker.copy(count = (waker.count - (base?.count ?: 0L)).coerceAtLeast(0L),
                totalMs = (waker.totalMs - (base?.totalMs ?: 0L)).coerceAtLeast(0L))
        }.filter { it.count > 0 || it.totalMs > 0 }.partition { it.kind == "KERNEL_WAKELOCK" }
        // Reserve six time-ranked kernels and four count-ranked reasons; lend unused slots to the other kind.
        val selectedKernels = kernels.sortedWith(
            compareByDescending<DeviceWaker> { it.totalMs }.thenBy { it.name },
        ).take(6 + (4 - reasons.size).coerceAtLeast(0))
        val selectedReasons = reasons.sortedWith(
            compareByDescending<DeviceWaker> { it.count }.thenBy { it.name },
        ).take(10 - selectedKernels.size)
        return selectedKernels + selectedReasons
    }
}
