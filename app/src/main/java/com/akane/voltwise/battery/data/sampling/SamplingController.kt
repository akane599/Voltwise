package com.akane.voltwise.battery.data.sampling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.measurement.BatteryReading
import com.akane.voltwise.battery.measurement.Boundary
import com.akane.voltwise.battery.measurement.EtaBasis
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.measurement.SamplingPolicy
import com.akane.voltwise.battery.measurement.StateEventSequencer
import com.akane.voltwise.battery.service.SamplingDemand
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun shouldMarkContinuityLoss(observe: Boolean): Boolean = observe

/** Immediate token ownership; cadence reads and [synchronize] belong to the sampler thread. */
internal class SamplerDemandState(private val changed: () -> Unit) : SamplingDemand {
    private val holders = AtomicInteger()
    var held: Boolean = false
        private set

    // Never refresh this inside capture/schedule: a queued last release must still hand off.
    fun synchronize() { held = holders.get() > 0 }

    override fun acquire(tag: String): AutoCloseable {
        if (holders.incrementAndGet() == 1) changed()
        return object : AutoCloseable {
            private val closed = AtomicBoolean()
            override fun close() {
                if (closed.compareAndSet(false, true) && holders.decrementAndGet() == 0) changed()
            }
        }
    }
}

/**
 * A sequenced observation: [sample] and [point] hold RAW `CURRENT_NOW` (the database keeps raw
 * values). [poll] is true for timer and handoff captures (PersistPolicy), false for broadcasts
 * and refreshes. [chargeRemainingMs] is `computeChargeTimeRemaining()` (API 28+, charging).
 */
data class SamplerCapture(
    val sample: BatterySample,
    val point: Observation,
    val poll: Boolean,
    val chargeRemainingMs: Long?,
)

/** Receives the sampler's output; implemented by BatteryRepository. Every call is on the sampler thread. */
interface SamplerSink {
    /** Every capture, observed or not: realtime values and alerts. */
    fun onReading(sample: BatterySample, expectedIntervalMs: Long)

    /** A sequenced observation while monitoring; false when it was dropped (the next one becomes a GAP). */
    fun onObserved(capture: SamplerCapture): Boolean

    /** A battery read problem, or null once reads work again. */
    fun onBatteryIssue(message: String?)

    /** A state-event subscription problem, or null once subscribed or stopped. */
    fun onStateEventsIssue(message: String?)
}

/**
 * The only battery sampler. A HandlerThread ("batstats-sampler") owns the broadcast receivers,
 * every BatteryManager/PowerManager Binder read, the [StateEventSequencer] and the overflow flag,
 * so no sampling work runs on main and none of that state is shared.
 *
 * Cadence ([SamplingPolicy]): 2 s while a [SamplingDemand] token is held, else 30 s with the
 * screen on and 300 s with it off. Polls are `Handler.postDelayed` on the uptime clock, so a
 * pending poll never wakes a sleeping CPU; broadcasts still capture while it sleeps. When demand or
 * monitoring changes the interval, a handoff capture stamped with the new interval is taken at
 * once; a screen change arrives as a SCREEN capture that is already stamped. Monitoring off: only
 * demand polls, realtime values only (never observed or saved). [readOnce] never reaches history.
 */
