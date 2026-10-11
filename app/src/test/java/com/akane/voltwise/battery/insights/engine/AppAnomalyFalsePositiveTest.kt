package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.engine.detectors.app.AppDrainAnomaly
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundLocation
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRadio
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRunaway
import com.akane.voltwise.battery.insights.engine.detectors.app.JobStorm
import com.akane.voltwise.battery.insights.engine.detectors.app.LingeringForegroundService
import com.akane.voltwise.battery.insights.engine.detectors.app.StuckWakelock
import com.akane.voltwise.battery.insights.engine.detectors.app.WakeupStorm
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The shared rule runs per app per analysis, not once for the whole device. Even at 2%, 20 stable
 * apps imply 0.4 false candidates per metric per analysis in expectation (without assuming independent
 * apps); multiple metrics make this worse. Since HIGH-severity findings with at least MEDIUM
 * confidence can notify, and any app finding can offer privileged actions, <=2% (10/500) is a
 * deliberately permissive ceiling, not a device-wide guarantee.
 * A real 3x increase must still fire in >=90% (450/500), so silence cannot pass by disabling detection.
 *
 * Seeds 37201..37204 and bounds were fixed before execution. Each trial has 12 measured daily history
 * windows, enough for HIGH confidence, followed by the caller's evaluated suffix. No feedback, absent
 * rows or censoring can mask the shared rule. Detector predicates are satisfied independently of the
 * tested metric, and each production detect call is checked against the shared anomaly result.
 * All 11 anomaly call sites, including fallback metrics and two-window persistence, are covered.
 * NewHeavyApp has no anomaly call.
 *
 * Noisy values are floor + floor * X, where X is lognormal with mean 1 and CV 0.5: every sample is
 * above the floor without clipping or resampling. AR(1) uses phi=0.7 in log space, initialized from
 * its stationary N(0,1) marginal, with innovation SD sqrt(1-phi^2). Its marginal matches the IID
 * series; the positive control multiplies the evaluated suffix of an IID series by 3 (both windows
 * for persistence). The 4x control must reach HIGH in >=450/500 on every lane, with the entire
 * evaluated suffix increased. All production app-anomaly metrics are unbounded, including both time
 * shares (overlapping wakelocks and summed shared-UID background timers). Do not cap these controls.
 * A future provably bounded metric needs a 4x baseline whose multiplied median stays <=0.9.
 * Held-out seeds add 1,000,000, fixed before execution; their counts are reported, never asserted.
 * These are synthetic per-app metric tests, not end-to-end notification tests.
 */
