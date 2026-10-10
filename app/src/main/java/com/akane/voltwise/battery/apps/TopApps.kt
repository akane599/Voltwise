package com.akane.voltwise.battery.apps

/** One app of [TopApps]: its power over the window and its share of every app's power (0..1). */
data class TopAppRow(val uid: Int, val packageName: String, val powerMah: Double, val share: Float)

/**
 * The biggest users in one per-app dump, over the window [basis] says: since the discharge session's baseline
 * ([AppUsageBasis.DELTA]), or absolute values when there is no usable baseline. [capturedAt] is the dump's time.
 */
data class TopApps(val rows: List<TopAppRow>, val basis: AppUsageBasis, val capturedAt: Long) {
    companion object {
        const val COUNT = 3

        /** Power leaders, then additional waking UIDs, then the unselected tail exactly once. */
        internal fun selectSessionRows(
            rows: List<AppUsageRow>,
            topN: Int,
            hasUnknownCounters: (AppUsageRow) -> Boolean = { false },
        ): List<AppUsageRow> {
            val sorted = rows.filter { row -> row.powerMah > 0 ||
                listOf(row.cpuTimeMs, row.foregroundTimeMs, row.backgroundTimeMs, row.wakelockTimeMs,
                    row.mobileBytes, row.wifiBytes, row.wakeupAlarms, row.partialWakelockCount, row.partialWakelockBgMs, row.jobCount, row.jobMs, row.syncCount, row.fgServiceMs, row.topMs, row.mobileActiveMs, row.gpsMs, row.sensorMs).any { (it ?: 0L) > 0L } ||
                hasUnknownCounters(row)
            }.sortedByDescending { it.powerMah }
            val power = sorted.take(topN.coerceIn(0, 30))
            val selectedUids = power.map { it.uid }.toSet()
            val wakers = sorted.filter { it.uid !in selectedUids &&
                ((it.wakeupAlarms ?: 0L) > 0L || (it.partialWakelockBgMs ?: 0L) > 0L) }
                .sortedWith(compareByDescending<AppUsageRow> { it.wakeupAlarms ?: 0L }
                    .thenByDescending { it.partialWakelockBgMs ?: 0L }.thenBy { it.uid }).take(10)
            val selected = power + wakers
            val kept = selected.map { it.uid }.toSet()
            val rest = sorted.filter { it.uid !in kept }
            if (rest.isEmpty()) return selected
            return selected + AppUsageRow(
                uid = -1, packageName = "", powerMah = rest.sumOf { it.powerMah }, isOthers = true,
                cpuTimeMs = foldLong(rest, AppUsageRow::cpuTimeMs),
                foregroundTimeMs = foldLong(rest, AppUsageRow::foregroundTimeMs),
                backgroundTimeMs = foldLong(rest, AppUsageRow::backgroundTimeMs),
                wakelockTimeMs = foldLong(rest, AppUsageRow::wakelockTimeMs),
                mobileBytes = foldLong(rest, AppUsageRow::mobileBytes),
                wifiBytes = foldLong(rest, AppUsageRow::wifiBytes),
                wakeupAlarms = foldLong(rest, AppUsageRow::wakeupAlarms),
                partialWakelockCount = foldLong(rest, AppUsageRow::partialWakelockCount),
                partialWakelockBgMs = foldLong(rest, AppUsageRow::partialWakelockBgMs),
                jobCount = foldLong(rest, AppUsageRow::jobCount),
                jobMs = foldLong(rest, AppUsageRow::jobMs),
                syncCount = foldLong(rest, AppUsageRow::syncCount),
                fgServiceMs = foldLong(rest, AppUsageRow::fgServiceMs),
                topMs = foldLong(rest, AppUsageRow::topMs),
                mobileActiveMs = foldLong(rest, AppUsageRow::mobileActiveMs),
                gpsMs = foldLong(rest, AppUsageRow::gpsMs),
                sensorMs = foldLong(rest, AppUsageRow::sensorMs),
            )
        }

        private fun foldLong(rows: List<AppUsageRow>, field: (AppUsageRow) -> Long?): Long? {
            var total = 0L
            for (row in rows) {
                val value = field(row) ?: return null
                if (value < 0L || value > Long.MAX_VALUE - total) return null
                total += value
            }
            return total
        }

        /**
         * The top [count] real apps of [usage] (the folded "others" row only counts toward the total). [baseline] is
         * used only when it is older than [usage]: a dump taken before the session's baseline predates the session.
         * Null when no app used power.
         */
        fun of(usage: AppUsageSnapshot, baseline: AppUsageSnapshot?, count: Int = COUNT): TopApps? {
            val result = AppUsageDelta.compute(baseline?.takeIf { usage.capturedAt > it.capturedAt }, usage)
            val total = result.rows.sumOf { it.powerMah.coerceAtLeast(0.0) }
            val top = result.rows.filter { !it.isOthers && it.powerMah > 0 }.take(count)
            if (total <= 0 || top.isEmpty()) return null
            return TopApps(
                rows = top.map { TopAppRow(it.uid, it.packageName, it.powerMah, (it.powerMah / total).toFloat()) },
                basis = result.basis,
                capturedAt = usage.capturedAt,
            )
        }
    }
}
