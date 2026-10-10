package com.akane.voltwise.battery.apps

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.data.db.AppSnapshotKind
import com.akane.voltwise.battery.data.db.AppSnapshot
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionDeviceWaker
import com.akane.voltwise.battery.data.db.SessionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The Room side of the per-app pipeline: A3's one-baseline, row-must-exist, unique-uid and atomic-end rules. */
@RunWith(AndroidJUnit4::class)
class RoomSessionSnapshotStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    private val store = RoomSessionSnapshotStore(db)

    @After fun close() = db.close()

    private fun session(id: String, type: SessionType = SessionType.DISCHARGE, open: Boolean = false,
                        status: AppUsageStatus? = AppUsageStatus.PENDING, start: Long = 1_000) =
        ChargeSession(id, type, start, if (open) null else start + 500, 80, null, null, null, null,
            lastSampleTime = start + 500, appUsageStatus = status)

    private fun snapshot(window: Long, capturedAt: Long, vararg rows: AppUsageRow) = AppUsageSnapshot(window, 3, capturedAt, rows.toList())

    @Test fun oneBaselinePerExistingSessionWithSharedUidsCollapsed() = runBlocking {
        assertFalse("No row yet: orphan pruning would delete it", store.saveBaseline("B", snapshot(100, 1, AppUsageRow(1, "a", 1.0))))
        db.sessionDao().insert(session("B", open = true))
        val first = snapshot(100, 2, AppUsageRow(1, "a", 1.0), AppUsageRow(1, "a2", 0.5), AppUsageRow(2, "b", 2.0))
        assertTrue(store.saveBaseline("B", first))
        assertFalse(store.saveBaseline("B", snapshot(100, 3, AppUsageRow(1, "a", 9.0))))
        val stored = store.baseline("B")!!
        assertEquals(listOf(1 to 1.5, 2 to 2.0), stored.rows.map { it.uid to it.powerMah })
        assertEquals(100L, stored.windowStartedAt)
        assertTrue(store.hasBaseline("B"))
        assertEquals(1, db.appUsageDao().snapshots().count { it.kind == AppSnapshotKind.BASELINE })
    }

    @Test fun endWritesSnapshotBreakdownAndStatusTogether() = runBlocking {
        db.sessionDao().insert(session("A"))
        val end = snapshot(100, 5, AppUsageRow(1, "a", 3.0))
        val result = AppUsageDelta.compute(null, end)
        assertTrue(store.saveEnd("A", end, result))
        val row = db.sessionDao().byId("A")!!
        assertEquals(AppUsageStatus.READY, row.appUsageStatus)
        assertEquals(AppUsageBasis.ABSOLUTE, row.appUsageBasis)
        assertEquals(listOf("a"), db.appUsageDao().sessionUsage("A").first().map { it.packageName })
        assertNotNull(db.appUsageDao().latestSnapshot("A", AppSnapshotKind.END))
        assertFalse("A deleted session is skipped", store.saveEnd("gone", end, result))
    }

    @Test fun failedSessionWakerInsertRollsBackEndIncludingPrunedSnapshotChildren() = runBlocking {
        val usage = db.appUsageDao()
        val oldSession = session("rollback", status = AppUsageStatus.FAILED).copy(
            appUsageBasis = AppUsageBasis.WINDOW_RESET,
            appCaptureStartMs = 1_200,
            appCaptureEndMs = 1_800,
        )
        db.sessionDao().insert(oldSession)
        val oldUsage = listOf(
            SessionAppUsage("rollback", 0, 11, "old.app", 7.0,
                cpuTimeMs = 80, basis = AppUsageBasis.WINDOW_RESET, wakeupAlarms = 4,
                topAlarmTag = "old-alarm"),
        )
        usage.insertSessionUsage(oldUsage)
        val oldWakers = listOf(
            SessionDeviceWaker("rollback", "KERNEL_WAKELOCK", "old-lock", 3, 90, 0),
            SessionDeviceWaker("rollback", "WAKEUP_REASON", "old-reason", 2, 20, 1),
        )
        usage.insertSessionWakers("rollback", oldWakers)

        val baseline = snapshot(100, 2_000, AppUsageRow(21, "new.app", 1.0)).copy(
            deviceWakers = listOf(DeviceWaker("WAKEUP_REASON", "new-alarm", 1, 10)),
            wakersComplete = true,
        )
        val oldestId = usage.insertSnapshot(
            AppSnapshot(sessionId = "rollback", kind = AppSnapshotKind.BASELINE,
                capturedAt = 2_000, windowStartedAt = 100, windowStartCount = 3, wakersComplete = true),
            baseline.rows, baseline.deviceWakers,
        )
        repeat(2) { i ->
            val sessionId = "seed$i"
            db.sessionDao().insert(session(sessionId))
            usage.insertSnapshot(
                AppSnapshot(sessionId = sessionId, kind = AppSnapshotKind.END,
                    capturedAt = 3_000L + i, windowStartedAt = 100, windowStartCount = 3,
                    deepIdleMs = 40L + i, wakersComplete = true),
                listOf(AppUsageRow(31 + i, "seed.app$i", 2.0 + i, cpuTimeMs = 60L + i)),
                listOf(DeviceWaker("KERNEL_WAKELOCK", "seed-lock$i", 2L + i, 30L + i)),
            )
        }

        val sessionBefore = db.sessionDao().byId("rollback")!!
        val usageBefore = usage.sessionUsage("rollback").first()
        val wakersBefore = usage.sessionWakers(listOf("rollback"))
        val headersBefore = usage.snapshots()
        val uidsBefore = headersBefore.associate { it.id to usage.snapshotUids(it.id) }
        val snapshotWakersBefore = headersBefore.associate { it.id to usage.snapshotWakers(it.id) }
        assertEquals(oldSession, sessionBefore)
        assertEquals(oldUsage, usageBefore)
        assertEquals(oldWakers, wakersBefore)
        assertEquals(3, headersBefore.size)
        assertEquals("The fourth insert must prune this closed session's baseline", oldestId, headersBefore.minOf { it.id })
        assertTrue(uidsBefore.values.all { it.isNotEmpty() })
        assertTrue(snapshotWakersBefore.values.all { it.isNotEmpty() })

        val end = snapshot(100, 5_000, AppUsageRow(21, "new.app", 5.0, cpuTimeMs = 200)).copy(
            deviceWakers = listOf(DeviceWaker("WAKEUP_REASON", "new-alarm", 6, 70)),
            wakersComplete = true,
        )
        val validResult = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, validResult.basis)
        assertEquals(2_000L, validResult.captureStartMs)
        assertEquals(listOf(DeviceWaker("WAKEUP_REASON", "new-alarm", 5, 60)), validResult.deviceWakers)
        // Only the final session-waker replacement is invalid; snapshot wakers stay valid.
        val invalidResult = validResult.copy(deviceWakers = validResult.deviceWakers + validResult.deviceWakers)
        try {
            store.saveEnd("rollback", end, invalidResult)
            fail("Duplicate session-waker keys must abort END persistence")
        } catch (error: SQLiteConstraintException) {
            assertTrue("Failure must come from the final session-waker insert: ${error.message}",
                error.message.orEmpty().contains("session_device_wakers"))
        }

        // Removing the public constructor's outer withTransaction would commit the earlier
        // snapshot/pruning, capture and READY writes despite the final DAO transaction failing.
        assertEquals("All session fields, including status/basis/capture, must roll back",
            sessionBefore, db.sessionDao().byId("rollback"))
        assertEquals(usageBefore, usage.sessionUsage("rollback").first())
        assertEquals(wakersBefore, usage.sessionWakers(listOf("rollback")))
        assertEquals("No new END or pruned header may survive", headersBefore, usage.snapshots())
        assertEquals("Every original snapshot UID set, including the pruned baseline, must survive",
            uidsBefore, headersBefore.associate { it.id to usage.snapshotUids(it.id) })
        assertEquals("Every original snapshot waker set, including the pruned baseline, must survive",
            snapshotWakersBefore, headersBefore.associate { it.id to usage.snapshotWakers(it.id) })
    }

    @Test fun pendingClosedDischargesSkipsOpenReadyLegacyAndChargingSessions() = runBlocking {
        db.sessionDao().insert(session("closed-pending", start = 1_000))
        db.sessionDao().insert(session("closed-ready", status = AppUsageStatus.READY, start = 2_000))
        db.sessionDao().insert(session("legacy", status = null, start = 3_000))
        db.sessionDao().insert(session("charge", SessionType.CHARGE, status = AppUsageStatus.NOT_APPLICABLE, start = 4_000))
        db.sessionDao().insert(session("open", open = true, start = 5_000))
        assertEquals(listOf("closed-pending"), store.pendingClosedDischarges())
        store.setStatus("closed-pending", AppUsageStatus.FAILED)
        assertEquals(AppUsageStatus.FAILED, db.sessionDao().byId("closed-pending")!!.appUsageStatus)
        assertTrue(store.pendingClosedDischarges().isEmpty())
        assertEquals(OpenSession("open", SessionType.DISCHARGE), store.openSession().first())
    }

    @Test fun backwardClockEndRetainsSnapshotWakersAndReadyBreakdown() = runBlocking {
        repeat(3) { i ->
            db.sessionDao().insert(session("seed$i"))
            val seed = snapshot(100, 10_000L + i, AppUsageRow(1, "a", 2.0))
            assertTrue(store.saveEnd("seed$i", seed, AppUsageDelta.compute(null, seed)))
        }
        db.sessionDao().insert(session("backward"))
        val waker = DeviceWaker("WAKEUP_REASON", "alarm", 2, 20)
        val end = snapshot(100, 500, AppUsageRow(1, "a", 3.0)).copy(
            deviceWakers = listOf(waker), wakersComplete = true,
        )
        assertTrue(store.saveEnd("backward", end, AppUsageDelta.compute(null, end)))
        val header = db.appUsageDao().latestSnapshot("backward", AppSnapshotKind.END)!!
        assertEquals(500L, header.capturedAt)
        assertEquals(listOf(1), db.appUsageDao().snapshotUids(header.id).map { it.uid })
        assertEquals(listOf(waker.name), db.appUsageDao().snapshotWakers(header.id).map { it.name })
        assertEquals(AppUsageStatus.READY, db.sessionDao().byId("backward")!!.appUsageStatus)
        assertEquals(listOf("a"), db.appUsageDao().sessionUsage("backward").first().map { it.packageName })
        assertEquals(setOf("seed1", "seed2", "backward"), db.appUsageDao().snapshots().map { it.sessionId }.toSet())
    }

    @Test fun snapshotsArePrunedToTheOpenBaselinePlusTheNewestThree() = runBlocking {
        db.sessionDao().insert(session("open", open = true, start = 1))
        assertTrue(store.saveBaseline("open", snapshot(100, 1, AppUsageRow(1, "a", 1.0))))
        repeat(5) { i ->
            db.sessionDao().insert(session("s$i", start = 100L + i))
            store.saveEnd("s$i", snapshot(100, 10L + i, AppUsageRow(1, "a", 2.0)), AppUsageDelta.compute(null, snapshot(100, 10L + i, AppUsageRow(1, "a", 2.0))))
        }
        val kept = db.appUsageDao().snapshots()
        assertEquals(4, kept.size)
        assertEquals(setOf("open", "s2", "s3", "s4"), kept.map { it.sessionId }.toSet())
        assertTrue(kept.any { it.sessionId == "open" && it.kind == AppSnapshotKind.BASELINE })
    }
}
