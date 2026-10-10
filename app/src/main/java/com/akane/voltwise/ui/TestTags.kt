package com.akane.voltwise.ui

/**
 * Semantic test tags for the navigation shell ([com.akane.voltwise.ui.screens.MainScreen]). The app
 * root sets `Modifier.semantics { testTagsAsResourceId = true }` so these are also resolvable as
 * resource-ids for UiAutomator-based checks.
 */
object TestTags {
    const val ROOT = "root"
    const val TAB_NOW = "tab_now"
    const val TAB_INSIGHTS = "tab_insights"
    const val TAB_HISTORY = "tab_history"
    const val TAB_APPS = "tab_apps"
    const val TAB_SETTINGS = "tab_settings"
}
