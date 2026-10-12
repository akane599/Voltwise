package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.measurement.CapacityEstimate
import com.akane.voltwise.battery.measurement.CapacityEstimator
import com.akane.voltwise.battery.measurement.HealthSummary

/** At this level, integer-level quantisation changes counter-derived full capacity by at most about 2%. */
private const val MIN_LEVEL_TO_PREFER_COUNTER = 50

/** Drain capacity: prefer the usable counter at ≥ 50%, otherwise the stored estimate; never design capacity. */
fun resolveFullUah(counterUah: Long?, levelPct: Int?, storedEstimateUah: Long?): Long? {
    val counterFullUah = HealthSummary.counterFullUah(counterUah, levelPct)
    return if (levelPct != null && levelPct >= MIN_LEVEL_TO_PREFER_COUNTER) {
        counterFullUah ?: storedEstimateUah
    } else {
        storedEstimateUah ?: counterFullUah
    }
}

/** Usable local estimates, or imported estimates when no local estimate exists. */
fun usableStoredEstimates(sessions: List<ChargeSession>): List<CapacityEstimate> {
    val (imported, local) = sessions.mapNotNull { session ->
        HealthSummary.storedEstimate(session.capacityEstimateMah, session.capacityConfidence, session.capacityBasis)
            ?.let { session to it }
    }.partition { (session, _) -> session.source.startsWith("import:") || session.sessionId.startsWith("import:") }
    return local.ifEmpty { imported }.map { it.second }
}

/** Confidence-weighted median of usable local estimates, or imported estimates when no local estimate exists. */
fun storedFullUah(sessions: List<ChargeSession>): Long? =
    CapacityEstimator.combine(usableStoredEstimates(sessions))?.fullUah
