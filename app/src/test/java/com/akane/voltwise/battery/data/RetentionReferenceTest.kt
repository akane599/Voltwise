package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.akane.voltwise.battery.data.db.BatteryDao
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.SessionChartReading
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.settings.SettingsMigrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the reference read used by BatteryRepository.start and the real persisted clock seed. */
class RetentionReferenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @After fun close() = scope.cancel()

    private val now = 1_790_000_000_000L
    private val dayMs = 86_400_000L
    private val state = SamplerState(FakeKeyValueStore())

    private suspend fun retention(): HistoryRetention {
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "settings.preferences_pb")
        }
        store.edit { it[intPreferencesKey("data_retention_index")] = 1 }
        val migrator = SettingsMigrator(store)
        migrator.run()
        return HistoryRetention(migrator, store, state) { 1 }
    }

    @Test fun importedOnlyHistoryEstablishesCurrentClockWithoutFirstPurge() = runBlocking {
        val imported = sample(now - 200 * dayMs, "import:backup")
        val dao = SamplesDao(listOf(sample(now - 201 * dayMs, "import:older"), imported))
        val retention = retention()
        assertNull("Fresh device has no stored retention clock", state.retentionClock)
        assertEquals(imported, dao.lastSample())

        val reference = dao.retentionReferenceWallMs()
        val cutoff = retention.cutoff(now, 1_000, reference)

        assertEquals("Imported rows must not seed the stored device clock", now, state.retentionClock!!.wallMs)
        assertNull("Imported-only history has no local reference", reference)
        assertNull("First maintenance establishes authority without an age purge", cutoff)
        assertEquals("Later maintenance must use current time, not a 200-day lag",
            now + 30_000 - 30 * dayMs, retention.cutoff(now + 30_000, 31_000))
    }

    @Test fun recentLocalSampleRemainsTheRetentionReference() = runBlocking {
        val local = sample(now - 60_000, "BatteryManager")
        val dao = SamplesDao(listOf(sample(now - 2 * dayMs, "legacy"), local))
        val retention = retention()

        val reference = dao.retentionReferenceWallMs()
        val cutoff = retention.cutoff(now, 1_000, reference)

        assertEquals(local.timestamp, reference)
        assertEquals(local.timestamp, state.retentionClock!!.wallMs)
        assertEquals(local.timestamp - 30 * dayMs, cutoff)
    }

    @Test fun newerImportedRowCannotReplaceRecentLocalReference() = runBlocking {
        val local = sample(now - 60_000, "BatteryManager")
        val imported = sample(now - 1_000, "import:BatteryManager")
        val dao = SamplesDao(listOf(local, imported))

        assertEquals("All-source lookup remains unchanged for import consumers", imported, dao.lastSample())
        assertEquals("Retention must read the newest local row despite a newer import",
            local.timestamp, dao.retentionReferenceWallMs())
    }

    @Test fun legacyLocalSampleAlsoProvidesTheReference() = runBlocking {
        val legacy = sample(now - 60_000, "legacy")
        val dao = SamplesDao(listOf(legacy, sample(now - 1_000, "import:legacy")))

        assertEquals(legacy.timestamp, dao.retentionReferenceWallMs())
    }

    private fun sample(timestamp: Long, source: String) = BatterySample(
        timestamp = timestamp, levelPercent = 50, status = 3, plugged = null,
        currentNowUa = null, chargeCounterUah = null, voltageMv = null,
        temperatureDeciC = null, health = null, screenOn = false, source = source,
    )

    private class SamplesDao(private val stored: List<BatterySample>) : BatteryDao {
        override suspend fun lastSample(): BatterySample? = stored.maxByOrNull { it.timestamp }
        override suspend fun lastLocalSample(): BatterySample? =
            stored.filterNot { it.source.startsWith("import:") }.maxByOrNull { it.timestamp }
        override suspend fun insertSample(sample: BatterySample): Long = error("Not used")
        override suspend fun byId(id: Long): BatterySample? = error("Not used")
        override suspend fun atTimestamp(timestamp: Long): List<BatterySample> = error("Not used")
        override suspend fun observedPoint(observationId: String, elapsedMs: Long): BatterySample? = error("Not used")
        override suspend fun count(): Int = error("Not used")
        override fun samplesBetween(from: Long, to: Long): Flow<List<BatterySample>> = error("Not used")
        override suspend fun latestSamplesBetween(from: Long, to: Long, limit: Int): List<BatterySample> = error("Not used")
        override fun chartSamples(from: Long, to: Long, bucketMs: Long): Flow<List<BatterySample>> = error("Not used")
        override fun samplesForSession(sessionId: String): Flow<List<BatterySample>> = error("Not used")
        override suspend fun sessionChartSamples(sessionId: String, from: Long, to: Long, bucketMs: Long): List<SessionChartReading> = error("Not used")
        override suspend fun boundStorage(limit: Int) = error("Not used")
        override suspend fun clearAll() = error("Not used")
        override suspend fun purge(olderThan: Long) = error("Not used")
    }
}
