package com.akane.voltwise.ui.screens.insights

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.actions.IntentSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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

/** What to tell the user after trying to open a settings page: a fallback page opened, or none could be opened. */
internal enum class SettingsNotice { UNAVAILABLE, FALLBACK, BATTERY_OPTIMIZATION_FALLBACK }

/**
 * The notice for [spec] once [openFirst] returned [opened]: none when the page asked for opened;
 * [SettingsNotice.UNAVAILABLE] when no page opened at all; otherwise a fallback page opened, and the notice says it
 * isn't the one asked for (with where to find battery optimization when that was the page).
 */
internal fun settingsNotice(spec: IntentSpec, opened: Int?): SettingsNotice? = when {
    opened == null -> SettingsNotice.UNAVAILABLE
    opened == 0 -> null
    spec.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK
    else -> SettingsNotice.FALLBACK
}

/** A notice's snackbar text. */
@StringRes
internal fun SettingsNotice.messageRes(): Int = when (this) {
    SettingsNotice.UNAVAILABLE -> R.string.insights_settings_unavailable
    SettingsNotice.FALLBACK -> R.string.insights_settings_fallback
    SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK -> R.string.insights_settings_fallback_battery_optimization
}

/**
 * Waits until [lifecycleState] is at least RESUMED, then calls [show] once. A settings page covers the app (STARTED
 * or below) until the user comes back, so a notice shown at once would expire unseen.
 */
internal suspend fun showOnceResumed(lifecycleState: Flow<Lifecycle.State>, show: suspend () -> Unit) {
    lifecycleState.first { it.isAtLeast(Lifecycle.State.RESUMED) }
    show()
}

/**
 * Remembers a function that opens a settings page with [openSettingsPage] and holds the [settingsNotice] it calls
 * for, if any. The held notice survives recreation and shows in [snackbar] (Long, in the current locale) once the
 * app is resumed again, which is at once when nothing opened; it is cleared once that snackbar has gone. It lives in
 * this composable's state, not in the caller's effect, so consuming the effect that opened the page doesn't drop it.
 */
@Composable
internal fun rememberSettingsOpener(snackbar: SnackbarHostState): (IntentSpec) -> Unit {
    val context = LocalContext.current
    // The enum is saved by name.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    pending?.let(SettingsNotice::valueOf)?.let { notice ->
        val text = stringResource(notice.messageRes())
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(notice, lifecycle) {
            showOnceResumed(lifecycle.currentStateFlow) { snackbar.showSnackbar(text, duration = SnackbarDuration.Long) }
            pending = null
        }
    }
    return remember(context) {
        { spec -> settingsNotice(spec, openSettingsPage(context, spec))?.let { pending = it.name } }
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
