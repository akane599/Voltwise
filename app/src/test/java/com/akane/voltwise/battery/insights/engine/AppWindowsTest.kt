package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.engine.eligibility.EligibleAppWindow
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.insights.model.WindowBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWindowsTest {
    private val subject = Subject.App(UID, APP)

    @Test fun onlyClosedDischargeDeltaNotImportedHourWindowsAreEligible() {
        val valid = session(0)
        val invalid = listOf(
            valid.copy(kind = SessionKind.CHARGE), valid.copy(kind = SessionKind.PLUGGED),
            valid.copy(imported = true), valid.copy(appWindow = null),
            valid.copy(endMs = valid.startMs),
            valid.copy(appWindow = valid.appWindow!!.copy(basis = WindowBasis.ABSOLUTE)),
            valid.copy(appWindow = valid.appWindow!!.copy(basis = WindowBasis.WINDOW_RESET)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.startMs + HOUR - 1)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.endMs + HOUR)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureStartMs = valid.startMs + HOUR, captureEndMs = valid.endMs + HOUR)),
        )
        for (candidate in invalid) assertTrue(AppWindows.select(inputs(listOf(candidate), emptyList())).isEmpty())
        assertEquals(1, AppWindows.select(inputs(listOf(valid), emptyList())).size)
        assertTrue(AppWindows.select(inputs(listOf(valid), emptyList()).copy(nowMs = valid.endMs - 1)).isEmpty())
    }

    @Test fun durationToleranceIncludesBoundaryButNotMore() {
        val valid = session(0, 2 * HOUR)
        val allowed = valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.endMs - HOUR / 5))
        assertEquals(1, AppWindows.select(inputs(listOf(allowed), emptyList())).size)
        val rejected = allowed.copy(appWindow = allowed.appWindow!!.copy(captureEndMs = allowed.appWindow!!.captureEndMs - 1))
        assertTrue(AppWindows.select(inputs(listOf(rejected), emptyList())).isEmpty())
    }

    @Test fun everyTotalUsesCaptureHoursAndSharesUseCaptureDuration() {
        val session = session(0, 2 * HOUR)
        val row = highRow(session.id)
        val window = AppWindows.select(inputs(listOf(session), listOf(row))).single()
        val expected = mapOf(
            Metric.POWER_MAH_PER_H to 20.0, Metric.WAKEUP_ALARMS_PER_H to 50.0,
            Metric.CPU_MS_PER_H to 150_000.0, Metric.JOBS_PER_H to 50.0, Metric.SYNCS_PER_H to 50.0,
            Metric.GPS_MS_PER_H to 500_000.0, Metric.RADIO_ACTIVE_MS_PER_H to 500_000.0,
            Metric.BG_TIME_SHARE to (2_000_000.0 / (2 * HOUR)),
            Metric.PARTIAL_WAKELOCK_BG_SHARE to (1_000_000.0 / (2 * HOUR)),
            Metric.FGS_TO_FOREGROUND_RATIO to 15.0,
        )
        for ((metric, value) in expected) assertEquals(metric.name, value, AppWindows.value(window, row, metric)!!, 1e-9)
        for (metric in Metric.entries) assertNull(AppWindows.value(window, row.unsupported(), metric))
    }

    @Test fun sparseForegroundStaysUnknownForSystemAndNativeUids() {
        val row = row(session(0).id).copy(fgMs = null, topMs = 0)
        for (uid in listOf(1001, 1000, 0, 101001)) {
            assertNull("Missing foreground is unknown for UID $uid", AppWindows.foregroundMs(row.copy(uid = uid)))
            assertEquals(1_000.0, AppWindows.foregroundMs(row.copy(uid = uid, fgMs = 1_000))!!, 0.0)
        }
        assertEquals(0.0, AppWindows.foregroundMs(row.copy(uid = 10123)))
        assertEquals(0.0, AppWindows.foregroundMs(row.copy(uid = 110123)))
    }

    @Test fun zeroForegroundStaysUnknownForSystemAndNativeUids() {
        val row = row(session(0).id).copy(fgMs = 0, topMs = 0)
        assertEquals(0.0, AppWindows.foregroundMs(row.copy(uid = 10123)))
        for (uid in listOf(0, 1000, 1001, 101001)) {
            assertNull("Zero foreground is unknown for UID $uid", AppWindows.foregroundMs(row.copy(uid = uid)))
            assertNull(AppWindows.foregroundMs(row.copy(uid = uid, topMs = 1_000)))
            assertEquals(3_000.0, AppWindows.foregroundMs(row.copy(uid = uid, fgMs = 1_000, topMs = 2_000))!!, 0.0)
        }
    }

    @Test fun sparseForegroundIsZeroOnlyWithKnownNonnegativeTopTime() {
        val row = row(session(0).id).copy(fgMs = null, topMs = 0)
        assertEquals(0.0, AppWindows.foregroundMs(row))
        assertEquals(1_000.0, AppWindows.foregroundMs(row.copy(topMs = 1_000))!!, 0.0)
        assertNull(AppWindows.foregroundMs(row.copy(topMs = null)))
        assertNull(AppWindows.foregroundMs(row.copy(topMs = -1)))
        assertNull(AppWindows.foregroundMs(row.copy(fgMs = -1)))
        assertNull(AppWindows.foregroundMs(row.copy(fgMs = 1_000, topMs = null)))
        assertEquals(3_000.0, AppWindows.foregroundMs(row.copy(fgMs = 1_000, topMs = 2_000))!!, 0.0)
    }

    @Test fun absentAppWithZeroTailGpsHasExactZeroButPositiveTailRemainsCensored() {
        val window = truncatedWindow().let { window ->
            window.copy(rows = window.rows.map { if (it.isOthers) it.copy(gpsMs = 0) else it })
        }
        val point = AppWindows.point(window, subject, Metric.GPS_MS_PER_H)!!
        assertEquals(0.0, point.value)
        assertNull(point.upperBound)
        assertFalse(point.censored)
        assertFalse(point.present)

        val positiveTail = window.copy(rows = window.rows.map { if (it.isOthers) it.copy(gpsMs = 5_000) else it })
        val censored = AppWindows.point(positiveTail, subject, Metric.GPS_MS_PER_H)!!
        assertNull(censored.value)
        assertEquals(2_500.0, censored.upperBound!!, 0.0)
        assertTrue(censored.censored)
        assertFalse(censored.present)
    }

    @Test fun fullRowSetAbsenceCarriesMetricSpecificUpperBoundNotZero() {
        val session = session(0).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        val others = listOf(
            row(session.id).copy(uid = 2, packageName = "example.two", powerMah = 8.0, wakeupAlarms = 80),
            row(session.id).copy(uid = 3, packageName = "example.three", powerMah = 12.0, wakeupAlarms = 4),
            row(session.id).copy(uid = -1, packageName = "", isOthers = true, powerMah = 0.0, wakeupAlarms = 0),
        )
        val window = AppWindows.select(inputs(listOf(session), others)).single()
        val power = AppWindows.point(window, subject, Metric.POWER_MAH_PER_H)!!
        assertNull(power.value)
        assertEquals(8.0, power.upperBound!!, 0.0)
        assertTrue(power.censored)
        assertFalse(power.present)
        assertEquals(0.0, AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!.value!!, 0.0)
    }

    @Test fun powerCutoffUsesPowerLeadersNotAdditionalWakerRows() {
        val session = session(0, 2 * HOUR).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        val leaders = (0 until 30).map { rank ->
            row(session.id).copy(uid = 20_000 + rank, packageName = "example.leader$rank", rank = rank,
                powerMah = 37.0 - rank, wakeupAlarms = 80)
        }
        val waker = row(session.id).copy(uid = 30_000, packageName = "example.waker", rank = 30,
            powerMah = 0.5, wakeupAlarms = 4)
        val others = row(session.id).copy(uid = -1, packageName = "", rank = 31, isOthers = true,
            powerMah = 0.0, wakeupAlarms = 0)
        // Storage order is rank-based, not the caller's list order.
        val window = AppWindows.select(inputs(listOf(session), listOf(waker, others) + leaders.reversed())).single()
        for (powerMah in listOf(0.5, 0.0)) {
            val withWaker = window.copy(rows = window.rows.map { if (it.uid == waker.uid) it.copy(powerMah = powerMah) else it })
            val power = AppWindows.point(withWaker, subject, Metric.POWER_MAH_PER_H)!!
            assertNull(power.value)
            assertEquals(4.0, power.upperBound!!, 0.0)
            assertTrue(power.censored)
            assertFalse(power.present)
        }
        assertEquals(0.0, AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!.value!!, 0.0)
        val presentWaker = AppWindows.point(window, Subject.App(waker.uid, waker.packageName), Metric.POWER_MAH_PER_H)!!
        assertEquals(0.25, presentWaker.value!!, 0.0)
        assertTrue(presentWaker.present)
        assertFalse(presentWaker.censored)
        assertNull(AppWindows.point(window.copy(rows = listOf(waker, others)), subject, Metric.POWER_MAH_PER_H))
    }

    @Test fun absentAppWithTwoExtraWakersHasExactZeroAlarms() {
        val point = AppWindows.point(truncatedWindow(), subject, Metric.WAKEUP_ALARMS_PER_H)!!
        assertEquals(0.0, point.value)
        assertNull(point.upperBound)
        assertFalse(point.censored)
        assertFalse(point.present)
    }

    @Test fun absentAppJobsAreBoundedByOthersNotPowerLeaders() {
        val point = AppWindows.point(truncatedWindow(), subject, Metric.JOBS_PER_H)!!
        assertNull(point.value)
        assertEquals(40.0, point.upperBound!!, 0.0)
        assertTrue(point.censored)
        assertFalse(point.present)
    }

    @Test fun absentAppFgsRatioBoundUsesTailFgsTotalAndForegroundFloor() {
        val session = session(0, 8 * HOUR).let {
            it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true))
        }
        val rows = truncatedWindow().rows.map {
            if (it.isOthers) it.copy(fgServiceMs = HOUR, fgMs = 3 * HOUR, topMs = 0) else it
        }
        val window = AppWindows.select(inputs(listOf(session), rows)).single()
        val others = rows.single { it.isOthers }
        assertEquals(1.0 / 3.0, AppWindows.value(window, others, Metric.FGS_TO_FOREGROUND_RATIO)!!, 1e-9)
        val missingApp = row(session.id).copy(fgServiceMs = 40 * 60_000L, fgMs = 0, topMs = 0)
        val missingRatio = AppWindows.value(window, missingApp, Metric.FGS_TO_FOREGROUND_RATIO)!!
        assertEquals(5.0, missingRatio, 0.0)
        val point = AppWindows.point(window, subject, Metric.FGS_TO_FOREGROUND_RATIO)!!
        assertTrue("The censored bound must cover the missing app's ratio", point.upperBound!! >= missingRatio)
        assertEquals(7.5, point.upperBound!!, 0.0)
        assertNull(point.value)
        assertTrue(point.censored)
        assertFalse(point.present)
        // Only the tail's FGS total is needed; its foreground fields cannot tighten this bound.
        val noForeground = window.copy(rows = rows.map { if (it.isOthers) it.copy(fgMs = null, topMs = null) else it })
        assertEquals(7.5, AppWindows.point(noForeground, subject, Metric.FGS_TO_FOREGROUND_RATIO)!!.upperBound!!, 0.0)
    }

    @Test fun absentAppFgsRatioNeedsNonnegativeTailFgsTotal() {
        val window = truncatedWindow()
        for (fgs in listOf(null, -1L)) {
            val unsupported = window.copy(rows = window.rows.map { if (it.isOthers) it.copy(fgServiceMs = fgs) else it })
            assertNull(AppWindows.point(unsupported, subject, Metric.FGS_TO_FOREGROUND_RATIO))
        }
        val zero = window.copy(rows = window.rows.map { if (it.isOthers) it.copy(fgServiceMs = 0) else it })
        val point = AppWindows.point(zero, subject, Metric.FGS_TO_FOREGROUND_RATIO)!!
        assertEquals(0.0, point.value)
        assertNull(point.upperBound)
        assertFalse(point.censored)
        assertFalse(point.present)
    }

    @Test fun spareWakerSlotsAlsoMakeBackgroundWakelockAbsenceExactZero() {
        val point = AppWindows.point(truncatedWindow(), subject, Metric.PARTIAL_WAKELOCK_BG_SHARE)!!
        assertEquals(0.0, point.value)
        assertNull(point.upperBound)
        assertFalse(point.censored)
        assertFalse(point.present)
    }

    @Test fun unknownWakerTailCannotSupplyAnExactZeroOrBoundWithSpareOrFullSlots() {
        for (wakerCount in listOf(2, 10)) {
            val window = truncatedWindow(wakerCount).let { window ->
                window.copy(rows = window.rows.map {
                    if (it.isOthers) it.copy(wakeupAlarms = null, partialWakelockBgMs = null) else it
                })
            }
            assertNull("$wakerCount waker slots cannot bound an unknown alarm tail",
                AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H))
            assertNull("$wakerCount waker slots cannot bound an unknown background-wakelock tail",
                AppWindows.point(window, subject, Metric.PARTIAL_WAKELOCK_BG_SHARE))
        }
    }

    @Test fun fullWakerSlotsUseAlarmCutoffAndOthersWakelockShare() {
        val window = truncatedWindow(wakerCount = 10).let { window ->
            window.copy(rows = window.rows.map {
                if (it.isOthers) it.copy(wakeupAlarms = 8, partialWakelockBgMs = HOUR) else it
            })
        }
        val alarms = AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!
        assertEquals(5.5, alarms.upperBound!!, 0.0)
        assertNull(alarms.value)
        val wakelock = AppWindows.point(window, subject, Metric.PARTIAL_WAKELOCK_BG_SHARE)!!
        assertEquals(0.5, wakelock.upperBound!!, 0.0)
        assertNull(wakelock.value)
        assertFalse(wakelock.present)
    }

    @Test fun absentTruncatedMetricsWithoutStoredSupportOrOthersValueStayUnsupported() {
        val window = truncatedWindow()
        val legacy = window.copy(rows = window.rows.map {
            it.copy(wakeupAlarms = null, partialWakelockBgMs = null)
        })
        assertNull(AppWindows.point(legacy, subject, Metric.WAKEUP_ALARMS_PER_H))
        assertNull(AppWindows.point(legacy, subject, Metric.PARTIAL_WAKELOCK_BG_SHARE))
        val noJobs = window.copy(rows = window.rows.map { if (it.isOthers) it.copy(jobCount = null) else it })
        assertNull(AppWindows.point(noJobs, subject, Metric.JOBS_PER_H))
        val fullWakers = truncatedWindow(wakerCount = 10)
        val noWakelock = fullWakers.copy(rows = fullWakers.rows.map {
            if (it.isOthers) it.copy(partialWakelockBgMs = null) else it
        })
        assertNull(AppWindows.point(noWakelock, subject, Metric.PARTIAL_WAKELOCK_BG_SHARE))
    }

    private fun truncatedWindow(wakerCount: Int = 2): EligibleAppWindow {
        val session = session(0, 2 * HOUR).let {
            it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true))
        }
        val leaders = (0 until 30).map { rank ->
            row(session.id).copy(uid = 20_000 + rank, packageName = "example.leader$rank", rank = rank,
                powerMah = 60.0 - rank, wakeupAlarms = 0, jobCount = 0)
        }
        val wakers = (0 until wakerCount).map { index ->
            row(session.id).copy(uid = 30_000 + index, packageName = "example.waker$index", rank = 30 + index,
                powerMah = 0.5, wakeupAlarms = (20 - index).toLong())
        }
        val others = row(session.id).copy(uid = -1, packageName = "", rank = 30 + wakerCount, isOthers = true,
            powerMah = 1.0, wakeupAlarms = 0, partialWakelockBgMs = 0, jobCount = 80)
        return AppWindows.select(inputs(listOf(session), leaders + wakers + others)).single()
    }

    @Test fun nonFullAbsenceIsZeroButPresentNullAndUnsupportedSessionAreExcluded() {
        val session = session(0)
        val absent = row(session.id).copy(uid = 2, packageName = "example.other")
        val window = AppWindows.select(inputs(listOf(session), listOf(absent))).single()
        val point = AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!
        assertEquals(0.0, point.value!!, 0.0)
        assertFalse(point.censored)
        assertNull(AppWindows.point(window.copy(rows = listOf(absent.copy(wakeupAlarms = null))), subject, Metric.WAKEUP_ALARMS_PER_H))
        assertNull(AppWindows.point(window.copy(rows = listOf(row(session.id).copy(wakeupAlarms = null))), subject, Metric.WAKEUP_ALARMS_PER_H))
        assertNull(AppWindows.point(window.copy(rows = emptyList()), subject, Metric.WAKEUP_ALARMS_PER_H))
    }

    @Test fun changedUidDoesNotInheritAnotherInstallationsBaseline() {
        val session = session(0)
        val window = AppWindows.select(inputs(listOf(session), listOf(row(session.id).copy(uid = UID + 1)))).single()
        assertFalse(AppWindows.point(window, subject, Metric.POWER_MAH_PER_H)!!.present)
    }

    @Test fun screenOffOnlyDrainUsesMeasuredEnergyEvenWithNoLevelDrop() {
        for (coverage in listOf(0L, null)) {
            for (endLevel in listOf(79, 80)) {
                val session = session(0, 2 * HOUR).copy(
                    screenOnUah = null, screenOnCoveredMs = coverage,
                    screenOffUah = 150_000, startLevel = 80, endLevel = endLevel,
                )
                val window = AppWindows.select(inputs(listOf(session), emptyList())).single()
                assertEquals(150.0, AppWindows.drainMah(window, 4_000_000)!!, 0.0)
            }
        }
    }

    @Test fun screenOnOnlyDrainUsesMeasuredEnergy() {
        for (coverage in listOf(0L, null)) {
            val session = session(0, 2 * HOUR).copy(
                screenOnMs = 2 * HOUR, screenOnCoveredMs = 2 * HOUR, screenOnUah = 150_000,
                screenOffMs = 0, screenOffCoveredMs = coverage, screenOffUah = null,
                startLevel = 80, endLevel = 79,
            )
            val window = AppWindows.select(inputs(listOf(session), emptyList())).single()
            assertEquals(150.0, AppWindows.drainMah(window, 4_000_000)!!, 0.0)
        }
    }

    @Test fun occurredScreenStateWithMissingEnergyStillUsesLevelFallback() {
        val session = session(0, 2 * HOUR).copy(
            screenOnMs = HOUR, screenOnCoveredMs = HOUR,
            screenOffMs = HOUR, screenOffCoveredMs = HOUR,
            screenOnUah = 150_000, screenOffUah = 150_000,
            startLevel = 80, endLevel = 79,
        )
        for (missingOn in listOf(true, false)) {
            val incomplete = if (missingOn) session.copy(screenOnUah = null) else session.copy(screenOffUah = null)
            val window = AppWindows.select(inputs(listOf(incomplete), emptyList())).single()
            assertEquals(40.0, AppWindows.drainMah(window, 4_000_000)!!, 0.0)
            assertNull(AppWindows.drainMah(window, null))
        }
    }

    @Test fun totalDrainRequiresCoveredEnergyOrCapacityAndLevelDrop() {
        val session = session(0)
        val window = AppWindows.select(inputs(listOf(session), listOf(highRow(session.id)))).single()
        assertEquals(200.0, AppWindows.drainMah(window, null)!!, 0.0)
        val incomplete = window.copy(session = session.copy(screenOffCoveredMs = 0))
        assertNull(AppWindows.drainMah(incomplete, null))
        assertEquals(200.0, AppWindows.drainMah(incomplete, 4_000_000)!!, 0.0)
        assertNull(AppWindows.drainMah(incomplete.copy(session = incomplete.session.copy(endLevel = 90)), 4_000_000))
    }
}
