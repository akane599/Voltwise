package com.akane.voltwise.battery.insights.engine.stats

import kotlin.math.abs
import kotlin.math.pow

data class TimedValue(val atMs: Long, val value: Double)

/** Time-decayed median and median absolute deviation; non-finite observations are unsupported. */
data class RobustBaseline(val median: Double, val mad: Double, val samples: Int) {
    /** One absolute-floor change from the median scores at most z = 3. */
    fun robustZ(value: Double, absoluteFloor: Double): Double? {
        if (!value.isFinite() || !absoluteFloor.isFinite() || absoluteFloor <= 0.0) return null
        val scale = maxOf(MAD_SCALE * mad, absoluteFloor / Z_THRESHOLD)
        return ((value - median) / scale).takeIf { it.isFinite() }
    }

    companion object {
        const val MAD_SCALE = 1.4826
        const val Z_THRESHOLD = 3.0

        fun of(values: List<TimedValue>, nowMs: Long, halfLifeMs: Double): RobustBaseline? {
            require(halfLifeMs.isFinite() && halfLifeMs > 0.0)
            val valid = values.filter { it.value.isFinite() }
            if (valid.isEmpty()) return null
            // Relative ages preserve the weighted quantile while avoiding all weights underflowing
            // when the last observation is old. Future samples have age zero, never extra weight.
            val ages = valid.map { (nowMs.toDouble() - it.atMs.toDouble()).coerceAtLeast(0.0) }
            val youngest = ages.min()
            val weighted = valid.mapIndexed { index, point ->
                point.value to 2.0.pow(-(ages[index] - youngest) / halfLifeMs)
            }
            val median = weightedMedian(weighted)
            val mad = weightedMedian(weighted.map { (value, weight) -> abs(value - median) to weight })
            return RobustBaseline(median, mad, valid.size).takeIf { mad.isFinite() }
        }

        private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
            val sorted = values.filter { it.second > 0.0 }.sortedBy { it.first }
            val middle = sorted.sumOf { it.second } / 2.0
            var weight = 0.0
            for ((index, point) in sorted.withIndex()) {
                weight += point.second
                if (weight > middle) return point.first
                if (weight == middle && index < sorted.lastIndex) {
                    return point.first / 2.0 + sorted[index + 1].first / 2.0
                }
            }
            return sorted.last().first
        }
    }
}

internal fun median(values: List<Double>): Double? {
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
}
