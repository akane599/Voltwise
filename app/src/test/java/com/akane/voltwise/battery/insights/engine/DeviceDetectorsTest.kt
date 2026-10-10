package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.DeviceDetectors
import com.akane.voltwise.battery.insights.model.AttributionKind
import com.akane.voltwise.battery.insights.model.DeviceWakerInput
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.WakerKind
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DeviceDetectorsTest {
    @Test fun `doze blocked needs two hours both low shares and four baseline sessions`() {
        val input = dozeInput()
        val result = DeviceDetectors.detect(input).single()
        assertEquals(FindingType.DOZE_BLOCKED, result.type)
        assertEquals(0.05, result.evidence.first().observed, 0.0001)
        assertEquals(0.8, result.evidence.first().baseline!!, 0.0001)
        assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.drop(1))).isEmpty())
        for (replacement in listOf(
            input.sessions.last().copy(screenOffDozeMs = null),
            input.sessions.last().copy(screenOffSuspendMs = null),
            input.sessions.last().copy(screenOffMs = HOUR),
            input.sessions.last().copy(screenOffDozeMs = HOUR * 8 / 10),
            input.sessions.last().copy(screenOffSuspendMs = HOUR * 18 / 10),
        )) assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.dropLast(1) + replacement)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = input.sessions.map {
            it.copy(screenOffDozeMs = HOUR / 10, screenOffSuspendMs = HOUR / 10)
        })).isEmpty())
    }

    @Test fun `screen off drain uses covered time rather than wall time and requires capacity`() {
        val sessions = (0..4).map { session(it).copy(screenOffUah = if (it < 4) 40_000 else 160_000) }
        val input = inputs(sessions, emptyList())
        val finding = DeviceDetectors.detect(input).single()
        assertEquals(FindingType.SCREEN_OFF_DRAIN_HIGH, finding.type)
        assertEquals(4.0, finding.evidence.single().observed, 0.0)
        assertTrue(DeviceDetectors.detect(input.copy(fullUah = null)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(screenOffCoveredMs = null) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(screenOffUah = 40_000) })).isEmpty())
        val partial = input.copy(sessions = sessions.dropLast(1) + sessions.last().copy(screenOffCoveredMs = HOUR / 2))
        assertEquals(8.0, DeviceDetectors.detect(partial).single().evidence.single().observed, 0.0)
    }

    @Test fun `short covered screen off spike is excluded but sustained drain remains detectable`() {
        val history = (0..3).map { session(it).copy(screenOffUah = 40_000) }
        val short = session(4).copy(screenOffCoveredMs = 59_999, screenOffUah = 4_000)
        assertTrue("A 6 percent per hour spike under one minute is unavailable",
            DeviceDetectors.detect(inputs(history + short, emptyList())).isEmpty())
        val sustained = short.copy(screenOffCoveredMs = HOUR / 2, screenOffUah = 60_000)
        val finding = DeviceDetectors.detect(inputs(history + sustained, emptyList())).single()
        assertEquals(FindingType.SCREEN_OFF_DRAIN_HIGH, finding.type)
        assertEquals(3.0, finding.evidence.single().observed, 0.0)
        val shortHistory = history.map { it.copy(screenOffCoveredMs = 59_999, screenOffUah = 666) }
        assertTrue("Short history cannot supply four eligible baselines",
            DeviceDetectors.detect(inputs(shortHistory + sustained, emptyList())).isEmpty())
    }

    @Test fun `screen off drain remains current at seven days and expires one millisecond later`() {
        assertDeviceExpiry(drainInput(), FindingType.SCREEN_OFF_DRAIN_HIGH)
    }

    @Test fun `doze blocked remains current at seven days and expires one millisecond later`() {
        assertDeviceExpiry(dozeInput(), FindingType.DOZE_BLOCKED)
    }

    @Test fun `device freshness retains import and future measurement exclusions`() {
        for (input in listOf(drainInput(), dozeInput())) {
            assertEquals(1, DeviceDetectors.detect(input).size)
            val current = input.sessions.last()
            assertTrue(DeviceDetectors.detect(input.copy(
                sessions = input.sessions.dropLast(1) + current.copy(imported = true),
            )).isEmpty())
            assertTrue(DeviceDetectors.detect(input.copy(nowMs = current.endMs - 1)).isEmpty())
        }
    }

    @Test fun `fresh whitelist capture remains independent of expired device measurement`() {
        val base = drainInput()
        val current = base.sessions.last()
        val input = base.copy(
            nowMs = current.endMs + 7 * 24 * HOUR + 1,
            sessions = base.sessions.dropLast(1) + current.copy(
                appWindow = current.appWindow!!.copy(captureEndMs = current.endMs + HOUR / 20),
            ),
            appSessions = listOf(row(current.id)),
            dozeUserWhitelist = setOf(APP),
        )
        assertEquals(FindingType.DOZE_WHITELISTED_DRAINER, DeviceDetectors.detect(input).single().type)
    }

    @Test fun `seeded stationary drain stays silent and realistic sustained effect remains detectable`() {
        val trials = 500
        for ((mode, seed) in listOf("flat" to 401, "noisy" to 402, "AR1" to 403)) {
            val random = Random(seed)
            var falseFindings = 0
            var detectedEffects = 0
            var staleFindings = 0
            repeat(trials) {
                var noise = 0.0
                val stationary = (0..7).map { index ->
                    noise = when (mode) {
                        "flat" -> 0.0
                        "noisy" -> random.nextDouble(-0.2, 0.2)
                        else -> 0.5 * noise + random.nextDouble(-0.2, 0.2)
                    }
                    session(index).copy(screenOffUah = (40_000 * (1.0 + noise)).toLong())
                }
                if (DeviceDetectors.detect(inputs(stationary, emptyList())).isNotEmpty()) falseFindings++
                val changed = stationary.dropLast(1) + stationary.last().copy(
                    screenOffCoveredMs = HOUR / 2, screenOffUah = 60_000,
                )
                if (DeviceDetectors.detect(inputs(changed, emptyList())).any {
                    it.type == FindingType.SCREEN_OFF_DRAIN_HIGH
                }) detectedEffects++
                val expired = inputs(changed, emptyList()).copy(
                    nowMs = changed.last().endMs + 7 * 24 * HOUR + 1,
                )
                staleFindings += DeviceDetectors.detect(expired).count {
                    it.type == FindingType.SCREEN_OFF_DRAIN_HIGH
                }
            }
            println("Screen-off $mode: $falseFindings/$trials false findings (maximum 10), " +
                "$detectedEffects/$trials sustained effects, $staleFindings/$trials stale findings (bound 0; seed $seed)")
            assertTrue("$mode false findings $falseFindings/$trials; maximum 10", falseFindings <= 10)
            assertEquals("$mode must detect a sustained 1 to 3 percent per hour effect", trials, detectedEffects)
            assertEquals("$mode expired sustained effects must never fire", 0, staleFindings)
        }
    }

    @Test fun `near flat screen off drain history does not amplify score`() {
        val sessions = listOf(0L, 0L, 8_000L, 10_000L, 13_200L, 140_000L).mapIndexed { index, uah ->
            session(index).copy(screenOffUah = uah)
        }
        val finding = DeviceDetectors.detect(inputs(sessions, emptyList())).single()
        assertEquals(FindingType.SCREEN_OFF_DRAIN_HIGH, finding.type)
        assertEquals(3.5, finding.evidence.single().observed, 1e-9)
        assertEquals(0.2, finding.evidence.single().baseline!!, 1e-9)
        assertEquals(99.0, finding.score, 1e-9)
    }

    @Test fun `whitelist flags only top five eligible measured background drainers`() {
        val sessions = listOf(session(0))
        val rows = (1..6).map { n -> row("s0").copy(packageName = "app.n$n", uid = UID + n, bgMs = n * 10_000L) }
        val input = inputs(sessions, rows).copy(dozeUserWhitelist = rows.map { it.packageName }.toSet())
        val findings = DeviceDetectors.detect(input)
        assertEquals(5, findings.size)
        assertTrue(findings.none { it.key.endsWith("app.n1") })
        assertTrue(DeviceDetectors.detect(input.copy(dozeUserWhitelist = null)).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(dozeUserWhitelist = emptySet())).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(sessions = sessions.map { it.copy(imported = true) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(appSessions = rows.map { it.copy(bgMs = null, partialWakelockBgMs = null) })).isEmpty())
        assertTrue(DeviceDetectors.detect(input.copy(appSessions = rows.map { it.copy(isOthers = true) })).isEmpty())
    }

    @Test fun `whitelist measurement expires immediately after seven days`() {
        val session = session(0)
        val input = inputs(listOf(session), listOf(row(session.id))).copy(dozeUserWhitelist = setOf(APP))
        val atBoundary = input.copy(nowMs = session.endMs + 7 * 24 * HOUR)
        assertEquals(FindingType.DOZE_WHITELISTED_DRAINER, DeviceDetectors.detect(atBoundary).single().type)
        assertTrue("A current whitelist cannot make an expired measurement current",
            DeviceDetectors.detect(atBoundary.copy(nowMs = atBoundary.nowMs + 1)).isEmpty())
    }

    @Test fun `seeded stationary whitelist activity expires without suppressing current positive controls`() {
        val trials = 500
        for ((mode, seed) in listOf("flat" to 511, "noisy" to 512, "AR1" to 513)) {
            val random = Random(seed)
            var staleFindings = 0
            var currentFindings = 0
            repeat(trials) {
                var noise = 0.0
                val sessions = (0..7).map { session(it) }
                val rows = sessions.map { s ->
                    noise = when (mode) {
                        "flat" -> 0.0
                        "noisy" -> random.nextDouble(-0.2, 0.2)
                        else -> 0.5 * noise + random.nextDouble(-0.2, 0.2)
                    }
                    row(s.id).copy(bgMs = (60_000 * (1 + noise)).toLong(), powerMah = 5 * (1 + noise))
                }
                val input = inputs(sessions, rows).copy(dozeUserWhitelist = setOf(APP))
                // Whitelist activity intentionally needs one measured window, rather than a baseline anomaly.
                val age = random.nextLong(8 * 24 * HOUR, 90 * 24 * HOUR + 1)
                staleFindings += DeviceDetectors.detect(input.copy(nowMs = sessions.last().endMs + age))
                    .count { it.type == FindingType.DOZE_WHITELISTED_DRAINER }
                currentFindings += DeviceDetectors.detect(input.copy(nowMs = sessions.last().endMs + 7 * 24 * HOUR))
                    .count { it.type == FindingType.DOZE_WHITELISTED_DRAINER }
            }
            println("Whitelist $mode: $staleFindings/$trials stale findings (bound 0), $currentFindings/$trials current controls (seed $seed)")
            assertEquals("$mode expired activity must never fire", 0, staleFindings)
            assertEquals("$mode current measured whitelist activity remains eligible", trials, currentFindings)
        }
    }

    @Test fun `attributions match evaluated session omit unsupported sources cap and break ties by name`() {
        val base = dozeInput()
        val id = base.sessions.last().id
        val wakers = (1..6).map { DeviceWakerInput(id, WakerKind.KERNEL_WAKELOCK, "kernel.$it", 5, 1000) } +
            DeviceWakerInput("other", WakerKind.KERNEL_WAKELOCK, "unrelated", 100, 99999)
        val rows = (1..6).map { row(id).copy(packageName = "app.n$it", uid = UID + it, wakeupAlarms = 20) } +
            row(id).copy(packageName = "app.unsupported", wakeupAlarms = null, partialWakelockBgMs = null)
        val input = base.copy(deviceWakers = wakers, appSessions = rows)
        val finding = DeviceDetectors.detect(input).single()
        assertEquals(10, finding.attributions.size)
        assertEquals((1..5).map { "kernel.$it" }, finding.attributions.take(5).map { it.name })
        assertEquals((1..5).map { "app.n$it" }, finding.attributions.drop(5).map { it.packageName })
        assertTrue(finding.attributions.take(5).all { it.kind == AttributionKind.KERNEL_WAKELOCK && it.unit == MetricUnit.MS })
        assertTrue(finding.attributions.drop(5).all { it.value == 10.0 && it.unit == MetricUnit.COUNT_PER_H })
        assertEquals(finding, DeviceDetectors.detect(input.copy(deviceWakers = wakers.reversed(), appSessions = rows.reversed())).single())
        val fallback = input.copy(deviceWakers = listOf(DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "alarm", 7, 0)),
            appSessions = listOf(row(id).copy(wakeupAlarms = null, partialWakelockBgMs = 60_000)))
        val hints = DeviceDetectors.detect(fallback).single().attributions
        assertEquals(MetricUnit.COUNT, hints[0].unit)
        assertEquals(7.0, hints[0].value, 0.0)
        assertEquals(MetricUnit.MS_PER_H, hints[1].unit)
        assertEquals(30_000.0, hints[1].value, 0.0)
        assertEquals(1, DeviceDetectors.detect(fallback.copy(sessions = base.sessions.map { it.copy(appWindow = null) }))
            .single().attributions.size)
    }

    @Test fun `device attributions reserve slots for wakeup reasons`() {
        val base = dozeInput()
        val id = base.sessions.last().id
        val wakers = (1..6).map { DeviceWakerInput(id, WakerKind.KERNEL_WAKELOCK, "kernel.$it", 5, 60_000L * it) } +
            listOf(
                DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "reason.1", 300, 0),
                DeviceWakerInput(id, WakerKind.WAKEUP_REASON, "reason.2", 200, 0),
            )
        val finding = DeviceDetectors.detect(base.copy(deviceWakers = wakers)).single()
        val deviceAttributions = finding.attributions.takeWhile { it.kind != AttributionKind.APP }
        assertEquals(setOf("reason.1", "reason.2"), deviceAttributions.filter {
            it.kind == AttributionKind.WAKEUP_REASON
        }.map { it.name }.toSet())

        val kernelOnly = DeviceDetectors.detect(base.copy(deviceWakers = wakers.filter {
            it.kind == WakerKind.KERNEL_WAKELOCK
        })).single().attributions.takeWhile { it.kind != AttributionKind.APP }
        assertEquals(5, kernelOnly.size)
        assertTrue(kernelOnly.all { it.kind == AttributionKind.KERNEL_WAKELOCK })
    }

    private fun assertDeviceExpiry(input: InsightInputs, type: FindingType) {
        val boundary = input.copy(nowMs = input.sessions.last().endMs + 7 * 24 * HOUR)
        val finding = DeviceDetectors.detect(boundary).single()
        assertEquals(type, finding.type)
        assertEquals("Older sessions must remain available for the baseline", 4, finding.evidence.first().sessions)
        assertTrue("The latest device measurement expires immediately after seven days",
            DeviceDetectors.detect(boundary.copy(nowMs = boundary.nowMs + 1)).isEmpty())
    }

    private fun drainInput() = inputs((0..4).map {
        session(it).copy(screenOffUah = if (it < 4) 40_000 else 160_000)
    }, emptyList())

    private fun dozeInput() = inputs((0..4).map {
        session(it, 2 * HOUR).copy(screenOffDozeMs = if (it < 4) HOUR * 16 / 10 else HOUR / 10,
            screenOffSuspendMs = if (it < 4) HOUR * 18 / 10 else HOUR / 10)
    }, emptyList())
}
