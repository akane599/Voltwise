package com.akane.voltwise.battery.insights.engine.recommend

import com.akane.voltwise.battery.actions.CommandPolicy
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Recommendation
import com.akane.voltwise.battery.insights.model.Subject

object Recommender {
    fun recommend(finding: Finding, inputs: InsightInputs, sdkInt: Int): Finding {
        val app = finding.subject as? Subject.App
        val pkg = app?.packageName
        val invalidPackage = pkg != null && !CommandPolicy.isPackageName(pkg)
        val privilegedAppActionsBlocked = app != null &&
            (invalidPackage || CommandPolicy.isProtected(app.packageName, app.uid))
        val whitelisted = pkg != null && inputs.dozeUserWhitelist?.contains(pkg) == true
        val appFixes = if (whitelisted) {
            listOf(ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_APP_SETTINGS)
        } else appActions
        val actions = when (finding.type) {
            FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.STUCK_WAKELOCK,
            FindingType.WAKEUP_STORM, FindingType.JOB_STORM, FindingType.BACKGROUND_LOCATION,
            FindingType.BACKGROUND_RADIO -> appFixes
            FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE ->
                appFixes.dropLast(1) + ActionType.FORCE_STOP + ActionType.OPEN_APP_SETTINGS
            FindingType.DOZE_WHITELISTED_DRAINER -> listOf(
                ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
            )
            FindingType.DOZE_BLOCKED, FindingType.SCREEN_OFF_DRAIN_HIGH ->
                listOf(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
            FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING -> listOf(ActionType.ENABLE_HIGH_BATTERY_ALERT)
            FindingType.TREND, FindingType.HEALTH_DECLINE, FindingType.ACTION_EFFECT -> emptyList()
        }
        // Live whitelist membership proves an earlier removal no longer holds.
        val applied = inputs.actions.filter {
            it.status == ActionStatus.APPLIED && it.packageName == pkg &&
                (app == null || it.uid == app.uid) &&
                !((whitelisted || finding.type == FindingType.DOZE_WHITELISTED_DRAINER) &&
                    it.type == ActionType.REMOVE_DOZE_WHITELIST)
        }.map { it.type }.toSet()
        return finding.copy(recommendations = actions.filterNot { action ->
            action in applied || (action == ActionType.ENABLE_HIGH_BATTERY_ALERT && inputs.highBatteryAlertEnabled) ||
                (privilegedAppActionsBlocked && action in privilegedActions) ||
                (invalidPackage && action == ActionType.OPEN_APP_SETTINGS) ||
                (sdkInt < 28 &&
                    (action == ActionType.RESTRICT_BACKGROUND || action == ActionType.STANDBY_BUCKET_RESTRICTED ||
                        action == ActionType.STANDBY_BUCKET_RARE))
        }.map { action ->
            Recommendation(action, action != ActionType.FORCE_STOP, action in privilegedActions)
        })
    }

    private val appActions = listOf(
        ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.OPEN_APP_SETTINGS,
    )
    private val privilegedActions = setOf(
        ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.STANDBY_BUCKET_RARE,
        ActionType.FORCE_STOP, ActionType.REMOVE_DOZE_WHITELIST,
    )
}
