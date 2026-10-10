package com.akane.voltwise.battery.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.DailySummaryReplay
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.data.sampling.SamplingController
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.AppSettingsSchema
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.test.DeviceEnvironment
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Injected broadcasts/errors exercise recovery; they are not physical battery measurements. */
@RunWith(AndroidJUnit4::class)
class RepositoryRecoveryTest {
    internal class ReadingContext(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "battery-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        @Volatile var missingBattery = false
        @Volatile var throwOnBattery = false
        @Volatile var rejectEvents = false
        @Volatile var charging = false
        @Volatile var registeredReceiver: BroadcastReceiver? = null
        override fun getNoBackupFilesDir(): File = directory
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            if (receiver != null) return super.registerReceiver(receiver, filter)
            if (throwOnBattery) throw SecurityException("Injected read failure")
            if (missingBattery) return null
            return Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 80).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS,
                    if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, if (charging) BatteryManager.BATTERY_PLUGGED_AC else 0)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 250).putExtra(BatteryManager.EXTRA_HEALTH, 2)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? {
            return registerReceiver(receiver, filter, null, null, flags)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? {
            // ContextCompat on API33+ uses this five-argument overload.
            if (receiver != null && rejectEvents) throw SecurityException("Injected subscription failure")
            // Script registration as well, so real sticky broadcasts cannot mask a failed poll.
            registeredReceiver = receiver
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver?) { registeredReceiver = null }
    }

    /** Calibration and sampler state for one fixture, never the app's own preference files. */
    internal class MemoryStore : KeyValueStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun edit(values: Map<String, String?>) =
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
    }

    internal class Fixture {
        val context = ReadingContext(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.directory, "test.preferences_pb")
        }
        val settings = SettingsRepository<AppSettings>(dataStore = dataStore, schema = AppSettingsSchema)
        val diagnostics = DiagnosticStore(context, scope)
        val sampler = SamplingController(context, diagnostics)
        val calibration = CalibrationStore(MemoryStore(), flowOf(CalibrationOverrides()), scope)
        // As BatteryApp does: retention cleanup waits for this migration.
        private val migrator = SettingsMigrator(dataStore).also { scope.launch { it.run() } }
        val repository = BatteryRepository(database, settings, scope, HistoryMaintenance(), diagnostics, sampler,
            calibration, HistoryRetention(migrator, dataStore, com.akane.voltwise.battery.data.sampling.SamplerState(MemoryStore())) { 1 }, MemoryStore())
        suspend fun refresh() = withTimeout(60_000) {
            val result = CompletableDeferred<BatteryRepository.Realtime>()
            repository.refreshNow { result.complete(it) }
            result.await()
        }
        suspend fun close() {
            withContext(Dispatchers.Main.immediate) { repository.stopSampling() }
            sampler.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            context.directory.deleteRecursively()
        }
    }

    @Test fun stoppedRefreshRecoversFromMissingReadingWithoutStartingMonitoring(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.context.missingBattery = true
            assertNull(fixture.refresh().sample)
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("not supplied") == true } }
            fixture.context.missingBattery = false
            assertEquals(80, fixture.refresh().level)
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
            assertFalse(fixture.repository.isMonitoringFlow.value)
            assertNull(fixture.repository.observation.value.startedAt)
            assertEquals(0, fixture.database.batteryDao().count())
        } finally { fixture.close() }
    }

    @Test fun failedSubscriptionKeepsSnapshotsButCannotInventObservedPeriods(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.context.rejectEvents = true
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("State events") == true } }
            assertEquals(80, fixture.refresh().level)
            assertEquals(80, fixture.refresh().level)
            assertTrue(fixture.repository.error.value!!.contains("State events"))
            assertNull(fixture.repository.observation.value.startedAt)
            assertEquals(0L, fixture.repository.observation.value.screenOff.durationMs)
            assertEquals(0, fixture.database.batteryDao().count())
            assertEquals(0, fixture.database.sessionDao().filteredSessions(null, "", 10).first().size)
            // Restarting monitoring retries registration; an ordinary refresh must not hide its failure.
            fixture.repository.stopSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { !it } }
            fixture.context.rejectEvents = false
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
        } finally { fixture.close() }
    }

    @Test fun automaticAndBroadcastReadExceptionsAreRecoverable(): Unit = runBlocking {
        val fixture = Fixture()
        // A visible surface's demand token: 2 s polls instead of 30 s/300 s.
        val demand = fixture.sampler.acquire("RepositoryRecoveryTest")
        try {
            // Force the first automatic poll to fail; the sampler thread must survive.
            fixture.context.throwOnBattery = true
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.diagnostics.events.first { events -> events.any { it.code == DiagnosticCode.BATTERY_READ_FAILED } } }
            assertTrue(fixture.repository.isMonitoringFlow.value)
            fixture.context.throwOnBattery = false
            // The next scheduled poll must recover without a manual refresh or service restart.
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            val previous = fixture.repository.observation.value.latest!!.elapsedMs
            fixture.context.throwOnBattery = true
            withContext(Dispatchers.Main) {
                fixture.context.registeredReceiver!!.onReceive(fixture.context, Intent(Intent.ACTION_SCREEN_OFF))
            }
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("SecurityException") == true } }
            fixture.context.throwOnBattery = false
            fixture.refresh()
            withTimeout(60_000) { fixture.repository.observation.first { (it.latest?.elapsedMs ?: 0) > previous && it.gaps > 0 } }
            withTimeout(60_000) { fixture.repository.error.first { it == null } }
            assertTrue(fixture.repository.isMonitoringFlow.value)
        } finally { demand.close(); fixture.close() }
    }

    @Test fun demandHandoffsStampTheLongerIntervalAndKeepOnePollChain(): Unit = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository
        var phase = "screen-on monitoring"
        try {
            DeviceEnvironment.device.wakeUp()
            withTimeout(60_000) { while (!fixture.context.getSystemService(PowerManager::class.java).isInteractive) delay(100) }
            repository.startSampling()
            withTimeout(60_000) { repository.observation.first { it.startedAt != null } }

            phase = "acquire then release while monitoring"
            val demand = fixture.sampler.acquire("RepositoryRecoveryTest")
            withTimeout(60_000) { repository.observation.first { it.latest?.expectedIntervalMs == 2_000L } }
            val gaps = repository.observation.value.gaps
            demand.close()
            // The release handoff capture carries the 30 s stamp, so the wait that follows is no gap.
            val handoff = withTimeout(60_000) { repository.observation.first { it.latest?.expectedIntervalMs == 30_000L } }.latest!!
            assertEquals(gaps, repository.observation.value.gaps)
            phase = "first 30 s poll after the handoff"
            withTimeout(60_000) { repository.observation.first { (it.latest?.elapsedMs ?: 0) > handoff.elapsedMs } }
            assertEquals("A 30 s wait after a 2 s cadence is not a gap", gaps, repository.observation.value.gaps)

            phase = "token churn"
            repeat(5) { fixture.sampler.acquire("churn-$it").close() }
            val held = fixture.sampler.acquire("RepositoryRecoveryTest")
            try {
                delay(1_000) // The acquire handoff capture lands first.
                val windowStart = SystemClock.elapsedRealtime()
                val polls = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
                val collector = launch(Dispatchers.Default) {
                    repository.realtimeFlow.collect { reading ->
                        reading.sample?.elapsedMs?.takeIf { it > windowStart }?.let(polls::add)
                    }
                }
                delay(6_000)
                collector.cancelAndJoin()
                // One 2 s chain gives 3 polls in 6 s; duplicate chains from the churn would give 6 or more.
                assertTrue("Polls in 6 s: ${polls.size}", polls.size in 2..4)
            } finally { held.close() }
        } catch (failure: Throwable) {
            throw AssertionError("Failed during $phase; observation=${repository.observation.value}; " +
                "errors=${repository.error.value}", failure)
        } finally { fixture.close() }
    }

    @Test fun demandPollsStayRealtimeOnlyAndSavesFollowPersistPolicy(): Unit = runBlocking {
        val fixture = Fixture()
        val demand = fixture.sampler.acquire("RepositoryRecoveryTest")
        val repository = fixture.repository
        var phase = "monitoring off"
        try {
            // Monitoring off: demand polls refresh realtime values but never reach the observation or history.
            val first = withTimeout(60_000) { repository.realtimeFlow.first { it.sample != null } }.sample!!
            withTimeout(60_000) { repository.realtimeFlow.first { (it.sample?.elapsedMs ?: 0) > first.elapsedMs!! } }
            assertEquals(80, repository.readOnce().level)
            assertNull(repository.observation.value.startedAt)
            assertEquals(0, fixture.database.batteryDao().count())

            phase = "screen-on monitoring"
            DeviceEnvironment.device.wakeUp()
            withTimeout(60_000) { while (!fixture.context.getSystemService(PowerManager::class.java).isInteractive) delay(100) }
            val saved = async(start = CoroutineStart.UNDISPATCHED) { repository.persisted.first() }
            repository.startSampling()
            val row = withTimeout(60_000) { saved.await() }
            assertTrue(row.id > 0)
            // Several 2 s polls later, an unchanged screen-on reading is still one row (the next is due at 30 s).
            withTimeout(60_000) { repository.observation.first { it.screenOn.durationMs >= 6_000 } }
            assertEquals(1, fixture.database.batteryDao().count())
            val session = fixture.database.sessionDao().byId(row.sessionId!!)!!
            assertEquals(SessionType.DISCHARGE, session.type)
            assertEquals(1, session.activeKey)
            val today = DailySummaryAggregator.epochDay(row.timestamp, ZoneId.systemDefault())
            assertEquals(80, fixture.database.dailySummaryDao().byDay(today)?.minLevel)

            phase = "plug-in transition"
            val transition = async(start = CoroutineStart.UNDISPATCHED) { repository.powerTransitions.first() }
            fixture.context.charging = true
            withContext(Dispatchers.Main) {
                fixture.context.registeredReceiver!!.onReceive(fixture.context, Intent(Intent.ACTION_BATTERY_CHANGED))
            }
            val plugIn = withTimeout(60_000) { transition.await() }
            assertEquals(PowerState.DISCHARGING, plugIn.from)
            assertEquals(PowerState.CHARGING, plugIn.to)
            assertEquals(session.sessionId, plugIn.endedSessionId)
            // Both session rows committed before the transition was emitted.
            val ended = fixture.database.sessionDao().byId(plugIn.endedSessionId!!)!!
            assertNull(ended.activeKey)
            assertNotNull(ended.endTime)
            val started = fixture.database.sessionDao().byId(plugIn.startedSessionId!!)!!
            assertEquals(SessionType.CHARGE, started.type)
            assertEquals(1, started.activeKey)
            assertEquals("AC", started.chargerType)
            assertTrue(fixture.database.batteryDao().count() >= 2)
        } catch (failure: Throwable) {
            throw AssertionError("Failed during $phase; observation=${repository.observation.value}; " +
                "errors=${repository.error.value}", failure)
        } finally { demand.close(); fixture.close() }
    }

    @Test fun failedStorageRestartCannotReuseThePreviousObservationAndOrdinaryReadsStayAvailable(): Unit = runBlocking {
        val fixture = Fixture()
        var phase = "initial observation"
        try {
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            fixture.repository.stopSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { !it } }
            phase = "restart with closed storage"
            fixture.database.close() // Deliberate storage failure, isolated from the app's normal database.
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.isMonitoringFlow.first { it } }
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt == null } }
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("History collection failed") == true } }
            phase = "ordinary read failure and recovery"
            fixture.context.missingBattery = true
            fixture.refresh()
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("not supplied") == true } }
            fixture.context.missingBattery = false
            assertEquals(80, fixture.refresh().level)
            withTimeout(60_000) { fixture.repository.error.first { it != null && !it.contains("not supplied") } }
            assertTrue(fixture.repository.error.value!!.contains("History collection failed"))
            assertNull(fixture.repository.observation.value.latest)
        } catch (failure: Throwable) {
            throw AssertionError("Failed during $phase; observation=${fixture.repository.observation.value}; " +
                "monitoring=${fixture.repository.isMonitoringFlow.value}; errors=${fixture.repository.error.value}", failure)
        } finally { fixture.close() }
    }

    @Test fun dailySummaryBackfillRunsAtAppStartWithMonitoringOff(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            // An upgrade: this app's own stored samples, no daily rows yet, monitoring never started.
            val start = System.currentTimeMillis() - 3_600_000
            val generation = UUID.randomUUID().toString()
            repeat(5) { i ->
                fixture.database.batteryDao().insertSample(BatterySample(timestamp = start + i * 60_000L,
                    levelPercent = 80 - i, status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0,
                    currentNowUa = -500_000, chargeCounterUah = 3_000_000L - i * 8_000, voltageMv = 4000,
                    temperatureDeciC = 250, health = 2, screenOn = true, elapsedMs = 1_000_000L + i * 60_000,
                    uptimeMs = 1_000_000L + i * 60_000, observationId = generation, source = DailySummaryReplay.SAMPLE_SOURCE))
            }
            assertEquals(0, fixture.database.dailySummaryDao().count())
            fixture.repository.backfillDailySummariesOnce()
            withTimeout(60_000) { while (fixture.database.dailySummaryDao().count() == 0) delay(50) }
            assertFalse(fixture.repository.isMonitoringFlow.value)
        } finally { fixture.close() }
    }

    @Test fun storageLossCannotKeepAStoppedOrResetObservationRunning(): Unit = runBlocking {
        val fixture = Fixture()
        var phase = "initial discharge session"
        try {
            fixture.repository.startSampling()
            // Observation is published before persistence; wait for a committed open session too.
            withTimeout(60_000) { fixture.repository.activeSessionFlow.first { it?.type == SessionType.DISCHARGE } }
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            fixture.database.close()
            phase = "stop with closed storage"
            fixture.repository.stopSampling()
            val stopped = withTimeout(60_000) { fixture.repository.observation.first { it.stopped } }
            assertFalse(fixture.repository.isMonitoringFlow.value)
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("History collection failed") == true } }
            phase = "stale reset after stop"
            // 3bfba51 / PROGRESS.md: Reset applies only to an open DISCHARGE session.
            // Stop has already detached it, even when its storage close failed: keep the stopped result.
            fixture.repository.resetObservation()
            assertEquals(80, fixture.refresh().level)
            assertEquals("A stale Reset must retain the stopped observation", stopped, fixture.repository.observation.value)
            assertFalse(fixture.repository.isMonitoringFlow.value)
        } catch (failure: Throwable) {
            throw AssertionError("Failed during $phase; observation=${fixture.repository.observation.value}; " +
                "monitoring=${fixture.repository.isMonitoringFlow.value}; errors=${fixture.repository.error.value}", failure)
        } finally { fixture.close() }
    }

    @Test fun storageLossCannotKeepAnActiveDischargeObservationAfterReset(): Unit = runBlocking {
        val fixture = Fixture()
        var phase = "initial discharge session"
        try {
            fixture.repository.startSampling()
            withTimeout(60_000) { fixture.repository.activeSessionFlow.first { it?.type == SessionType.DISCHARGE } }
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt != null } }
            fixture.database.close()
            phase = "active reset with closed storage"
            fixture.repository.resetObservation()
            withTimeout(60_000) { fixture.repository.observation.first { it.startedAt == null } }
            withTimeout(60_000) { fixture.repository.error.first { it?.contains("History collection failed") == true } }
            assertEquals(0L, fixture.repository.observation.value.observedMs)
            assertNull(fixture.repository.observation.value.latest)
            // Reset does not stop monitoring, but failed storage cannot resurrect the old window.
            assertTrue(fixture.repository.isMonitoringFlow.value)
            phase = "ordinary refresh after failed reset"
            assertEquals(80, fixture.refresh().level)
            assertNull(fixture.repository.observation.value.latest)
        } catch (failure: Throwable) {
            throw AssertionError("Failed during $phase; observation=${fixture.repository.observation.value}; " +
                "monitoring=${fixture.repository.isMonitoringFlow.value}; errors=${fixture.repository.error.value}", failure)
        } finally { fixture.close() }
    }
}
