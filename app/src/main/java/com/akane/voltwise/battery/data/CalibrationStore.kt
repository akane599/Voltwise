package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.measurement.CalibrationBasis
import com.akane.voltwise.battery.measurement.CalibrationDecision
import com.akane.voltwise.battery.measurement.CalibrationSource
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentCalibrator
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.measurement.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Settings overrides for how `CURRENT_NOW` is read; null means Auto (use detection). */
data class CalibrationOverrides(val unit: CurrentUnit? = null, val sign: CurrentSign? = null)

/**
 * Owns the current calibration: feeds [CurrentCalibrator] from observed captures (raw current and
 * counter), keeps the detected result in [store] (non-backed-up SharedPreferences, file
 * [PREFS_NAME]; never part of the settings export) and merges Settings [overrides], which win.
 *
 * A detected correction that changes the effective calibration raises the notice; it stays
 * pending until [dismissNotice] or [undoLastCorrection]. Undo restores the previous detection and
 * rejects the undone one, so the same evidence is not applied again until [reset]. A sign-only
 * fast-path decision keeps the unit of an earlier window decision. "Clear all data" does not
 * touch this store. Thread-safe: the sampler's writer calls [accept], the UI the rest.
 */
class CalibrationStore(
    private val store: KeyValueStore,
    overrides: Flow<CalibrationOverrides>,
    scope: CoroutineScope,
) {
    private data class Detection(val calibration: CurrentCalibration, val windows: Int)

    private val lock = Any()
    private val calibrator = CurrentCalibrator()
    private var overrideValues = CalibrationOverrides()
    private var detected: Detection? = decode(store.getString(KEY_DETECTED))

    /** Present only while an undo is possible; its value may be "no earlier detection". */
    private var previous: Detection? = decode(store.getString(KEY_PREVIOUS))
    private var hasPrevious = previous != null || store.getString(KEY_PREVIOUS) == NONE
    private var rejected: CurrentCalibration? = decode(store.getString(KEY_REJECTED))?.calibration
    private var notice = store.getString(KEY_NOTICE) == true.toString()

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    init {
        scope.launch {
            overrides.distinctUntilChanged().collect { values ->
                synchronized(lock) {
                    overrideValues = values
                    publish()
                }
            }
        }
    }

    /** Every observed capture in order; [point] must carry the RAW `currentUa`. */
    fun accept(point: Observation, plugged: Int?) {
        synchronized(lock) {
            val decision = calibrator.accept(point, plugged) ?: return
            val next = Detection(candidate(decision), decision.agreeingWindows)
            val current = detected
            if (next.calibration == current?.calibration) {
                if (decision.basis == CalibrationBasis.POSITIVE_WHILE_DISCHARGING && current.windows > 0) return
                if (next.windows == current.windows) return
                detected = next
            } else {
                if (next.calibration == rejected) return
                val visible = effective(next) != effective(current)
                previous = current
                hasPrevious = true
                detected = next
                notice = notice || visible
            }
            save()
            publish()
        }
    }

    fun undoLastCorrection() {
        synchronized(lock) {
            if (!hasPrevious) return
            rejected = detected?.calibration
            detected = previous
            previous = null
            hasPrevious = false
            notice = false
            save()
            publish()
        }
    }

    fun dismissNotice() {
        synchronized(lock) {
            if (!notice) return
            notice = false
            save()
            publish()
        }
    }

    /** Settings › "Reset calibration": forgets detection, rejections and evidence. */
    fun reset() {
        synchronized(lock) {
            calibrator.reset()
            detected = null
            previous = null
            hasPrevious = false
            rejected = null
            notice = false
            save()
            publish()
        }
    }

    /** A fast-path decision is sign-only: it keeps the unit an earlier window decision found. */
    private fun candidate(decision: CalibrationDecision): CurrentCalibration = when (decision.basis) {
        CalibrationBasis.COUNTER_WINDOWS -> decision.calibration
        CalibrationBasis.POSITIVE_WHILE_DISCHARGING ->
            decision.calibration.copy(unit = detected?.calibration?.unit ?: decision.calibration.unit)
    }

    private fun effective(detection: Detection?) = CurrentCalibration(
        unit = overrideValues.unit ?: detection?.calibration?.unit ?: CurrentUnit.MICROAMPS,
        sign = overrideValues.sign ?: detection?.calibration?.sign ?: CurrentSign.NORMAL,
    )

    private fun compute() = CalibrationState(
        effective = effective(detected),
        detected = detected?.calibration,
        source = when {
            overrideValues.unit != null || overrideValues.sign != null -> CalibrationSource.OVERRIDE
            detected != null -> CalibrationSource.DETECTED
            else -> CalibrationSource.DEFAULT
        },
        agreeingWindows = detected?.windows ?: 0,
        noticePending = notice,
    )

    private fun publish() {
        _state.value = compute()
    }

    private fun save() = store.edit(
        mapOf(
            KEY_DETECTED to detected?.let(::encode),
            KEY_PREVIOUS to if (hasPrevious) previous?.let(::encode) ?: NONE else null,
            KEY_REJECTED to rejected?.let { encode(Detection(it, 0)) },
            KEY_NOTICE to if (notice) true.toString() else null,
        ),
    )

    companion object {
        const val PREFS_NAME = "calibration"
        private const val KEY_DETECTED = "detected"
        private const val KEY_PREVIOUS = "previous"
        private const val KEY_REJECTED = "rejected"
        private const val KEY_NOTICE = "notice"
        private const val NONE = "none"

        private fun encode(detection: Detection) =
            "${detection.calibration.unit.name}/${detection.calibration.sign.name}/${detection.windows}"

        /** Tolerant: anything unreadable counts as absent. */
        private fun decode(value: String?): Detection? {
            val parts = value?.split('/')?.takeIf { it.size == 3 } ?: return null
            val unit = CurrentUnit.entries.firstOrNull { it.name == parts[0] } ?: return null
            val sign = CurrentSign.entries.firstOrNull { it.name == parts[1] } ?: return null
            val windows = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return Detection(CurrentCalibration(unit, sign), windows)
        }
    }
}