class SamplingController(
    private val context: Context,
    private val diagnostics: DiagnosticStore,
) : SamplingDemand {
    private val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val demand = SamplerDemandState { handler.post { onCadenceInputsChanged() } }

    @Volatile private var sink: SamplerSink? = null

    // Confined to the sampler thread.
    private val sequencer = StateEventSequencer<SamplerCapture>()
    private var overflow = false
    private var generation: String? = null
    private var receiverRegistered = false
    private var screenOn = true
    private var scheduledIntervalMs: Long? = null

    private val demandHeld: Boolean get() = demand.held

    private val pollTask = Runnable {
        Log.d(LOG_TAG, "poll interval=${scheduledIntervalMs}ms demand=$demandHeld screen=${screenLabel()} monitoring=${generation != null}")
        capture(Boundary.SAMPLE, poll = true)
        schedule(pollIntervalMs())
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Registered with the sampler's Handler; a direct call from elsewhere is moved there.
            if (Looper.myLooper() == handler.looper) onBroadcast(intent) else handler.post { onBroadcast(intent) }
        }
    }

    /** Called once by the repository before it starts monitoring. */
    fun attach(sink: SamplerSink) {
        this.sink = sink
    }

    override fun acquire(tag: String): AutoCloseable = demand.acquire(tag)

    /** Subscribes to state events and observes under [generation] until [stopMonitoring]. */
    fun startMonitoring(generation: String) {
        handler.post {
            this.generation = generation
            sequencer.reset()
            register()
            capture(Boundary.SAMPLE, poll = true)
            schedule(pollIntervalMs())
            Log.d(LOG_TAG, "monitoring started interval=${scheduledIntervalMs}ms demand=$demandHeld screen=${screenLabel()}")
        }
    }

    fun stopMonitoring() {
        handler.post {
            generation = null
            sequencer.reset()
            unregister()
            sink?.onStateEventsIssue(null)
            val next = pollIntervalMs()
            if (next != scheduledIntervalMs) schedule(next)
            Log.d(LOG_TAG, "monitoring stopped interval=${scheduledIntervalMs}ms demand=$demandHeld")
        }
    }

    /** After an observation reset: forget the pending state event and capture a fresh first point. */
    fun restartSequence() {
        handler.post {
            sequencer.reset()
            capture(Boundary.SAMPLE, poll = false)
        }
    }

    /** One capture now, observed when monitoring (a manual refresh). Null when the read failed. */
    suspend fun refresh(): BatterySample? = onSampler { capture(Boundary.SAMPLE, poll = false) }

    /** One capture that never reaches the observation or history; works with monitoring off. */
    suspend fun readOnce(): BatterySample? = onSampler { capture(Boundary.SAMPLE, poll = false, observe = false) }

    /** The writer could not take an observation: the next one carries a GAP. */
    fun markGap() {
        handler.post { overflow = true }
    }

    /** Tests only: releases the receivers and ends the thread. */
    fun shutdown() {
        handler.post {
            unregister()
            schedule(null)
        }
        thread.quitSafely()
    }

    private suspend fun <T> onSampler(block: () -> T): T? {
        val result = CompletableDeferred<T?>()
        val posted = handler.post {
            try {
                result.complete(block())
            } finally {
                result.complete(null)
            }
        }
        return if (posted) result.await() else null
    }

    private fun pollIntervalMs(): Long? = SamplingPolicy.pollIntervalMs(generation != null, demandHeld, screenOn)

    private fun schedule(intervalMs: Long?) {
        handler.removeCallbacks(pollTask)
        scheduledIntervalMs = intervalMs
        if (intervalMs != null) handler.postDelayed(pollTask, intervalMs)
    }

    /** Demand changed: when that changes the interval, take the handoff capture now. */
    private fun onCadenceInputsChanged() {
        demand.synchronize()
        val next = pollIntervalMs()
        if (SamplingPolicy.needsHandoffCapture(scheduledIntervalMs, next)) {
            Log.d(LOG_TAG, "handoff interval=${next}ms demand=$demandHeld screen=${screenLabel()} monitoring=${generation != null}")
            capture(Boundary.SAMPLE, poll = true)
            schedule(pollIntervalMs())
        } else if (next == null) {
            schedule(null)
        }
    }

    private fun onBroadcast(intent: Intent) {
        val boundary = when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> Boundary.SCREEN
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> Boundary.DOZE
            else -> Boundary.POWER
        }
        val screenOverride = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> true
            Intent.ACTION_SCREEN_OFF -> false
            else -> null
        }
        capture(boundary, screenOverride, poll = false)
        Log.d(LOG_TAG, "broadcast ${intent.action?.substringAfterLast('.')} screen=${screenLabel()}")
        // A screen change is itself a capture stamped with the new interval: just reschedule.
        val next = pollIntervalMs()
        if (next != scheduledIntervalMs) schedule(next)
    }

    private fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(context, receiver, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            sink?.onStateEventsIssue(null)
        } catch (e: RuntimeException) {
            diagnostics.record(DiagnosticCode.STATE_EVENTS_UNAVAILABLE)
            sink?.onStateEventsIssue("State events unavailable; observation is paused, ordinary battery readings continue")
        }
    }

    private fun unregister() {
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun capture(boundary: Boundary, screenOverride: Boolean? = null, poll: Boolean, observe: Boolean = true): BatterySample? =
        try {
            read(boundary, screenOverride, poll, observe)
        } catch (e: RuntimeException) {
            diagnostics.record(DiagnosticCode.BATTERY_READ_FAILED)
            sink?.onBatteryIssue("Battery collection failed (${e.javaClass.simpleName})")
            if (shouldMarkContinuityLoss(observe)) overflow = true
            null
        }

    private fun read(boundary: Boundary, screenOverride: Boolean?, poll: Boolean, observe: Boolean): BatterySample? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            diagnostics.record(DiagnosticCode.BATTERY_UNAVAILABLE)
            sink?.onBatteryIssue("Android has not supplied a battery reading")
            if (shouldMarkContinuityLoss(observe)) overflow = true
            return null
        }
        fun extra(name: String): Int? = if (intent.hasExtra(name)) intent.getIntExtra(name, Int.MIN_VALUE) else null
        val failedProperties = mutableListOf<String>()
        fun property(id: Int, name: String): Long = try {
            batteryManager.getLongProperty(id)
        } catch (_: RuntimeException) {
            failedProperties += name
            Long.MIN_VALUE
        }
        val status = extra(BatteryManager.EXTRA_STATUS) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = extra(BatteryManager.EXTRA_PLUGGED)?.takeIf { it >= 0 }
        val power = BatteryReading.powerState(status, plugged)
        val chargeTime = if (Build.VERSION.SDK_INT >= 28 && power == PowerState.CHARGING) {
            try {
                batteryManager.computeChargeTimeRemaining().takeIf { it in 1..7 * 86_400_000L }
            } catch (_: RuntimeException) {
                failedProperties += "charge_time"
                null
            }
        } else null
        val currentNow = BatteryReading.currentUa(property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "current_now"))
        val chargeCounter = BatteryReading.chargeUah(property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "charge_counter"))
        val currentAverage = BatteryReading.currentUa(property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "current_average"))
        val energy = BatteryReading.energyNwh(property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, "energy_counter"))
        val interactive = powerManager.isInteractive
        val dozing = powerManager.isDeviceIdleMode
        // A queued SCREEN_OFF after an already-completed wake cannot establish an off
        // endpoint. Preserve the actual reading and mark the contradictory event as a gap.
        val captureBoundary = if (screenOverride != null && screenOverride != interactive) Boundary.GAP else boundary
        // Keep the monotonic clock pair adjacent: Binder/property latency between these
        // reads would otherwise appear as CPU suspend or a clock discontinuity.
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        val observing = generation?.takeIf { observe }
        val sample = BatterySample(
            timestamp = wall,
            levelPercent = BatteryReading.percentage(extra(BatteryManager.EXTRA_LEVEL) ?: -1, extra(BatteryManager.EXTRA_SCALE) ?: -1),
            status = status, plugged = plugged,
            currentNowUa = currentNow,
            chargeCounterUah = chargeCounter,
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE)?.let(BatteryReading::voltageMv),
            temperatureDeciC = extra(BatteryManager.EXTRA_TEMPERATURE)?.let(BatteryReading::temperatureDeciC),
            health = extra(BatteryManager.EXTRA_HEALTH),
            screenOn = interactive,
            elapsedMs = elapsed, uptimeMs = uptime, observationId = observing,
            currentAverageUa = currentAverage,
            energyNwh = energy,
            cycleCount = if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 } else null,
            etaMs = chargeTime,
            etaBasis = chargeTime?.let { EtaBasis.ANDROID.name },
            source = DailySummaryReplay.SAMPLE_SOURCE,
        )
        if (failedProperties.isNotEmpty()) diagnostics.record(DiagnosticCode.BATTERY_READ_FAILED)
        sink?.onBatteryIssue(failedProperties.takeIf { it.isNotEmpty() }?.let { "Battery property reads failed: ${it.joinToString()}" })
        screenOn = interactive
        sink?.onReading(sample, SamplingPolicy.expectedIntervalMs(demandHeld, interactive))
        // Polling alone cannot establish screen/Doze continuity or prove that no transition happened.
        if (observing == null || !receiverRegistered) return sample
        val point = SamplingPolicy.stamp(
            Observation(wall, elapsed, uptime, sample.levelPercent, chargeCounter, currentNow, sample.voltageMv, power,
                interactive, dozing, observing, boundary = if (overflow) Boundary.GAP else captureBoundary),
            demandHeld,
        )
        for (sequenced in sequencer.offer(SamplerCapture(sample, point, poll, chargeTime), point)) {
            // Overflow may occur between the retained capture and its confirming event.
            val delivered = if (overflow) sequenced.point.copy(boundary = Boundary.GAP) else sequenced.point
            overflow = sink?.onObserved(sequenced.value.copy(point = delivered)) != true
        }
        return sample
    }

    private fun screenLabel() = if (screenOn) "on" else "off"

    companion object {
        const val THREAD_NAME = "batstats-sampler"

        /** `adb logcat -d --pid=… | grep BatStatsSampler`: one line per poll, broadcast and save. */
        const val LOG_TAG = "BatStatsSampler"
    }
}