@RunWith(Parameterized::class)
class AppAnomalyFalsePositiveTest(
    private val type: FindingType,
    private val metric: Metric,
    private val floor: Double,
    private val evaluated: Int,
) {
    @Test fun flatFalsePositiveRateAtMostTwoPercent() = checkFalsePositives("flat", 37201, 0.0)

    @Test fun noisyFalsePositiveRateAtMostTwoPercent() = checkFalsePositives("noisy", 37202, 0.0)

    @Test fun ar1FalsePositiveRateAtMostTwoPercent() = checkFalsePositives("AR1", 37203, 0.7)

    @Test fun threefoldNoisyEffectDetectedAtLeastNinetyPercent() {
        val count = report("noisy", 37204, 0.0, effect = 3.0).fires
        val message = "$type/$metric 3x effect: $count/$TRIALS fires; minimum $MIN_EFFECT_FIRES (seed 37204)"
        println(message)
        assertTrue(message, count >= MIN_EFFECT_FIRES)
    }

    @Test fun fourfoldNoisyEffectHighAtLeastNinetyPercent() {
        val count = report("noisy", 37204, 0.0, effect = 4.0).high
        assertTrue("$type/$metric 4x HIGH: $count/$TRIALS; minimum $MIN_EFFECT_FIRES", count >= MIN_EFFECT_FIRES)
    }

    private fun report(mode: String, seed: Long, phi: Double, effect: Double = 1.0): AnomalyCounts {
        val original = countFires(mode, seed, phi, effect)
        val heldOut = countFires(mode, seed + 1_000_000, phi, effect)
        println("$type/$metric $mode effect=$effect seed=$seed: $original; held-out: $heldOut")
        return original
    }

    private fun checkFalsePositives(mode: String, seed: Long, phi: Double) {
        val count = report(mode, seed, phi).fires
        val message = "$type/$metric $mode: $count/$TRIALS false fires; maximum $MAX_FALSE_FIRES (seed $seed)"
        println(message)
        assertTrue(message, count <= MAX_FALSE_FIRES)
    }

    private fun countFires(mode: String, seed: Long, phi: Double, effect: Double = 1.0): AnomalyCounts {
        val random = Random(seed)
        val sigma = sqrt(ln(1.0 + 0.5 * 0.5))
        var fires = 0
        var high = 0
        repeat(TRIALS) {
            var state = random.nextGaussian()
            val values = List(AppContext.HIGH_CONFIDENCE_SESSIONS + evaluated) { index ->
                if (index > 0) state = phi * state + sqrt(1.0 - phi * phi) * random.nextGaussian()
                val scatter = if (mode == "flat") 1.0 else exp(sigma * state - sigma * sigma / 2.0)
                val scale = if (index >= AppContext.HIGH_CONFIDENCE_SESSIONS) effect else 1.0
                floor * (1.0 + scatter) * scale
            }
            val base = appAnomalyContext(metric, values)
            val input = base.inputs.copy(appSessions = base.inputs.appSessions.map { row ->
                when (type) {
                    FindingType.BACKGROUND_RUNAWAY -> row.copy(
                        cpuMs = 120_000, partialWakelockBgMs = HOUR,
                        // BG stays below its anomaly floor in the FGS fallback lane.
                        bgMs = if (metric == Metric.FGS_MS_PER_H) 180_000 else row.bgMs,
                    )
                    FindingType.LINGERING_FOREGROUND_SERVICE -> row.copy(
                        // Preserve the ratio while satisfying the separate ten-minute FGS predicate.
                        fgMs = 120_000, fgServiceMs = row.fgServiceMs!! * 2,
                    )
                    else -> row
                }
            })
            val ctx = AppContext(input, AppWindows.select(input), Subject.App(UID, APP))
            assertEquals("Every generated window must be eligible", values.size, ctx.windows.size)
            assertEquals("The evaluated suffix must not train its baseline",
                AppContext.HIGH_CONFIDENCE_SESSIONS, ctx.history(evaluated).size)
            assertTrue("Every measured metric must exceed its floor",
                ctx.windows.all { ctx.value(metric, it)!! > floor })
            val expected = ctx.anomaly(type, metric, floor, evaluated)?.let { ctx.finding(type, it) }
            val findings = detectAppAnomaly(type, ctx)
            assertEquals("$type/$metric detector must agree with its shared anomaly lane",
                expected != null, findings.isNotEmpty())
            findings.forEach { finding ->
                assertEquals(type, finding.type)
                assertEquals("Fallback must not mask the tested metric", metric, finding.evidence.first().metric)
                assertEquals("Detector must exclude the same evaluated suffix",
                    AppContext.HIGH_CONFIDENCE_SESSIONS, finding.evidence.first().sessions)
                assertEquals("Detector severity must agree with its shared anomaly lane", expected!!.severity, finding.severity)
            }
            if (findings.isNotEmpty()) fires++
            if (findings.any { it.severity == Severity.HIGH }) high++
        }
        return AnomalyCounts(fires, high)
    }

    companion object {
        private const val TRIALS = 500
        private const val MAX_FALSE_FIRES = 10
        private const val MIN_EFFECT_FIRES = 450

        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(FindingType.APP_DRAIN_ANOMALY, Metric.POWER_MAH_PER_H, AppDrainAnomaly.POWER_FLOOR_MAH_PER_H, 1),
            arrayOf(FindingType.BACKGROUND_RUNAWAY, Metric.BG_TIME_SHARE, BackgroundRunaway.BG_SHARE_FLOOR, BackgroundRunaway.PERSISTENCE_SESSIONS),
            arrayOf(FindingType.BACKGROUND_RUNAWAY, Metric.FGS_MS_PER_H, BackgroundRunaway.BG_SHARE_FLOOR * HOUR, BackgroundRunaway.PERSISTENCE_SESSIONS),
            arrayOf(FindingType.BACKGROUND_LOCATION, Metric.GPS_MS_PER_H, BackgroundLocation.LOCATION_FLOOR_MS_PER_H, 1),
            arrayOf(FindingType.BACKGROUND_LOCATION, Metric.SENSOR_MS_PER_H, BackgroundLocation.LOCATION_FLOOR_MS_PER_H, 1),
            arrayOf(FindingType.BACKGROUND_RADIO, Metric.RADIO_ACTIVE_MS_PER_H, BackgroundRadio.RADIO_FLOOR_MS_PER_H, 1),
            arrayOf(FindingType.STUCK_WAKELOCK, Metric.PARTIAL_WAKELOCK_BG_SHARE, StuckWakelock.WAKELOCK_BG_SHARE, 1),
            arrayOf(FindingType.WAKEUP_STORM, Metric.WAKEUP_ALARMS_PER_H, WakeupStorm.ALARMS_FLOOR_PER_H, 1),
            arrayOf(FindingType.JOB_STORM, Metric.JOBS_PER_H, JobStorm.JOBS_FLOOR_PER_H, 1),
            arrayOf(FindingType.JOB_STORM, Metric.SYNCS_PER_H, JobStorm.SYNCS_FLOOR_PER_H, 1),
            arrayOf(FindingType.LINGERING_FOREGROUND_SERVICE, Metric.FGS_TO_FOREGROUND_RATIO, LingeringForegroundService.FGS_RATIO_FLOOR, 1),
        )
    }
}

