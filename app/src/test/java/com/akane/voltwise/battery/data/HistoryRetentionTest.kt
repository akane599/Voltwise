package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.settings.AppSettingsSchema
import com.akane.voltwise.settings.SettingsMigrations
import com.akane.voltwise.settings.SettingsMigrator
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The writer's retention cleanup starts with [HistoryRetention.cutoff]: it must wait for the
 * settings migration that `BatteryApp` runs, and then see the v3 retention.
 */
class HistoryRetentionTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @After fun close() = scope.cancel()

    private val localState = FakeKeyValueStore()
    private var bootCount = 1

    private fun retention(migrator: SettingsMigrator, store: DataStore<Preferences>, state: FakeKeyValueStore = localState) =
        HistoryRetention(migrator, store, SamplerState(state)) { bootCount }

    private val now = 1_790_000_000_000L

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }

    /** A fresh v2 install (no recorded version) that keeps 1 month of history. */
    private suspend fun seedV2(store: DataStore<Preferences>, autoCleanup: Boolean) = store.edit {
        it[booleanPreferencesKey("auto_cleanup_enabled")] = autoCleanup
        it[intPreferencesKey("data_retention_index")] = 1
    }

    @Test fun cleanupRequestedBeforeTheMigrationRunsAfterItWithRetentionForever() = runBlocking {
        val store = store()
        seedV2(store, autoCleanup = false)
        val settings = SettingsRepository(store, AppSettingsSchema)
        val migrator = SettingsMigrator(store)
        val retention = retention(migrator, store)

        val cutoff = async { retention.cutoff(now, previousWallMs = now) }
        delay(300)
        assertFalse("Retention must not read settings before the migration has ended", cutoff.isCompleted)
        // Unmigrated, the store still says 1 month: a read at this point would purge.
        assertEquals(1, settings.flow.first().dataRetentionIndex)

        assertTrue(migrator.run() is MigrationResult.Success)
        assertNull("Auto-cleanup off is retention Forever once migrated", withTimeout(10_000) { cutoff.await() })
    }

    @Test fun autoCleanupOnKeepsItsRetentionAfterTheMigration() = runBlocking {
        val store = store()
        seedV2(store, autoCleanup = true)
        val migrator = SettingsMigrator(store)
        val retention = retention(migrator, store)

        val cutoff = async { retention.cutoff(now, previousWallMs = now) }
        delay(100)
        assertFalse(cutoff.isCompleted)
        assertTrue(migrator.run() is MigrationResult.Success)
        assertEquals(now - 30 * 86_400_000L, withTimeout(10_000) { cutoff.await() })
    }

    @Test fun forwardJumpCannotAgeOutRealHistoryEvenAfterRestart() = runBlocking {
        val store = store()
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        val future = now + 365 * 86_400_000L
        val cutoff = retention.cutoff(future, 1_000, now)
        assertEquals("A future wall clock must not advance the purge cutoff", now - 90 * 86_400_000L, cutoff)
        assertTrue("Recent persisted history must survive the jump", now - 30 * 86_400_000L >= cutoff!!)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = com.akane.voltwise.settings.createSettingsDataStore(File(folder.root, "settings.preferences_pb"), reopenedScope)
            val restartedMigrator = SettingsMigrator(reopened)
            restartedMigrator.run()
            val restarted = retention(restartedMigrator, reopened)
            assertEquals("The future sample must not become a trusted reference after reopening the file", cutoff!! + 1_000,
                restarted.cutoff(future, 2_000, future))
        } finally {
            reopenedScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun liveJumpAdvancesOnlyMonotonicTimeAndClockCorrectionRemainsSafe() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        val initial = retention.cutoff(now, 1_000, now)!!
        assertEquals(initial + 30_000, retention.cutoff(now + 365 * 86_400_000L, 31_000))
        assertEquals(initial + 60_000, retention.cutoff(now + 60_000, 61_000))
        assertEquals("A corrected wall clock must cap the cutoff at wall time minus retention",
            now - 30_000 - 90 * 86_400_000L, retention.cutoff(now - 30_000, 91_000))
        assertEquals("A backwards wall clock must not lower the stored trusted clock",
            now + 60_000, SamplerState(localState).retentionClock!!.wallMs)
        assertEquals("Clock correction must preserve the stored trusted clock", initial + 60_000,
            retention.cutoff(now + 60_000, 121_000))
        assertEquals("A zero-uptime reset cannot advance the durable clock", initial + 60_000,
            retention.cutoff(now + 365 * 86_400_000L, 0))
    }

    @Test fun noReferencePausesFirstPurgeAndInvalidStoredChoiceStaysPaused() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        assertNull("No history reference means no first purge", retention.cutoff(now, 1_000))
        assertEquals(now + 30_000 - 90 * 86_400_000L, retention.cutoff(now + 30_000, 31_000))
        store.edit { it.remove(intPreferencesKey("data_retention_index")) }
        assertEquals(now + 60_000 - 90 * 86_400_000L, retention.cutoff(now + 60_000, 61_000))
        store.edit { it[intPreferencesKey("data_retention_index")] = 99 }
        assertNull(retention.cutoff(now + 90_000, 91_000))
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        assertEquals("An explicit choice resumes age maintenance", now + 120_000 - 90 * 86_400_000L,
            retention.cutoff(now + 120_000, 121_000))
    }

    @Test fun invalidRetentionAgreesAcrossMigrationCleanupAndSettings() = runBlocking {
        val store = store()
        val key = intPreferencesKey("data_retention_index")
        store.edit {
            it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = 2
            it[key] = 99
        }
        val field = AppSettingsSchema.fields.first { it.name == "dataRetentionIndex" }
        assertEquals("The schema decoder accepts a stale out-of-range integer", 99, field.decodeValue("i:99"))
        val migrator = SettingsMigrator(store)
        assertTrue(migrator.run() is MigrationResult.Success)
        assertEquals("Migration must not authorize a default for an invalid choice", 99, store.data.first()[key])
        val settings = com.akane.voltwise.viewmodel.KmpSettingsStore(store)
        assertEquals("The DataStore schema reader passes the invalid index through", 99, settings.settings.first().dataRetentionIndex)
        assertNull("An invalid choice pauses age cleanup", retention(migrator, store).cutoff(now, 1_000, now))
        assertTrue("Settings must show not set whenever the invalid choice pauses cleanup", settings.retentionUnset.first())
        settings.set("dataRetentionIndex", 1)
        assertFalse("An explicit valid choice ends the not-set state", settings.retentionUnset.first())
        assertEquals("The same choice resumes cleanup", now - 30 * 86_400_000L,
            retention(migrator, store).cutoff(now, 1_000, now))
    }

    @Test fun v3MissingRetentionStillUsesNinetyDays() = runBlocking {
        val store = store()
        store.edit { it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = 3 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        assertEquals("Existing v3 without a choice must retain the 90-day default", now - 90 * 86_400_000L,
            retention(migrator, store).cutoff(now, 1_000, now))
    }

    @Test fun backwardsWallClockNeverLowersWatermark() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        val initial = retention.cutoff(now, 1_000, now)
        assertEquals("An RTC fallback must not purge rows stamped at the fallback time",
            -90 * 86_400_000L, retention.cutoff(0, 31_000))
        assertEquals("A backwards RTC must not lower the persisted watermark", now,
            SamplerState(localState).retentionClock!!.wallMs)
        assertEquals("The watermark must remain trusted after the RTC is corrected", initial,
            retention.cutoff(now, 61_000))
    }

    @Test fun futureSeedWithoutHistoryCapsCutoffAfterClockCorrection() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        val future = now + 365 * 86_400_000L
        assertNull(retention.cutoff(future, 1_000))
        val correctedNow = now + 86_400_000L
        val cutoff = retention.cutoff(correctedNow, 61_000)!!
        assertTrue("A future seed must not purge history newer than 90 days at corrected wall time",
            cutoff <= correctedNow - 90 * 86_400_000L)
        assertEquals("Clock correction must not regress the stored future seed", future,
            SamplerState(localState).retentionClock!!.wallMs)
    }

    @Test fun restartWithinSameBootCountsElapsedTime() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val initial = retention(migrator, store).cutoff(now, 1_000, now)!!
        val restarted = retention(migrator, store)
        assertEquals("Same-boot restart must retain elapsed time since the last cleanup", initial + 60_000,
            restarted.cutoff(now + 60_000, 61_000, now))
    }

    @Test fun rebootCountsCurrentUptimeWithoutCountingPreviousBootUptime() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val initial = retention(migrator, store).cutoff(now, 1_000, now)!!
        bootCount++
        val restarted = retention(migrator, store)
        assertEquals("A new boot must advance by its full current uptime", initial + 61_000,
            restarted.cutoff(now + 365 * 86_400_000L, 61_000))
        assertEquals("Later cleanup must count only the new boot's additional elapsed time", initial + 62_000,
            restarted.cutoff(now + 365 * 86_400_000L, 62_000))
    }

    @Test fun backwardsElapsedWithUnavailableBootCountCountsCurrentUptime() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        bootCount = -1
        val initial = retention(migrator, store).cutoff(now, 61_000, now)!!
        assertEquals("An elapsed reset with unknown boot count must count current uptime", initial + 1_000,
            retention(migrator, store).cutoff(now + 60_000, 1_000))
    }

    @Test fun dailyRebootsWithOneCleanupPerBootEventuallyExpireNinetyDayHistory() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val initial = retention(migrator, store).cutoff(now, 1_000, now)!!
        val uptime = 23 * 3_600_000L
        var cutoff = initial
        repeat(100) { index ->
            bootCount++
            cutoff = retention(migrator, store).cutoff(now + (index + 1) * 86_400_000L, uptime)!!
            assertEquals("One cleanup per daily boot must accumulate each boot's uptime", initial + (index + 1) * uptime,
                cutoff)
        }
        assertTrue("Daily reboots must eventually make the original row eligible for the 90-day purge", now < cutoff)
        assertTrue("Time outside the observed boot uptime must not advance the trusted clock",
            cutoff < now + 10 * 86_400_000L)
    }

    @Test fun repeatedShortMonitoringRunsAccumulateSameBootTime() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        val initial = retention(migrator, store).cutoff(now, 1_000, now)!!
        repeat(10) { index ->
            val elapsed = (index + 1) * 60_000L
            assertEquals("Short runs must accumulate rather than reset the retention clock", initial + elapsed,
                retention(migrator, store).cutoff(now + elapsed, 1_000 + elapsed, now))
        }
    }

    @Test fun restoringSettingsWithoutHistoryDoesNotRestoreWatermark() = runBlocking {
        val store = store()
        val migrator = SettingsMigrator(store)
        migrator.run()
        retention(migrator, store).cutoff(now, 1_000, now)
        // A backup made by the rejected implementation may still carry this obsolete key.
        store.edit { it[androidx.datastore.preferences.core.longPreferencesKey("__history_retention_now_ms")] = now }
        val restored = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "restored.preferences_pb") }
        restored.updateData { store.data.first() }
        val restoredMigrator = SettingsMigrator(restored)
        restoredMigrator.run()
        val retention = retention(restoredMigrator, restored, FakeKeyValueStore())
        val restoredNow = now + 180 * 86_400_000L
        assertNull("Settings-only restore must have no history clock reference",
            retention.cutoff(restoredNow, 1_000))
        assertEquals("Restored device must seed its own current clock", restoredNow + 1_000 - 90 * 86_400_000L,
            retention.cutoff(restoredNow + 1_000, 2_000))
    }

    @Test fun normalClockWithStoredThreeMonthsExpiresOnlyOlderRows() = runBlocking {
        val store = store()
        store.edit { it[intPreferencesKey("data_retention_index")] = 2 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        val retention = retention(migrator, store)
        val cutoff = retention.cutoff(now, 1_000, now)!!
        assertEquals(now - 90 * 86_400_000L, cutoff)
        assertTrue("Rows older than three months are eligible for purge", now - 91 * 86_400_000L < cutoff)
        assertTrue("The cutoff boundary remains retained", now - 90 * 86_400_000L >= cutoff)
    }

    @Test fun corruptStorePausesAgeRetentionWhileFreshInstallKeepsNinetyDays() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01))
        val corrupt = com.akane.voltwise.settings.createSettingsDataStore(file, scope)
        val migrator = SettingsMigrator(corrupt)
        migrator.run()
        val retention = retention(migrator, corrupt)
        assertNull("Corruption recovery must not silently impose 90-day retention", retention.cutoff(now, previousWallMs = now))
        val fresh = store()
        val freshMigrator = SettingsMigrator(fresh)
        freshMigrator.run()
        val defaults = retention(freshMigrator, fresh)
        assertEquals("A genuine fresh store must explicitly retain the 90-day default", now - 90 * 86_400_000L,
            defaults.cutoff(now, previousWallMs = now))
    }

    @Test fun migrationThatDoesNotReachTheCurrentSchemaPausesRetention() = runBlocking {
        val store = store()
        // A store from a newer app version: kmp-settings refuses to downgrade it.
        store.edit { it[intPreferencesKey(SettingsMigrations.VERSION_KEY)] = SettingsMigrations.CURRENT_VERSION + 1 }
        val migrator = SettingsMigrator(store)
        val retention = retention(migrator, store)

        assertTrue(migrator.run() is MigrationResult.DowngradeDetected)
        assertFalse(migrator.awaitMigrated())
        assertTrue(runCatching { retention.cutoff(now, previousWallMs = now) }.exceptionOrNull() is IllegalStateException)
    }
}
