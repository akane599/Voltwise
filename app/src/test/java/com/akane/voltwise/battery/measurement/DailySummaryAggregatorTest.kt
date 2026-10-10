package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.sampling.DailySummaryReplay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class DailySummaryAggregatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(time: String) = OffsetDateTime.parse(time).toInstant().toEpochMilli()
    private fun day(date: String) = LocalDate.parse(date).toEpochDay()
    private fun apply(interval: DayInterval, rows: Map<Long, DailySummary> = emptyMap()) =
        DailySummaryAggregator.apply(rows, interval, berlin, updatedAt = 42).associateBy { it.epochDay }

    @Test fun partialCoverageAcrossDaysPreservesTotalsAndNeverExceedsDuration() {
        val rows = apply(DayInterval(at("2026-06-10T00:00+02:00"), at("2026-06-13T00:00+02:00"),
            screenOnMs = 3, screenOnCoveredMs = 2, screenOnDischargeUah = 0)).values.toList()
        assertEquals(2L, rows.sumOf { it.screenOnCoveredMs!! })
        assertTrue(rows.all { it.screenOnCoveredMs!! in 0..it.screenOnMs })
        assertEquals(0L, rows.last().screenOnCoveredMs)
    }

    @Test fun knownCoverageAccumulatesButLegacyCoverageStaysUnknown() {
        val d = day("2026-06-10")
        val interval = DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:02+02:00"),
            screenOnMs = 120_000, screenOnCoveredMs = 60_000, screenOnDischargeUah = 1000)
        val first = apply(interval).getValue(d)
        assertEquals(60_000L, first.screenOnCoveredMs)
        assertEquals(120_000L, apply(interval, mapOf(d to first)).getValue(d).screenOnCoveredMs)
        val legacy = first.copy(screenOnCoveredMs = null)
        assertNull(apply(interval, mapOf(d to legacy)).getValue(d).screenOnCoveredMs)
    }

    @Test fun intervalWithinOneDayAddsToThatDay() {
        val june10 = day("2026-06-10")
        val rows = apply(DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:30+02:00"),
            screenOnMs = 1_800_000, screenOnDischargeUah = 90_000, cpuSuspendMs = 0,
            endLevelPercent = 64, endTemperatureDeciC = 312))
        val expected = DailySummary(june10, screenOnMs = 1_800_000, screenOnDischargeUah = 90_000, cpuSuspendMs = 0,
            minLevel = 64, maxLevel = 64, peakTemperatureDeciC = 312, updatedAt = 42,
            screenOnCoveredMs = 0, screenOffCoveredMs = 0)
        assertEquals(mapOf(june10 to expected), rows)
        assertEquals(june10, DailySummaryAggregator.epochDay(at("2026-06-10T23:59:59+02:00"), berlin))
        assertEquals(june10 + 1, DailySummaryAggregator.epochDay(at("2026-06-10T22:00:00Z"), berlin))
    }

    @Test fun existingRowIsAccumulated() {
        val june10 = day("2026-06-10")
        val existing = DailySummary(june10, screenOffMs = 1_000, screenOffDischargeUah = 50, chargedUah = 7,
            cpuSuspendMs = 400, minLevel = 60, maxLevel = 70, peakTemperatureDeciC = 330, updatedAt = 1)
        val rows = apply(DayInterval(at("2026-06-10T10:00+02:00"), at("2026-06-10T10:00:02+02:00"),
            screenOffMs = 2_000, screenOffDischargeUah = 100, cpuSuspendMs = 600,
            endLevelPercent = 55, endTemperatureDeciC = 300), mapOf(june10 to existing))
        assertEquals(existing.copy(screenOffMs = 3_000, screenOffDischargeUah = 150, cpuSuspendMs = 1_000,
            minLevel = 55, updatedAt = 42), rows[june10])
    }

    @Test fun intervalAcrossMidnightIsSplitByWallTime() {
        val rows = apply(DayInterval(at("2026-06-10T23:45+02:00"), at("2026-06-11T00:15+02:00"),
            screenOffMs = 1_800_000, screenOffDischargeUah = 9_001, cpuSuspendMs = 1_500_000, endLevelPercent = 50))
        val first = rows.getValue(day("2026-06-10"))
        val second = rows.getValue(day("2026-06-11"))
        assertEquals(900_000L, first.screenOffMs)
        assertEquals(4_500L, first.screenOffDischargeUah)
        assertEquals(750_000L, first.cpuSuspendMs)
        assertNull(first.minLevel) // the reading belongs to the interval's end
        assertEquals(900_000L, second.screenOffMs)
        assertEquals(4_501L, second.screenOffDischargeUah) // totals are preserved
        assertEquals(750_000L, second.cpuSuspendMs)
        assertEquals(50, second.minLevel)
    }

    @Test fun springForwardDayHasTwentyThreeHours() {
        // Berlin 2026-03-29: 02:00 → 03:00. 23:00 (+01) → 03:30 (+02) is 3.5 real hours, 1 of them on the 28th.
        val rows = apply(DayInterval(at("2026-03-28T23:00+01:00"), at("2026-03-29T03:30+02:00"),
            screenOffMs = 12_600_000, screenOffDischargeUah = 35_000))
        assertEquals(3_600_000L, rows.getValue(day("2026-03-28")).screenOffMs)
        assertEquals(10_000L, rows.getValue(day("2026-03-28")).screenOffDischargeUah)
        assertEquals(9_000_000L, rows.getValue(day("2026-03-29")).screenOffMs)
        assertEquals(25_000L, rows.getValue(day("2026-03-29")).screenOffDischargeUah)

        // The local day is 23 h long: 24 h from its midnight spill one hour into the 30th.
        val whole = apply(DayInterval(at("2026-03-29T00:00+01:00"), at("2026-03-30T01:00+02:00"), screenOffMs = 86_400_000))
        assertEquals(82_800_000L, whole.getValue(day("2026-03-29")).screenOffMs)
        assertEquals(3_600_000L, whole.getValue(day("2026-03-30")).screenOffMs)
    }

    @Test fun fallBackDayHasTwentyFiveHours() {
        // Berlin 2026-10-25: 03:00 → 02:00. 23:00 (+02) → 02:30 (+01) is 4.5 real hours, 1 of them on the 24th.
        val rows = apply(DayInterval(at("2026-10-24T23:00+02:00"), at("2026-10-25T02:30+01:00"),
            screenOnMs = 16_200_000, chargedUah = 45_000))
        assertEquals(3_600_000L, rows.getValue(day("2026-10-24")).screenOnMs)
        assertEquals(10_000L, rows.getValue(day("2026-10-24")).chargedUah)
        assertEquals(12_600_000L, rows.getValue(day("2026-10-25")).screenOnMs)
        assertEquals(35_000L, rows.getValue(day("2026-10-25")).chargedUah)

        // The local day is 25 h long and all of it stays on the 25th.
        val whole = apply(DayInterval(at("2026-10-25T00:00+02:00"), at("2026-10-26T00:00+01:00"), screenOffMs = 90_000_000))
        assertEquals(90_000_000L, whole.getValue(day("2026-10-25")).screenOffMs)
        assertEquals(DailySummary(day("2026-10-26"), updatedAt = 42, screenOnCoveredMs = 0, screenOffCoveredMs = 0), whole[day("2026-10-26")])
    }

    @Test fun readingsTrackMinMaxLevelAndPeakTemperature() {
        var rows = emptyMap<Long, DailySummary>()
        var time = at("2026-06-10T08:00+02:00")
        for ((level, temperature) in listOf(80 to 300, 75 to 350, 90 to 320, null to null)) {
            rows = rows + apply(DayInterval(time, time, endLevelPercent = level, endTemperatureDeciC = temperature), rows)
            time += 60_000
        }
        val june10 = rows.getValue(day("2026-06-10"))
        assertEquals(75, june10.minLevel)
        assertEquals(90, june10.maxLevel)
        assertEquals(350, june10.peakTemperatureDeciC)
        assertNull(june10.cpuSuspendMs) // never discharging: not measured
    }

    @Test fun intervalWithNothingToAddTouchesOnlyTheEndDay() {
        val rows = apply(DayInterval(at("2026-06-10T22:00+02:00"), at("2026-06-11T02:00+02:00"), endLevelPercent = 40))
        assertEquals(setOf(day("2026-06-11")), rows.keys)
        assertEquals(40, rows.getValue(day("2026-06-11")).minLevel)
    }

    @Test fun dozeAndScreenOffSuspendUseCumulativeDifferencesAndAccumulate() {
        val start = at("2026-06-10T23:59+02:00")
        fun obs(t: Long, screen: Boolean, boundary: Boundary = Boundary.SAMPLE) =
            Observation(start + t, t, t / 2, 70, null, null, null, PowerState.DISCHARGING,
                screen, true, "one", boundary = boundary)
        val engine = ObservationEngine()
        engine.accept(obs(0, true))
        val before = engine.accept(obs(60_000, false, Boundary.SCREEN))
        val after = engine.accept(obs(120_000, false))
        val interval = DailySummaryAggregator.interval(before, after, null)!!
        assertEquals(60_000L, interval.dozeMs)
        assertEquals(60_000L, interval.screenOffDozeMs)
        assertEquals(30_000L, interval.screenOffSuspendMs)
        val first = apply(interval).getValue(day("2026-06-11"))
        val second = apply(interval, mapOf(first.epochDay to first)).getValue(first.epochDay)
        assertEquals(120_000L, second.dozeMs)
        assertEquals(120_000L, second.screenOffDozeMs)
        assertEquals(60_000L, second.screenOffSuspendMs)
    }

    @Test fun pluggedScreenOffIntervalDoesNotInflateDailyOnBatteryCountersAcrossMidnight() {
        val start = at("2026-06-10T23:59+02:00")
        val engine = ObservationEngine()
        val before = engine.accept(Observation(start, 0, 0, 70, null, null, null,
            PowerState.PLUGGED, false, true, "one"))
        val after = engine.accept(before.latest!!.copy(wallMs = start + 120_000,
            elapsedMs = 120_000, uptimeMs = 40_000))
        val interval = DailySummaryAggregator.interval(before, after, null)!!
        assertEquals(0L, interval.screenOffMs)
        assertEquals(120_000L, interval.dozeMs)
        assertNull(interval.cpuSuspendMs)
        assertEquals(0L, interval.screenOffDozeMs)
        assertEquals(0L, interval.screenOffSuspendMs)
        val firstDay = day("2026-06-10")
        val historical = DailySummary(firstDay, screenOffMs = 10_000,
            screenOffDozeMs = 20_000, screenOffSuspendMs = 15_000)
        val rows = apply(interval, mapOf(firstDay to historical))
        val first = rows.getValue(firstDay)
        val second = rows.getValue(day("2026-06-11"))
        assertEquals(60_000L, first.dozeMs)
        assertEquals(historical.screenOffMs, first.screenOffMs)
        assertEquals(historical.screenOffDozeMs, first.screenOffDozeMs)
        assertEquals(historical.screenOffSuspendMs, first.screenOffSuspendMs)
        assertEquals(60_000L, second.dozeMs)
        assertEquals(0L, second.screenOffMs)
        assertEquals(0L, second.screenOffDozeMs)
        assertEquals(0L, second.screenOffSuspendMs)
    }

    @Test fun forwardWallClockJumpTouchesOnlyRealDays() {
        val start = at("2026-06-10T12:00+02:00")
        val end = start + 30 * 86_400_000L
        val engine = ObservationEngine()
        val before = engine.accept(Observation(start, 1_000, 1_000, 70, null, null, null,
            PowerState.DISCHARGING, true, false, "one"))
        val observed = engine.accept(before.latest!!.copy(wallMs = start + 20_000,
            elapsedMs = 21_000, uptimeMs = 21_000))
        val after = engine.accept(observed.latest!!.copy(wallMs = end,
            elapsedMs = 26_000, uptimeMs = 26_000))
        assertEquals(1, after.gaps)
        assertEquals("Wall clock changed; new interval baseline", after.lastIssue)
        assertEquals(20_000L, after.screenOn.durationMs)

        val interval = DailySummaryAggregator.interval(before, after, null)!!
        val rows = apply(interval)
        val realDays = setOf(DailySummaryAggregator.epochDay(start, berlin),
            DailySummaryAggregator.epochDay(end, berlin))
        assertTrue("A clock jump must not create rows for skipped days: ${rows.keys}",
            rows.keys.all { it in realDays })
        assertEquals(20_000L, rows.values.sumOf { it.screenOnMs })
        assertTrue(DailySummaryAggregator.days(interval, berlin).all { it in realDays })
    }

    @Test fun normalMultiDayElapsedIntervalStillTouchesEveryObservedDay() {
        val start = at("2026-06-10T12:00+02:00")
        val duration = 3 * 86_400_000L
        val engine = ObservationEngine()
        val before = engine.accept(Observation(start, 1_000, 1_000, 70, null, null, null,
            PowerState.DISCHARGING, true, false, "one", expectedIntervalMs = duration))
        val after = engine.accept(before.latest!!.copy(wallMs = start + duration,
            elapsedMs = 1_000 + duration, uptimeMs = 1_000 + duration))
        assertEquals(0, after.gaps)
        val interval = DailySummaryAggregator.interval(before, after, null)!!
        assertEquals(start, interval.startWallMs)
        assertEquals(day("2026-06-10")..day("2026-06-13"), DailySummaryAggregator.days(interval, berlin))
        val rows = apply(interval)
        assertEquals((day("2026-06-10")..day("2026-06-13")).toSet(), rows.keys)
        assertEquals(listOf(43_200_000L, 86_400_000L, 86_400_000L, 43_200_000L),
            rows.values.map { it.screenOnMs })
        assertEquals(duration, rows.values.sumOf { it.screenOnMs })
    }

    @Test fun missingBaselineOrNegativeElapsedTouchesOnlyEndDay() {
        val end = at("2026-07-10T12:00+02:00")
        val point = Observation(end, 1_000, 1_000, 70, null, null, null,
            PowerState.DISCHARGING, true, false, "one")
        val after = ObservationSummary(latest = point, screenOn = ObservedBucket(durationMs = 20_000))
        val before = ObservationSummary(latest = point.copy(wallMs = end - 30 * 86_400_000L,
            elapsedMs = 2_000, uptimeMs = 2_000))
        for (baseline in listOf(ObservationSummary(), before)) {
            val interval = DailySummaryAggregator.interval(baseline, after, null)!!
            assertEquals(end, interval.startWallMs)
            val row = apply(interval).values.single()
            assertEquals(day("2026-07-10"), row.epochDay)
            assertEquals(20_000L, row.screenOnMs)
        }
        assertNull(DailySummaryAggregator.interval(before, ObservationSummary(), null))
    }

    @Test fun acceptedNonincreasingWallClockKeepsMeasuredTotalsAndLaterAccumulation() {
        val start = at("2026-06-10T00:00:00.500+02:00")
        for ((wallDelta, endDay) in listOf(-1_000L to day("2026-06-09"), 0L to day("2026-06-10"))) {
            val engine = ObservationEngine()
            val before = engine.accept(Observation(start, 0, 0, 70, null, null, null,
                PowerState.DISCHARGING, false, true, "one"))
            val after = engine.accept(before.latest!!.copy(wallMs = start + wallDelta,
                elapsedMs = 2_000, uptimeMs = 1_000, dozing = false, boundary = Boundary.DOZE))
            val lastState = PersistPolicy.State(0, 3, 0, 70, "one")
            assertEquals(PersistReason.DOZE, PersistPolicy.decide(lastState,
                lastState.copy(elapsedMs = 2_000), Boundary.DOZE, screenOn = false, poll = false))
            assertEquals(0, after.gaps)
            val interval = DailySummaryAggregator.interval(before, after, null)!!
            assertEquals(2_000L, interval.screenOffMs)
            assertEquals(2_000L, interval.dozeMs)
            assertEquals(2_000L, interval.screenOffDozeMs)
            assertEquals(1_000L, interval.screenOffSuspendMs)
            assertEquals(endDay..endDay, DailySummaryAggregator.days(interval, berlin))

            val first = apply(interval).values.single()
            assertEquals(endDay, first.epochDay)
            assertEquals(2_000L, first.screenOffMs)
            assertEquals(1_000L, first.cpuSuspendMs)
            assertEquals(2_000L, first.dozeMs)
            assertEquals(2_000L, first.screenOffDozeMs)
            assertEquals(1_000L, first.screenOffSuspendMs)

            val later = engine.accept(after.latest!!.copy(wallMs = start + wallDelta + 2_000,
                elapsedMs = 4_000, uptimeMs = 2_000, boundary = Boundary.SAMPLE))
            val next = apply(DailySummaryAggregator.interval(after, later, null)!!,
                mapOf(endDay to first)).values
            assertEquals(4_000L, next.sumOf { it.screenOffMs })
            assertEquals(2_000L, next.sumOf { it.dozeMs!! })
            assertEquals(2_000L, next.sumOf { it.screenOffDozeMs!! })
            assertEquals(2_000L, next.sumOf { it.screenOffSuspendMs!! })
        }
    }

    @Test fun acceptedBackwardWallClockAccumulatesKnownTotalsAndPreservesUnknownHistory() {
        val start = at("2026-06-10T12:00+02:00")
        val d = day("2026-06-10")
        val engine = ObservationEngine()
        val before = engine.accept(Observation(start, 0, 0, 70, null, null, null,
            PowerState.DISCHARGING, false, true, "one"))
        val after = engine.accept(before.latest!!.copy(wallMs = start - 1_000,
            elapsedMs = 2_000, uptimeMs = 1_000, dozing = false, boundary = Boundary.DOZE))
        val interval = DailySummaryAggregator.interval(before, after, null)!!
        val known = DailySummary(d, screenOffMs = 10_000, dozeMs = 4_000,
            screenOffDozeMs = 4_000, screenOffSuspendMs = 3_000)
        val measured = apply(interval, mapOf(d to known)).getValue(d)
        assertEquals(12_000L, measured.screenOffMs)
        assertEquals(6_000L, measured.dozeMs)
        assertEquals(6_000L, measured.screenOffDozeMs)
        assertEquals(4_000L, measured.screenOffSuspendMs)

        val unknown = known.copy(screenOffDozeMs = null, screenOffSuspendMs = null)
        val retained = apply(interval, mapOf(d to unknown)).getValue(d)
        assertEquals(12_000L, retained.screenOffMs)
        assertNull(retained.screenOffDozeMs)
        assertNull(retained.screenOffSuspendMs)
    }

    @Test fun acceptedCollapsedPluggedIntervalPreservesMeasuredScreenOffZeros() {
        val start = at("2026-06-10T12:00+02:00")
        val engine = ObservationEngine()
        val before = engine.accept(Observation(start, 0, 0, 70, null, null, null,
            PowerState.PLUGGED, false, true, "one"))
        val after = engine.accept(before.latest!!.copy(wallMs = start - 1_000,
            elapsedMs = 2_000, uptimeMs = 1_000, dozing = false, boundary = Boundary.DOZE))
        val measured = apply(DailySummaryAggregator.interval(before, after, null)!!).values.single()
        assertEquals(0L, measured.screenOffMs)
        assertNull(measured.cpuSuspendMs)
        assertEquals(2_000L, measured.dozeMs)
        assertEquals(0L, measured.screenOffDozeMs)
        assertEquals(0L, measured.screenOffSuspendMs)
    }

    @Test fun midnightAndDstSplitNewMetricsWithoutMeasuringEndpointOnlyDays() {
        for ((start, end, shares) in listOf(
            Triple("2026-06-10T23:45+02:00", "2026-06-11T00:15+02:00", listOf(900_000L, 900_000L)),
            Triple("2026-03-29T00:00+01:00", "2026-03-30T01:00+02:00", listOf(82_800_000L, 3_600_000L)),
            Triple("2026-10-25T00:00+02:00", "2026-10-26T00:00+01:00", listOf(90_000_000L, 0L)),
        )) {
            val total = shares.sum()
            val rows = apply(DayInterval(at(start), at(end), dozeMs = total,
                screenOffDozeMs = total / 2, screenOffSuspendMs = total / 4)).values.toList()
            assertEquals(shares.map { it.takeIf { value -> value > 0 } }, rows.map { it.dozeMs })
            assertEquals(shares.map { (it / 2).takeIf { _ -> it > 0 } }, rows.map { it.screenOffDozeMs })
            assertEquals(shares.map { (it / 4).takeIf { _ -> it > 0 } }, rows.map { it.screenOffSuspendMs })
        }
    }

    @Test fun roundingRemainderStaysOnObservedDaysNotMidnightEndpoint() {
        val rows = apply(DayInterval(at("2026-06-10T00:00+02:00"), at("2026-06-13T00:00+02:00"),
            dozeMs = 2, screenOffDozeMs = 1, screenOffSuspendMs = 1)).values.toList()
        assertEquals(listOf(0L, 0L, 2L, null), rows.map { it.dozeMs })
        assertEquals(listOf(0L, 0L, 1L, null), rows.map { it.screenOffDozeMs })
        assertEquals(listOf(0L, 0L, 1L, null), rows.map { it.screenOffSuspendMs })
    }

    @Test fun noObservedIntervalKeepsUnknownButObservedZeroIsMeasured() {
        val start = at("2026-06-10T23:59+02:00")
        val engine = ObservationEngine()
        val initial = engine.accept(Observation(start, 0, 0, 70, null, null, null,
            PowerState.PLUGGED, false, false, "one"))
        val baseline = apply(DailySummaryAggregator.interval(ObservationSummary(), initial, null)!!).values.single()
        assertNull(baseline.dozeMs)
        assertNull(baseline.screenOffDozeMs)
        assertNull(baseline.screenOffSuspendMs)
        val observed = engine.accept(initial.latest!!.copy(wallMs = start + 120_000, elapsedMs = 120_000, uptimeMs = 120_000))
        val rows = apply(DailySummaryAggregator.interval(initial, observed, null)!!).values
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.dozeMs == 0L && it.screenOffDozeMs == 0L && it.screenOffSuspendMs == 0L })
        val gap = engine.accept(observed.latest!!.copy(wallMs = start + 180_000, elapsedMs = 180_000,
            uptimeMs = 180_000, boundary = Boundary.GAP))
        val empty = DailySummaryAggregator.interval(observed, gap, null)!!
        assertNull(empty.dozeMs)
        assertNull(empty.screenOffDozeMs)
        assertNull(empty.screenOffSuspendMs)
        val retained = apply(empty, rows.associateBy { it.epochDay }).values.single()
        assertEquals(0L, retained.dozeMs)
        assertEquals(0L, retained.screenOffDozeMs)
        assertEquals(0L, retained.screenOffSuspendMs)
    }

    @Test fun legacyScreenOffNumeratorsStayUnknownAfterMeasuredPluggedZeroAndDischarge() {
        val start = at("2026-06-10T12:00+02:00")
        val d = day("2026-06-10")
        val legacy = DailySummary(d, screenOffMs = 28_800_000)
        val plugged = DayInterval(start, start + 60_000,
            dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 0)
        val afterPlugged = apply(plugged, mapOf(d to legacy)).getValue(d)
        assertNull("A measured plugged zero cannot recover eight hours of unknown Doze", afterPlugged.screenOffDozeMs)
        assertNull("A measured plugged zero cannot recover eight hours of unknown suspend", afterPlugged.screenOffSuspendMs)
        val discharge = plugged.copy(screenOffMs = 60_000, screenOffDozeMs = 30_000, screenOffSuspendMs = 45_000)
        val afterDischarge = apply(discharge, mapOf(d to afterPlugged)).getValue(d)
        assertNull(afterDischarge.screenOffDozeMs)
        assertNull(afterDischarge.screenOffSuspendMs)
        assertEquals(28_860_000L, afterDischarge.screenOffMs)
    }

    @Test fun replayedEightHourScreenOffRowKeepsUnknownNumeratorsAfterPluggedZero() {
        val start = at("2026-06-10T00:00+02:00")
        val replay = DailySummaryReplay(berlin, updatedAt = 1)
        for (minute in 0..480 step 5) {
            val offset = minute * 60_000L
            replay.add(BatterySample(timestamp = start + offset, status = 3, plugged = 0,
                levelPercent = 80, currentNowUa = null, chargeCounterUah = null, voltageMv = null,
                temperatureDeciC = null, health = null,
                screenOn = false, observationId = "run", elapsedMs = offset, uptimeMs = offset,
                source = DailySummaryReplay.SAMPLE_SOURCE))
        }
        val historical = replay.result.single()
        assertEquals(28_800_000L, historical.screenOffMs)
        val measured = apply(DayInterval(start + 12 * 3_600_000, start + 12 * 3_600_000 + 60_000,
            dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 0),
            mapOf(historical.epochDay to historical)).values.single()
        assertNull(measured.screenOffDozeMs)
        assertNull(measured.screenOffSuspendMs)
    }

    @Test fun zeroScreenOffHistoryInitializesMeasurementsAndKnownZeroAccumulates() {
        val start = at("2026-06-10T12:00+02:00")
        val d = day("2026-06-10")
        val plugged = DayInterval(start, start + 60_000,
            dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 0)
        for (existing in listOf(emptyMap(), mapOf(d to DailySummary(d, screenOnMs = 28_800_000)))) {
            val zero = apply(plugged, existing).getValue(d)
            assertEquals(0L, zero.screenOffDozeMs)
            assertEquals(0L, zero.screenOffSuspendMs)
            val measured = apply(plugged.copy(screenOffMs = 60_000, screenOffDozeMs = 30_000,
                screenOffSuspendMs = 45_000), mapOf(d to zero)).getValue(d)
            assertEquals(30_000L, measured.screenOffDozeMs)
            assertEquals(45_000L, measured.screenOffSuspendMs)
        }
        val mixed = DailySummary(d, screenOffMs = 28_800_000, screenOffDozeMs = 0)
        val updated = apply(plugged, mapOf(d to mixed)).getValue(d)
        assertEquals(0L, updated.screenOffDozeMs)
        assertNull(updated.screenOffSuspendMs)
    }

    @Test fun intervalIsTheDifferenceOfTwoEngineSummaries() {
        val start = at("2026-06-10T12:00+02:00")
        fun obs(t: Long, charge: Long, power: PowerState, uptime: Long = t, boundary: Boundary = Boundary.SAMPLE) =
            Observation(start + t, t, uptime, 70, charge, -1, 4000, power, false, false, "one", boundary = boundary)
        val engine = ObservationEngine()
        val a = engine.accept(obs(0, 4_000_000, PowerState.DISCHARGING))
        assertEquals(DayInterval(start, start, endLevelPercent = 70),
            DailySummaryAggregator.interval(ObservationSummary(), a, endTemperatureDeciC = null))
        val b = engine.accept(obs(60_000, 3_999_000, PowerState.DISCHARGING, uptime = 20_000))
        assertEquals(DayInterval(start, start + 60_000, screenOffMs = 60_000, screenOffDischargeUah = 1_000,
            cpuSuspendMs = 40_000, dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 40_000, endLevelPercent = 70, endTemperatureDeciC = 301, screenOffCoveredMs = 60_000),
            DailySummaryAggregator.interval(a, b, endTemperatureDeciC = 301))
        val c = engine.accept(obs(120_000, 3_999_000, PowerState.CHARGING, uptime = 80_000, boundary = Boundary.POWER))
        val d = engine.accept(obs(180_000, 4_009_000, PowerState.CHARGING, uptime = 140_000))
        assertEquals(DayInterval(start + 120_000, start + 180_000, chargedUah = 10_000, dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 0, endLevelPercent = 70),
            DailySummaryAggregator.interval(c, d, endTemperatureDeciC = null))
    }
}
