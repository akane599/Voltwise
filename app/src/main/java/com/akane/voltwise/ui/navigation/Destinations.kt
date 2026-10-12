package com.akane.voltwise.ui.navigation

import android.content.Intent

// CLEAR_TOP finds the existing activity; SINGLE_TOP keeps it alive to receive onNewIntent.
internal fun mainActivityLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

/**
 * String values for the `destination` intent extra used by deep links: notification tap today;
 * the QS tile long-press and widgets follow in P5. [com.akane.voltwise.battery.BatteryMainActivity]
 * accepts [EXTRA_DESTINATION] only on a fresh, non-history `onCreate` or in `onNewIntent`,
 * then removes the handled extra. Restored activities keep their saved navigation state instead
 * of replaying the launch destination. [com.akane.voltwise.ui.navigation.openDestination] applies a value to a
 * [TopLevelBackStack].
 */
object Destinations {
    const val EXTRA_DESTINATION = "destination"

    const val NOW = "now"
    const val INSIGHTS = "insights"
    const val HISTORY = "history"
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val HEALTH = "health"
    const val STATUS = "status"

    private const val SESSION_PREFIX = "session:"

    fun initialDestination(
        extra: String?,
        restored: Boolean,
        launchedFromHistory: Boolean,
    ): String? = if (restored || launchedFromHistory) null else extra

    fun session(sessionId: String) = "$SESSION_PREFIX$sessionId"

    fun sessionIdOrNull(value: String): String? =
        value.takeIf { it.startsWith(SESSION_PREFIX) }?.removePrefix(SESSION_PREFIX)
}
