package com.akane.voltwise.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The stored-preferences migration through kmp-settings 0.8.3's own `MigrationManager`, wired by [SettingsMigrator]. */
class SettingsMigrationsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @After fun close() = scope.cancel()

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }

    /** A fresh [SettingsMigrator] per run, as each process start creates one. */
    private suspend fun migrate(store: DataStore<Preferences>): MigrationResult {
        val migrator = SettingsMigrator(store)
        val result = migrator.run()
        val current = result is MigrationResult.Success || result is MigrationResult.NoMigrationNeeded
        assertEquals(current, migrator.awaitMigrated())
        return result
    }

    /** A v2 store as the app left it: auto-cleanup off, the removed keys present, Material You on. */
    private suspend fun seedV2(store: DataStore<Preferences>, recordedVersion: Int?) = store.edit {
        recordedVersion?.let { version -> it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = version }
        it[booleanPreferencesKey("auto_cleanup_enabled")] = false
        it[intPreferencesKey("data_retention_index")] = 1
        it[intPreferencesKey("theme_index")] = 2
        it[intPreferencesKey("monitoring_interval_index")] = 4
        it[booleanPreferencesKey("alert_sound_enabled")] = false
        it[longPreferencesKey("total_samples_collected")] = 1234L
        it[booleanPreferencesKey("dynamic_colors")] = true
        it[intPreferencesKey("low_battery_threshold")] = 15
    }

    private suspend fun storedKeys(store: DataStore<Preferences>) = store.data.first().asMap().keys.map { it.name }.toSet()

    @Test fun unversionedV2StoreReachesV3AndKeepsItsChoices() = runBlocking {
        // Fresh v2 installs never recorded a version: kmp-settings reported a broken chain for them.
        val store = store()
        seedV2(store, recordedVersion = null)
        assertTrue(migrate(store) is MigrationResult.Success)
        val settings = SettingsRepository(store, AppSettingsSchema).flow.first()
        assertEquals(RETENTION_FOREVER_INDEX, settings.dataRetentionIndex)
        assertEquals(Retention.Forever, resolveRetention(store.data.first()))
        assertTrue("Material You is a user choice here, not the v1 default", settings.dynamicColors)
        assertEquals(15, settings.lowBatteryThreshold)
        assertEquals(emptySet<String>(), storedKeys(store) intersect SettingsMigrations.V3_REMOVED_KEYS)
        assertEquals(SettingsMigrations.CURRENT_VERSION, store.data.first()[intPreferencesKey(SettingsMigrations.VERSION_KEY)])
        assertTrue(migrate(store) is MigrationResult.NoMigrationNeeded)
    }

    @Test fun recordedV2StoreMigratesTheSameWay() = runBlocking {
        val store = store()
        seedV2(store, recordedVersion = 2)
        assertTrue(migrate(store) is MigrationResult.Success)
        val settings = SettingsRepository(store, AppSettingsSchema).flow.first()
        assertEquals(RETENTION_FOREVER_INDEX, settings.dataRetentionIndex)
        assertTrue(settings.dynamicColors)
        assertEquals(emptySet<String>(), storedKeys(store) intersect SettingsMigrations.V3_REMOVED_KEYS)
    }

    @Test fun recordedV1StoreStillTurnsDynamicColorsOff() = runBlocking {
        val store = store()
        seedV2(store, recordedVersion = 1)
        assertTrue(migrate(store) is MigrationResult.Success)
        val settings = SettingsRepository(store, AppSettingsSchema).flow.first()
        assertFalse(settings.dynamicColors)
        assertEquals(RETENTION_FOREVER_INDEX, settings.dataRetentionIndex)
    }

    @Test fun autoCleanupOnKeepsTheChosenRetention() = runBlocking {
        val store = store()
        store.edit {
            it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = 2
            it[booleanPreferencesKey("auto_cleanup_enabled")] = true
            it[intPreferencesKey("data_retention_index")] = 1
        }
        assertTrue(migrate(store) is MigrationResult.Success)
        val settings = SettingsRepository(store, AppSettingsSchema).flow.first()
        assertEquals(1, settings.dataRetentionIndex)
        assertEquals(Retention.Days(30), resolveRetention(store.data.first()))
        assertFalse("auto_cleanup_enabled" in storedKeys(store))
    }

    @Test fun newInstallMigratesToDefaults() = runBlocking {
        val store = store()
        assertTrue(migrate(store) is MigrationResult.Success)
        assertEquals(AppSettings(), SettingsRepository(store, AppSettingsSchema).flow.first())
        assertEquals(setOf(SettingsMigrations.VERSION_KEY, "data_retention_index"), storedKeys(store))
    }

    @Test fun exportUpgradeAppliesTheSameRules() {
        val v2 = mapOf("auto_cleanup_enabled" to "b:false", "data_retention_index" to "i:1", "theme_index" to "i:2",
            "dynamic_colors" to "b:true", "low_battery_threshold" to "i:15")
        assertEquals(mapOf("data_retention_index" to "i:$RETENTION_FOREVER_INDEX", "dynamic_colors" to "b:true",
            "low_battery_threshold" to "i:15"), SettingsMigrations.upgradeExport(v2, fromVersion = 2))
        assertEquals("b:false", SettingsMigrations.upgradeExport(v2, fromVersion = 1)["dynamic_colors"])
        val cleanupOn = mapOf("auto_cleanup_enabled" to "b:true", "data_retention_index" to "i:1")
        assertEquals(mapOf("data_retention_index" to "i:1"), SettingsMigrations.upgradeExport(cleanupOn, fromVersion = 2))
        assertEquals(v2, SettingsMigrations.upgradeExport(v2, fromVersion = SettingsMigrations.CURRENT_VERSION))
    }
}
