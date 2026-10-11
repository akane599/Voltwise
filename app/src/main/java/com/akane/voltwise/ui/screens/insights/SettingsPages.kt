package com.akane.voltwise.ui.screens.insights

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
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

/**
 * Passes each page of [settingsFallbacks] for [spec] to [start] until one opens, and returns that page's index (0 is
 * the page asked for), or null when none did. A missing page throws ActivityNotFoundException; on some OEM builds a
 * page that exists but isn't exported throws SecurityException. Either way the next page is tried.
 */
internal fun openFirst(spec: IntentSpec, start: (IntentSpec) -> Unit): Int? =
    settingsFallbacks(spec).indexOfFirst { page ->
        try {
            start(page)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }.takeIf { it >= 0 }

/** Opens the first page of [settingsFallbacks] for [spec] this device lets the app open; see [openFirst]. */
internal fun openSettingsPage(context: Context, spec: IntentSpec): Int? =
    openFirst(spec) { context.startActivity(it.toIntent()) }

/** What to tell the user after a settings page was opened. */
internal enum class SettingsNotice { UNAVAILABLE, FALLBACK, BATTERY_OPTIMIZATION_FALLBACK }

/**
 * The notice for [spec] once [openFirst] returned [opened]: none when the page asked for opened; a fallback page
 * says it isn't the one asked for (with where to find battery optimization when that was the page).
 */
internal fun settingsNotice(spec: IntentSpec, opened: Int?): SettingsNotice? = when {
    opened == null -> SettingsNotice.UNAVAILABLE
    opened == 0 -> null
    spec.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK
    else -> SettingsNotice.FALLBACK
}

/**
 * Remembers a function that opens a settings page with [openSettingsPage] and returns the snackbar text its
 * [settingsNotice] calls for, or null when the page asked for opened. Its text follows the current locale.
 */
@Composable
internal fun rememberSettingsOpener(): (IntentSpec) -> String? {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(stringResource(R.string.insights_settings_unavailable))
    val fallback by rememberUpdatedState(stringResource(R.string.insights_settings_fallback))
    val batteryFallback by rememberUpdatedState(stringResource(R.string.insights_settings_fallback_battery_optimization))
    return remember(context) {
        { spec ->
            when (settingsNotice(spec, openSettingsPage(context, spec))) {
                null -> null
                SettingsNotice.UNAVAILABLE -> unavailable
                SettingsNotice.FALLBACK -> fallback
                SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK -> batteryFallback
            }
        }
    }
}

/** The notification settings page takes its package as an extra; every other page as `package:` data. */
internal fun IntentSpec.toIntent(): Intent {
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
