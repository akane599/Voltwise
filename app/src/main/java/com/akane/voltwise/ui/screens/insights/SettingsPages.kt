package com.akane.voltwise.ui.screens.insights

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.akane.voltwise.battery.insights.actions.IntentSpec

/**
 * The settings pages to try for [spec], in order, until one opens: the page asked for, then the app's details page
 * (only when [spec] names a package), then Android's Settings home. Some OEM and Go builds lack pages such as
 * IGNORE_BATTERY_OPTIMIZATION_SETTINGS, and a button that opens nothing looks broken.
 */
internal fun settingsFallbacks(spec: IntentSpec): List<IntentSpec> = listOfNotNull(
    spec,
    spec.packageName?.let { IntentSpec(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it) },
    IntentSpec(Settings.ACTION_SETTINGS),
).distinct()

/** This app's notification settings page, as a [spec][IntentSpec] for [openSettingsPage]. */
internal fun notificationSettings(packageName: String): IntentSpec =
    IntentSpec(Settings.ACTION_APP_NOTIFICATION_SETTINGS, packageName)

/** Opens the first page of [settingsFallbacks] for [spec] this device has; false when none of them could open. */
internal fun openSettingsPage(context: Context, spec: IntentSpec): Boolean = settingsFallbacks(spec).any { page ->
    try {
        context.startActivity(page.toIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The notification settings page takes its package as an extra; every other page as `package:` data. */
private fun IntentSpec.toIntent(): Intent {
    val intent = Intent(action)
    packageName?.let { pkg ->
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        } else {
            intent.data = Uri.fromParts("package", pkg, null)
        }
    }
    return intent
}
