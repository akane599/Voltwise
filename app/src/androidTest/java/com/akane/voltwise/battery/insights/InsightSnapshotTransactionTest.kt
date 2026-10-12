package com.akane.voltwise.battery.insights

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.model.FindingType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class InsightSnapshotTransactionTest {
    @Test fun retentionCannotSplitHeadersFromAppRows(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BatteryDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val headersRead = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val now = 100L * 24 * 3_600_000
        val hour = 3_600_000L
        val sessions = database.sessionDao()
        val apps = database.appUsageDao()
        try {
            for (days in listOf(11, 10, 9, 8, 1)) {
                val end = now - days * 24 * hour
                val id = "day$days"
                sessions.insert(ChargeSession(id, SessionType.DISCHARGE, end - hour, end, 80, 75,
                    100_000, null, null, observationId = "observation", source = "local",
                    observedMs = hour, screenOffMs = hour, appUsageStatus = AppUsageStatus.READY,
                    appUsageBasis = AppUsageBasis.DELTA, appCaptureStartMs = end - hour, appCaptureEndMs = end))
                apps.insertSessionUsage(listOf(SessionAppUsage(id, 0, 10_001, "example.app", 40.0,
                    basis = AppUsageBasis.DELTA, backgroundTimeMs = hour, foregroundTimeMs = 0, topMs = 0)))
            }
            val coordinatedSessions = object : SessionDao by sessions {
                override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> {
                    val rows = sessions.closedSessionsBetween(from, to)
                    assertTrue("all input reads must use Room's transaction", database.inTransaction())
                    headersRead.complete(Unit)
                    cleanupStarted.await()
                    return rows
                }
            }
            val store = object : KeyValueStore {
                private val values = mutableMapOf<String, String>()
                override fun getString(key: String) = values[key]
                override fun edit(values: Map<String, String?>) {
                    values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
                }
            }
            val repo = InsightRepository(coordinatedSessions, database.dailySummaryDao(), apps, database.insightDao(),
                scope, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), { emptySet() }, store,
                HistoryMaintenance(), capacityReading = { 2_000_000L to 50 },
                readTransaction = { block -> database.withTransaction { block() } },
                analyze = {
                    assertEquals(5, it.sessions.size)
                    assertEquals(5, it.appSessions.size)
                    InsightEngine.analyze(it, 28)
                })
            withTimeout(30_000) {
                val cleanup = async(Dispatchers.IO) {
                    headersRead.await()
                    cleanupStarted.complete(Unit)
                    database.withTransaction { sessions.purge(now - 7 * 24 * hour) }
                }
                repo.refresh()
                cleanup.await()
            }
            assertEquals(1, sessions.count())
            assertEquals(1, apps.usageRowsForSessions(listOf("day11", "day10", "day9", "day8", "day1")).size)
            assertFalse(repo.report.value!!.findings.any { it.type == FindingType.NEW_HEAVY_APP })
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
        }
    }
}
