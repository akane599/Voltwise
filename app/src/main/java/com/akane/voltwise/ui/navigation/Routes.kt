package com.akane.voltwise.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 keys for the 5-tab shell.
 *
 * [Now], [Insights], [History], [Apps] and [Settings] are the top-level tabs; each owns its own
 * [androidx.navigation3.runtime.NavBackStack] (see [TopLevelBackStack]). The rest are detail
 * routes, pushed onto whichever tab is active when they're reached.
 */
@Serializable
sealed interface Routes : NavKey {
    @Serializable
    data object Now : Routes

    @Serializable
    data object Insights : Routes

    @Serializable
    data object History : Routes

    @Serializable
    data object Apps : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data class SessionDetails(val sessionId: String) : Routes

    @Serializable
    data class AppDetails(val uid: Int, val packageName: String) : Routes

    @Serializable
    data object Health : Routes

    @Serializable
    data object SettingsData : Routes

    @Serializable
    data object SettingsStatus : Routes

    /** One Insights finding, by its stable `Finding.key`. */
    @Serializable
    data class FindingDetails(val key: String) : Routes
}

/** The 5 tabs shown in [com.akane.voltwise.ui.screens.MainScreen]'s bar/rail, in display order. */
val TOP_LEVEL_TABS: List<Routes> = listOf(Routes.Now, Routes.Insights, Routes.History, Routes.Apps, Routes.Settings)
