package com.akane.voltwise.battery.insights.engine.stats

import kotlin.math.sqrt

object TheilSen {
    data class Trend(val slope: Double, val mannKendallZ: Double)

    /** Median pairwise slope, in value per millisecond. Equal timestamps cannot define a slope. */
    fun slope(values: List<TimedValue>): Double? = trend(values)?.slope

    /** Slope and lag-1 autocorrelation-adjusted, continuity-corrected Mann–Kendall z use the same finite values. */
    fun trend(values: List<TimedValue>): Trend? {
        val valid = values.filter { it.value.isFinite() }.sortedBy { it.atMs }
        val slopes = mutableListOf<Double>()
        var score = 0.0
        for (left in valid.indices) {
            for (right in left + 1 until valid.size) {
                val elapsed = valid[right].atMs.toDouble() - valid[left].atMs.toDouble()
                if (elapsed > 0.0) {
                    val slope = (valid[right].value - valid[left].value) / elapsed
                    if (slope.isFinite()) {
                        slopes += slope
                        score += when {
                            valid[right].value > valid[left].value -> 1.0
                            valid[right].value < valid[left].value -> -1.0
                            else -> 0.0
                        }
                    }
                }
            }
        }
        val slope = median(slopes) ?: return null
        val n = valid.size.toDouble()
        // Fit relative to the first timestamp to avoid subtracting a large epoch intercept.
        val origin = valid.first().atMs.toDouble()
        val detrended = valid.map { it.value - slope * (it.atMs.toDouble() - origin) }
        val intercept = median(detrended) ?: return null
        val residuals = detrended.map { it - intercept }
        val mean = residuals.average()
        val centered = residuals.map { it - mean }
        val sumSquares = centered.sumOf { it * it }
        val lagProduct = (1 until centered.size).sumOf { centered[it - 1] * centered[it] }
        val r1 = if (sumSquares > 0.0) {
            val correlation = lagProduct / sumSquares
            (correlation + (1.0 + 4.0 * correlation) / n).coerceIn(0.0, 0.9)
        } else 0.0
        // Kendall bias correction and full AR(1) variance inflation. Nonpositive corrected
        // correlation cannot reduce the iid variance; a perfect fit has no residual correlation.
        // Omitting the value-tie correction remains conservative for tied capacity estimates.
        val variance = n * (n - 1) * (2 * n + 5) / 18.0 * (1.0 + r1) / (1.0 - r1)
        val correctedScore = when {
            score < 0.0 -> score + 1.0
            score > 0.0 -> score - 1.0
            else -> 0.0
        }
        return Trend(slope, correctedScore / sqrt(variance))
    }
}
