package com.akane.voltwise.battery.insights.engine.stats

import kotlin.math.abs

/** Signed changes. Relative change is undefined for a zero baseline unless both medians are zero. */
data class EffectSize(val before: Double, val after: Double, val absolute: Double, val relative: Double?) {
    companion object {
        fun between(before: List<Double>, after: List<Double>): EffectSize? {
            val previous = median(before) ?: return null
            val current = median(after) ?: return null
            val change = current - previous
            if (!change.isFinite()) return null
            val relative = if (previous == 0.0) {
                if (current == 0.0) 0.0 else null
            } else {
                (change / abs(previous)).takeIf { it.isFinite() }
            }
            return EffectSize(previous, current, change, relative)
        }
    }
}