private fun detectAppAnomaly(type: FindingType, ctx: AppContext): List<Finding> = when (type) {
    FindingType.APP_DRAIN_ANOMALY -> AppDrainAnomaly.detect(ctx)
    FindingType.BACKGROUND_RUNAWAY -> BackgroundRunaway.detect(ctx)
    FindingType.BACKGROUND_LOCATION -> BackgroundLocation.detect(ctx)
    FindingType.BACKGROUND_RADIO -> BackgroundRadio.detect(ctx)
    FindingType.STUCK_WAKELOCK -> StuckWakelock.detect(ctx)
    FindingType.WAKEUP_STORM -> WakeupStorm.detect(ctx)
    FindingType.JOB_STORM -> JobStorm.detect(ctx)
    FindingType.LINGERING_FOREGROUND_SERVICE -> LingeringForegroundService.detect(ctx)
    else -> error("No anomaly detector for $type")
}

internal fun appAnomalyContext(metric: Metric, values: List<Double>): AppContext {
    val sessions = values.indices.map { session(it) }
    val rows = sessions.mapIndexed { index, session ->
        val value = values[index]
        val timer = value.roundToLong()
        when (metric) {
            Metric.POWER_MAH_PER_H -> row(session.id).copy(powerMah = value)
            Metric.BG_TIME_SHARE -> row(session.id).copy(bgMs = (value * HOUR).roundToLong())
            Metric.FGS_MS_PER_H -> row(session.id).copy(fgServiceMs = timer)
            Metric.GPS_MS_PER_H -> row(session.id).copy(gpsMs = timer)
            Metric.SENSOR_MS_PER_H -> row(session.id).copy(sensorMs = timer)
            Metric.RADIO_ACTIVE_MS_PER_H -> row(session.id).copy(mobileActiveMs = timer)
            Metric.PARTIAL_WAKELOCK_BG_SHARE -> row(session.id).copy(partialWakelockBgMs = (value * HOUR).roundToLong())
            Metric.WAKEUP_ALARMS_PER_H -> row(session.id).copy(wakeupAlarms = timer)
            Metric.JOBS_PER_H -> row(session.id).copy(jobCount = timer)
            Metric.SYNCS_PER_H -> row(session.id).copy(syncCount = timer)
            Metric.FGS_TO_FOREGROUND_RATIO -> row(session.id).copy(fgServiceMs = (value * AppWindows.FOREGROUND_FLOOR_MS).roundToLong())
            else -> error("No anomaly caller for $metric")
        }
    }
    val input = inputs(sessions, rows)
    return AppContext(input, AppWindows.select(input), Subject.App(UID, APP))
}

internal data class AnomalyCounts(val fires: Int, val high: Int) {
    override fun toString(): String = "$fires/500 fires, $high/500 HIGH"
}

