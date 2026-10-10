package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.engine.detectors.app.AppDrainAnomaly
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundLocation
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRadio
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRunaway
import com.akane.voltwise.battery.insights.engine.detectors.app.JobStorm
import com.akane.voltwise.battery.insights.engine.detectors.app.LingeringForegroundService
import com.akane.voltwise.battery.insights.engine.detectors.app.NewHeavyApp
import com.akane.voltwise.battery.insights.engine.detectors.app.StuckWakelock
import com.akane.voltwise.battery.insights.engine.detectors.app.WakeupStorm
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Subject

/** T4b seam: app-family candidates, already ranked; the combined engine applies its global 12-item cap. */
fun appFindings(inputs: InsightInputs): List<Finding> {
    val windows = AppWindows.select(inputs)
    val current = windows.lastOrNull() ?: return emptyList()
    if (current.atMs < inputs.nowMs - MAX_CURRENT_WINDOW_AGE_MS) return emptyList()
    val detectors = listOf(
        AppDrainAnomaly::detect, NewHeavyApp::detect, BackgroundRunaway::detect, StuckWakelock::detect,
        WakeupStorm::detect, JobStorm::detect, BackgroundLocation::detect, BackgroundRadio::detect,
        LingeringForegroundService::detect,
    )
    return current.rows.filterNot { it.isOthers }.map { Subject.App(it.uid, it.packageName) }.distinct()
        .flatMap { subject ->
            val ctx = AppContext(inputs, windows, subject)
            detectors.flatMap { it(ctx) }
        }
        .distinctBy { it.key }
        .sortedWith(findingOrder)
        .take(MAX_APP_FINDINGS)
}

/** App findings stay current for up to a week without another eligible discharge window. */
const val MAX_CURRENT_WINDOW_AGE_MS = 7 * 24 * 3_600_000L

private const val MAX_APP_FINDINGS = 12
