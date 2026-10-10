package com.akane.voltwise.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.mlmgames.settings.core.managers.Migration

/**
 * Settings schema versions. The same rules run on the stored preferences ([steps], registered
 * with kmp-settings' `MigrationManager`) and on older exports ([upgradeExport], used by
 * [SettingsImportPolicy]); kmp-settings itself imports an older export's values unmigrated.
 *
 * kmp-settings 0.8.3 records the version (key [VERSION_KEY]) only after a successful run, and
 * reports a broken chain when the first step starts above the stored version. Its v2 steps
 * started at 1, so fresh v2 installs never recorded one and still read as 0. [UnversionedStep]
 * bridges 0 → 2 so those stores reach the v3 step, and [DynamicColorsStep] only rewrites a store
 * that recorded version 1. The v3 step removes keys, so there is no down-migration.
 */
object SettingsMigrations {
    const val CURRENT_VERSION = 3

    /** kmp-settings' own (internal) version key; read by [DynamicColorsStep] only. */
    internal const val VERSION_KEY = "__schema_version__"

    private const val DYNAMIC_COLORS_KEY = "dynamic_colors"
    private const val AUTO_CLEANUP_KEY = "auto_cleanup_enabled"

    /**
     * Keys removed in v3: both intervals, theme, show-in-mA, chart range, auto-cleanup (folded into
     * retention), alert sound/vibration (the alert channel's system settings own them), and the
     * `@Persisted` fields no code read.
     */
    val V3_REMOVED_KEYS: Set<String> = setOf(
        "monitoring_interval_index", "detailed_stats_interval_index", "theme_index", "show_current_in_ma",
        "chart_time_range_index", AUTO_CLEANUP_KEY, "alert_sound_enabled", "alert_vibration_enabled",
        "total_samples_collected", "show_notification", "show_drain_notification", "notification_style_index",
        "track_foreground_apps", "compact_stats_view", "export_format_index", "export_include_raw_samples",
        "last_data_cleanup", "last_export_time", "first_launch_time", "has_seen_onboarding",
    )

    val steps: List<Migration> = listOf(UnversionedStep, DynamicColorsStep, V3Step)

    /** A store that never recorded a version is a v2 store (or a new one); nothing to change. */
    private object UnversionedStep : Migration {
        override val fromVersion = 0
        override val toVersion = 2
        override suspend fun migrate(prefs: MutablePreferences) = Unit
    }

    /** v1 → 2: dynamic colors off. Only for stores that recorded v1, not for unversioned ones. */
    private object DynamicColorsStep : Migration {
        override val fromVersion = 1
        override val toVersion = 2
        override suspend fun migrate(prefs: MutablePreferences) {
            if (prefs[intPreferencesKey(VERSION_KEY)] == 1) prefs[booleanPreferencesKey(DYNAMIC_COLORS_KEY)] = false
        }
    }

    /** v2 → 3: auto-cleanup off becomes retention Forever; the removed keys are deleted. */
    private object V3Step : Migration {
        override val fromVersion = 2
        override val toVersion = 3
        override suspend fun migrate(prefs: MutablePreferences) {
            if (prefs[booleanPreferencesKey(AUTO_CLEANUP_KEY)] == false) {
                prefs[RETENTION_INDEX] = RETENTION_FOREVER_INDEX
            }
            // Explicitly authorize the default for fresh/legacy stores, never for corruption recovery.
            if (resolveRetention(prefs) is Retention.Days && prefs[RETENTION_INDEX] == null) {
                prefs[RETENTION_INDEX] = AppSettings().dataRetentionIndex
            }
            // Keys compare by name, whatever type a key was stored with.
            prefs.asMap().keys.filter { it.name in V3_REMOVED_KEYS }.forEach { prefs.remove(it) }
        }
    }

    /**
     * An export's settings map (key → kmp-settings encoded value, e.g. `b:false`, `i:5`) from
     * schema [fromVersion], migrated to [CURRENT_VERSION] with the rules of [steps].
     */
    fun upgradeExport(settings: Map<String, String>, fromVersion: Int): Map<String, String> {
        val result = settings.toMutableMap()
        if (fromVersion == 1) result[DYNAMIC_COLORS_KEY] = "b:false"
        if (fromVersion < 3) {
            if (result[AUTO_CLEANUP_KEY] == "b:false") result[RETENTION_INDEX.name] = "i:$RETENTION_FOREVER_INDEX"
            result.keys.removeAll(V3_REMOVED_KEYS)
        }
        return result
    }
}
