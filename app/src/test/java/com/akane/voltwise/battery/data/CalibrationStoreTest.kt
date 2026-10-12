package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.measurement.CalibrationSource
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.PowerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The store's state machine, fed through the real CurrentCalibrator with scripted captures. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationStoreTest {
    private val prefs = FakeKeyValueStore()
    private val overrides = MutableStateFlow(CalibrationOverrides())
    private var elapsed = 0L
    private var charge = 4_000_000L

    private val invertedMicroamps = CurrentCalibration(CurrentUnit.MICROAMPS, CurrentSign.INVERTED)
    private val milliamps = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL)
    private val invertedMilliamps = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED)

    private fun CoroutineScope.store(flow: Flow<CalibrationOverrides> = overrides) = CalibrationStore(prefs, flow, this)

    /** [count] unplugged 30 s captures at a true 300 mA drain, reported as report(true Android-signed µA). */
    private fun CalibrationStore.discharge(count: Int, report: (Long) -> Long) = repeat(count) {
        elapsed += 30_000
        charge -= 2_500
        accept(Observation(1_000_000 + elapsed, elapsed, elapsed, 80, charge, report(-300_000), 4000,
            PowerState.DISCHARGING, true, false, "run", 30_000), plugged = 0)
    }

    /** The fast path: 20 positive readings while unplugged with a falling counter (sign only). */
    private fun CalibrationStore.positiveWhileDischarging() = discharge(20) { -it }

    /** Three agreeing 10-minute counter windows (unit and sign), plus the opening capture. */
    private fun CalibrationStore.threeWindows(report: (Long) -> Long) = discharge(61, report)

    private fun assertState(expected: CalibrationState, store: CalibrationStore) = assertEquals(expected, store.state.value)

    @Test fun startsAtIdentityWithoutEvidence() = runTest(UnconfinedTestDispatcher()) {
        val store = backgroundScope.store()
        assertState(CalibrationState(), store)
        store.discharge(19) { -it }
        assertState(CalibrationState(), store)
        assertEquals(0, prefs.writes)
    }

    @Test fun detectedCorrectionAppliesWithANoticeAndSurvivesARestart() = runTest(UnconfinedTestDispatcher()) {
        val store = backgroundScope.store()
        store.positiveWhileDischarging()
        val applied = CalibrationState(invertedMicroamps, invertedMicroamps, CalibrationSource.DETECTED, 0, noticePending = true)
        assertState(applied, store)
        val writes = prefs.writes
        store.discharge(5) { -it }
        assertEquals("An unchanged decision is not rewritten", writes, prefs.writes)
        assertState(applied, backgroundScope.store())
    }

    @Test fun dismissKeepsTheCorrection() = runTest(UnconfinedTestDispatcher()) {
        val store = backgroundScope.store()
        store.positiveWhileDischarging()
        store.dismissNotice()
        val kept = CalibrationState(invertedMicroamps, invertedMicroamps, CalibrationSource.DETECTED, 0, noticePending = false)
        assertState(kept, store)
        assertState(kept, backgroundScope.store())
    }

    @Test fun undoRevertsAndTheSameEvidenceIsNotReapplied() = runTest(UnconfinedTestDispatcher()) {
        val store = backgroundScope.store()
        store.positiveWhileDischarging()
        store.undoLastCorrection()
        assertState(CalibrationState(), store)
        store.discharge(30) { -it }
        assertState(CalibrationState(), store)
        // The rejection is stored: a restarted process with fresh evidence keeps it rejected.
        val restarted = backgroundScope.store()
        restarted.discharge(25) { -it }
        assertState(CalibrationState(), restarted)
        restarted.undoLastCorrection()
        assertState(CalibrationState(), restarted)
    }

    @Test fun counterWindowsDetectTheUnitAndAnIdentityResultNeedsNoNotice() = runTest(UnconfinedTestDispatcher()) {
        val mA = backgroundScope.store()
        mA.threeWindows { it / 1000 }
        assertState(CalibrationState(milliamps, milliamps, CalibrationSource.DETECTED, 3, noticePending = true), mA)

        prefs.values.clear()
        val normal = backgroundScope.store()
        normal.threeWindows { it }
        assertState(CalibrationState(CurrentCalibration.IDENTITY, CurrentCalibration.IDENTITY, CalibrationSource.DETECTED, 3), normal)
    }

    @Test fun signOnlyFastPathKeepsAStoredWindowUnit() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.store().threeWindows { it / 1000 }
        // A new process has no windows yet; its fast path decides the sign alone.
        val restarted = backgroundScope.store()
        restarted.dismissNotice()
        restarted.positiveWhileDischarging()
        assertState(CalibrationState(invertedMilliamps, invertedMilliamps, CalibrationSource.DETECTED, 0, noticePending = true), restarted)
        restarted.undoLastCorrection()
        assertState(CalibrationState(milliamps, milliamps, CalibrationSource.DETECTED, 3), restarted)
    }

    @Test fun signOnlyFastPathKeepsStoredWindowEvidenceAfterRestart() = runTest(UnconfinedTestDispatcher()) {
        prefs.values["detected"] = "MILLIAMPS/INVERTED/4"
        val restarted = backgroundScope.store()
        val confirmed = CalibrationState(invertedMilliamps, invertedMilliamps, CalibrationSource.DETECTED, 4)
        assertState(confirmed, restarted)
        val writes = prefs.writes

        restarted.positiveWhileDischarging()

        assertEquals("Sign-only evidence must not erase confirmed windows", 4, restarted.state.value.agreeingWindows)
        assertState(confirmed, restarted)
        assertEquals("Weaker evidence is not persisted", writes, prefs.writes)
        assertState(confirmed, backgroundScope.store())
    }

    @Test fun counterWindowsStillUpdateStoredWindowEvidence() = runTest(UnconfinedTestDispatcher()) {
        prefs.values["detected"] = "MILLIAMPS/INVERTED/4"
        val restarted = backgroundScope.store()

        restarted.threeWindows { -it / 1000 }

        assertState(CalibrationState(invertedMilliamps, invertedMilliamps, CalibrationSource.DETECTED, 3), restarted)
        restarted.discharge(20) { -it / 1000 }
        val confirmed = CalibrationState(invertedMilliamps, invertedMilliamps, CalibrationSource.DETECTED, 4)
        assertState(confirmed, restarted)
        assertState(confirmed, backgroundScope.store())
    }

    @Test fun overridesWinAndOnlyVisibleCorrectionsRaiseTheNotice() = runTest(UnconfinedTestDispatcher()) {
        overrides.value = CalibrationOverrides(unit = CurrentUnit.MILLIAMPS)
        val store = backgroundScope.store()
        assertState(CalibrationState(milliamps, null, CalibrationSource.OVERRIDE), store)
        store.positiveWhileDischarging()
        assertState(CalibrationState(invertedMilliamps, invertedMicroamps, CalibrationSource.OVERRIDE, 0, noticePending = true), store)

        prefs.values.clear()
        overrides.value = CalibrationOverrides(CurrentUnit.MICROAMPS, CurrentSign.NORMAL)
        val pinned = backgroundScope.store()
        pinned.discharge(20) { -it }
        assertState(CalibrationState(CurrentCalibration.IDENTITY, invertedMicroamps, CalibrationSource.OVERRIDE), pinned)
        overrides.value = CalibrationOverrides()
        assertState(CalibrationState(invertedMicroamps, invertedMicroamps, CalibrationSource.DETECTED), pinned)
    }

    @Test fun resetForgetsEverythingIncludingRejections() = runTest(UnconfinedTestDispatcher()) {
        val store = backgroundScope.store()
        store.positiveWhileDischarging()
        store.undoLastCorrection()
        store.reset()
        assertState(CalibrationState(), store)
        assertTrue(prefs.values.isEmpty())
        store.discharge(19) { -it }
        assertState(CalibrationState(), store)
        store.discharge(1) { -it }
        assertState(CalibrationState(invertedMicroamps, invertedMicroamps, CalibrationSource.DETECTED, 0, noticePending = true), store)
    }

    @Test fun unreadableStoredValuesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        prefs.values += mapOf("detected" to "KILOAMPS/NORMAL/3", "previous" to "garbage", "rejected" to "", "notice" to "maybe")
        assertState(CalibrationState(), backgroundScope.store())
    }
}