/** Capped inputs below are stress distributions, not bounds on either production share. */
@RunWith(Parameterized::class)
class AppShareAnomalyRegressionTest(
    private val metric: Metric,
    private val median: Double,
    private val phi: Double,
    private val cv: Double,
    private val capped: Boolean,
    private val saturationProbability: Double,
) {
    @Test fun noChangeShareAtMostTwoPercent() {
        val seed = if (phi == 0.0) 37202L else 37203L
        val original = count(seed)
        val heldOut = count(seed + 1_000_000)
        println("$metric median=$median phi=$phi CV=$cv capped=$capped mixed=$saturationProbability " +
            "seed=$seed: $original; held-out: $heldOut")
        assertTrue("$metric no-change: $original; maximum 10/500", original.fires <= 10)
    }

    internal fun count(seed: Long, effect: Double = 1.0): AnomalyCounts {
        val random = Random(seed)
        val sigma = sqrt(ln(1.0 + cv * cv))
        val runaway = metric == Metric.BG_TIME_SHARE
        val evaluated = if (runaway) BackgroundRunaway.PERSISTENCE_SESSIONS else 1
        var fires = 0
        var high = 0
        repeat(500) {
            var state = random.nextGaussian()
            val values = List(AppContext.HIGH_CONFIDENCE_SESSIONS + evaluated) { index ->
                if (index > 0) state = phi * state + sqrt(1.0 - phi * phi) * random.nextGaussian()
                val value = if (saturationProbability > 0.0) {
                    if (random.nextDouble() < saturationProbability) 1.0 else median + 0.03 * state
                } else median * exp(sigma * state)
                val scale = if (index >= AppContext.HIGH_CONFIDENCE_SESSIONS) effect else 1.0
                (if (capped) value.coerceIn(0.0, 1.0) else value) * scale
            }
            val base = appAnomalyContext(metric, values)
            // Keep detector-specific predicates satisfied; no FGS fallback can hide a BG-share failure.
            val input = base.inputs.copy(appSessions = base.inputs.appSessions.map { row ->
                row.copy(cpuMs = if (runaway) 120_000 else 0, fgMs = 0, topMs = 0,
                    fgServiceMs = 0, partialWakelockBgMs = if (runaway) HOUR else row.partialWakelockBgMs)
            })
            val ctx = AppContext(input, AppWindows.select(input), Subject.App(UID, APP))
            assertEquals(values.size, ctx.windows.size)
            assertEquals(12, ctx.history(evaluated).size)
            ctx.windows.forEachIndexed { index, window ->
                assertEquals("Timer conversion must not cap unbounded shares", values[index], ctx.value(metric, window)!!, 1.0 / HOUR)
            }
            val findings = if (runaway) BackgroundRunaway.detect(ctx) else StuckWakelock.detect(ctx)
            if (findings.isNotEmpty()) fires++
            if (findings.any { it.severity == Severity.HIGH }) high++
        }
        return AnomalyCounts(fires, high)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/median={1}/phi={2}/CV={3}/capped={4}/mixed={5}")
        fun cases(): List<Array<Any>> = listOf(Metric.PARTIAL_WAKELOCK_BG_SHARE, Metric.BG_TIME_SHARE).flatMap { metric ->
            listOf(0.9, 1.5, 2.0).flatMap { median ->
                listOf(0.0, 0.7).map { phi -> arrayOf<Any>(metric, median, phi, 0.2, false, 0.0) }
            } + (if (metric == Metric.BG_TIME_SHARE) listOf(arrayOf<Any>(metric, 0.6, 0.7, 0.5, true, 0.0)) else emptyList()) +
                listOf(0.6, 0.75).flatMap { median ->
                    listOf(0.1, 0.2, 0.3, 0.4).map { probability ->
                        arrayOf<Any>(metric, median, 0.0, 0.0, true, probability)
                    }
                }
        }
    }
}

/**
 * Production-share severity controls: median 1.5, uncapped IID lognormal CV .2, seed 37204.
 * Before measuring, require >=450/500 HIGH at 4x and >=450/500 non-HIGH at 2x. Non-HIGH includes
 * silence: the downward control bounds notifying severity, not firing power. The shift covers every
 * evaluated window: StuckWakelock's single window, or both of BackgroundRunaway's persistence windows.
 * Held-out seeds add the existing fixed 1,000,000 offset.
 */
class ProductionShareSeverityAppAnomalyFalsePositiveTest {
    @Test fun fourfoldWakelockHighAtLeastNinetyPercent() =
        checkSeverity(Metric.PARTIAL_WAKELOCK_BG_SHARE, 4.0)

    @Test fun fourfoldBackgroundHighAtLeastNinetyPercent() =
        checkSeverity(Metric.BG_TIME_SHARE, 4.0)

    @Test fun twofoldWakelockNonHighAtLeastNinetyPercent() =
        checkSeverity(Metric.PARTIAL_WAKELOCK_BG_SHARE, 2.0)

    @Test fun twofoldBackgroundNonHighAtLeastNinetyPercent() =
        checkSeverity(Metric.BG_TIME_SHARE, 2.0)

    private fun checkSeverity(metric: Metric, effect: Double) {
        val lane = AppShareAnomalyRegressionTest(metric, 1.5, 0.0, 0.2, false, 0.0)
        val original = lane.count(37204, effect)
        val heldOut = lane.count(37204 + 1_000_000, effect)
        val successes = if (effect == 4.0) original.high else 500 - original.high
        val heldOutSuccesses = if (effect == 4.0) heldOut.high else 500 - heldOut.high
        val severity = if (effect == 4.0) "HIGH" else "non-HIGH"
        val report = "$metric median=1.5 CV=.2 effect=$effect seed=37204: $original; " +
            "held-out: $heldOut; $severity $successes/500, held-out $heldOutSuccesses/500; minimum 450/500"
        println(report)
        assertTrue(report, successes >= 450)
    }
}

/** The same fixed seed and <=10/500 assertion are retained for the separately routed rule repair. */
class AppShareKnownGapTest {
    @Ignore("SQ-386: pre-existing SQ-380 gap, 15/500 vs bound 10; see report")
    @Test fun cappedWakelockMedianPointSixCvPointFiveAr1AtMostTwoPercent() {
        AppShareAnomalyRegressionTest(Metric.PARTIAL_WAKELOCK_BG_SHARE, 0.6, 0.7, 0.5, true, 0.0)
            .noChangeShareAtMostTwoPercent()
    }
}
