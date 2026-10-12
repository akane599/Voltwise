package com.akane.voltwise.battery.measurement

/** Alerts describe ordinary reported readings, not attributed energy or charging controls. */
enum class BatteryAlert { LOW, HIGH, TEMPERATURE, DISCHARGE, FULL }

data class BatteryAlertSettings(
    val low: Boolean = true, val lowPercent: Int = 20,
    val high: Boolean = false, val highPercent: Int = 80,
    val temperature: Boolean = true, val temperatureC: Double = 45.0,
    val discharge: Boolean = false, val dischargeMa: Int = 600,
    val full: Boolean = true
)
data class AlertReading(val elapsedMs: Long, val level: Int?, val status: Int, val plugged: Int?,
    val currentUa: Long?, val temperatureDeciC: Int?, val samplingIntervalMs: Long, val bootCount: Int? = null)

/** Hysteresis and persisted episode latches prevent per-sample and service-restart alert storms. */
class BatteryAlerts(initialLatches: Set<BatteryAlert> = emptySet(), initialElapsedMs: Long? = null,
    private val initialBootCount: Int? = null) {
    private val active = initialLatches.toMutableSet()
    val latches: Set<BatteryAlert> get() = active.toSet()
    private var beforeDelivery: Set<BatteryAlert> = emptySet()
    fun retryDelivery() { active.clear(); active.addAll(beforeDelivery) }
    private var restoring = initialLatches.isNotEmpty()
    var lastAcceptedElapsedMs: Long? = initialElapsedMs
        private set
    private var highCurrentSince: Long? = null
    private var highCurrentSamples = 0

    fun accept(reading: AlertReading, settings: BatteryAlertSettings): Set<BatteryAlert> {
        val previous = lastAcceptedElapsedMs
        if (!restoring && previous != null && reading.elapsedMs == previous) return emptySet()
        val gap = previous != null && (reading.elapsedMs < previous ||
            reading.elapsedMs - previous > reading.samplingIntervalMs.coerceIn(5_000, 300_000) * 3 + 10_000)
        if (restoring) {
            val anotherBoot = initialBootCount != null && reading.bootCount != null && initialBootCount != reading.bootCount
            if (previous == null || gap || anotherBoot) active.clear()
            restoring = false
        }
        // Delivery failure must roll back to the validated episode, never to an expired saved latch.
        beforeDelivery = active.toSet()
        lastAcceptedElapsedMs = reading.elapsedMs
        if (gap) { highCurrentSince = null; highCurrentSamples = 0 }
        val level = reading.level?.takeIf { it in 0..100 }
        val power = BatteryReading.powerState(reading.status, reading.plugged)
        val current = reading.currentUa?.let(BatteryReading::currentUa)
        val temperature = reading.temperatureDeciC?.let(BatteryReading::temperatureDeciC)?.div(10.0)
        val low = settings.lowPercent.coerceIn(5, 50)
        val high = settings.highPercent.coerceIn(50, 100)
        val tempLimit = settings.temperatureC.takeIf(Double::isFinite)?.coerceIn(35.0, 55.0) ?: 45.0
        val dischargeLimit = settings.dischargeMa.coerceIn(200, 2000)
        val events = mutableSetOf<BatteryAlert>()
        fun check(type: BatteryAlert, enabled: Boolean, trigger: Boolean?, rearm: Boolean?) {
            if (!enabled || rearm == true) active.remove(type)
            if (enabled && trigger == true && active.add(type)) events += type
        }
        val knownPower = power != PowerState.UNKNOWN
        val isDischarging = power == PowerState.DISCHARGING
        check(BatteryAlert.LOW, settings.low,
            if (knownPower && level != null) isDischarging && level <= low else null,
            if (knownPower) !isDischarging || level?.let { it >= low + 3 } == true else null)
        check(BatteryAlert.HIGH, settings.high,
            if (knownPower && level != null) !isDischarging && level >= high else null,
            if (knownPower) isDischarging || level?.let { it <= high - 3 } == true else null)
        check(BatteryAlert.TEMPERATURE, settings.temperature, temperature?.let { it >= tempLimit }, temperature?.let { it <= tempLimit - 2 })
        check(BatteryAlert.FULL, settings.full,
            if (knownPower) reading.status == 5 && reading.plugged != 0 else null,
            if (knownPower) isDischarging || level?.let { it < 97 } == true else null)

        // A vendor reporting positive discharge current is not silently interpreted as equivalent.
        val reportedDischarge = if (knownPower && current != null && (isDischarging && current <= 0 || !isDischarging)) {
            if (isDischarging) -current / 1000.0 else 0.0
        } else null
        val above = reportedDischarge != null && reportedDischarge >= dischargeLimit
        if (settings.discharge && above) {
            if (highCurrentSince == null) highCurrentSince = reading.elapsedMs
            highCurrentSamples++
        } else { highCurrentSince = null; highCurrentSamples = 0 }
        val qualified = above && highCurrentSamples >= 3 && reading.elapsedMs - (highCurrentSince ?: reading.elapsedMs) >= 60_000
        check(BatteryAlert.DISCHARGE, settings.discharge,
            if (reportedDischarge != null) qualified else null,
            reportedDischarge?.let { it <= dischargeLimit * 0.8 })
        // Reaching full and the configured high threshold on one reading needs one notification.
        if (BatteryAlert.FULL in events) events.remove(BatteryAlert.HIGH)
        return events
    }
}
