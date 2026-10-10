package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionSnapshotStoreTest {
    private class Sessions : UnusedSessionDao() {
        val rows = mutableMapOf<String, ChargeSession>()
        override suspend fun byId(id: String) = rows[id]
        override suspend fun update(session: ChargeSession): Int {
            rows[session.sessionId] = session
            return 1
        }
    }

    /** Models child FKs/cascades and insertion-order retention, not Room/SQLite transactions. */
    private class Usage(private val sessions: Sessions) : UnusedAppUsageDao() {
        val headers = mutableMapOf<Long, AppSnapshot>()
        val uids = mutableMapOf<Long, List<AppSnapshotUid>>()
        val wakers = mutableMapOf<Long, List<SnapshotDeviceWaker>>()
        val breakdowns = mutableMapOf<String, List<SessionAppUsage>>()
        private var nextId = 1L

        override suspend fun insertSnapshotHeader(snapshot: AppSnapshot): Long = nextId++.also {
            headers[it] = snapshot.copy(id = it)
        }
        override suspend fun insertSnapshotUids(rows: List<AppSnapshotUid>) {
            rows.groupBy { it.snapshotId }.forEach { (id, children) ->
                check(id in headers) { "UID foreign key" }
                uids[id] = children
            }
        }
        override suspend fun insertSnapshotWakers(rows: List<SnapshotDeviceWaker>) {
            rows.groupBy { it.snapshotId }.forEach { (id, children) ->
                check(id in headers) { "Waker foreign key" }
                wakers[id] = children
            }
        }
        override suspend fun pruneSnapshots(keepLatest: Int): Int {
            // All fixture snapshots have wakers; pin the DAO's child-before-prune write order.
            assertTrue("Snapshot wakers must exist before pruning", headers.keys.last() in wakers)
            val newest = headers.keys.sortedDescending().take(keepLatest).toSet()
            val removed = headers.values.filter { header ->
                header.id !in newest && !(header.kind == AppSnapshotKind.BASELINE &&
                    sessions.rows[header.sessionId]?.activeKey == 1)
            }.map { it.id }
            removed.forEach { id -> headers.remove(id); uids.remove(id); wakers.remove(id) }
            return removed.size
        }
        override suspend fun snapshotUids(snapshotId: Long) = uids[snapshotId].orEmpty()
        override suspend fun snapshotWakers(snapshotId: Long) = wakers[snapshotId].orEmpty()
        override suspend fun pruneOrphanSnapshots() = 0
        override suspend fun latestSnapshot(sessionId: String, kind: AppSnapshotKind) = headers.values
            .filter { it.sessionId == sessionId && it.kind == kind }
            .maxWithOrNull(compareBy<AppSnapshot> { it.capturedAt }.thenBy { it.id })
        override suspend fun deleteSessionUsage(sessionId: String) { breakdowns.remove(sessionId) }
        override suspend fun insertSessionUsage(rows: List<SessionAppUsage>) {
            rows.groupBy { it.sessionId }.forEach { (id, children) -> breakdowns[id] = children }
        }
        override suspend fun setAppUsageStatus(sessionId: String, status: AppUsageStatus, basis: AppUsageBasis?): Int {
            val session = sessions.rows[sessionId] ?: return 0
            sessions.rows[sessionId] = session.copy(appUsageStatus = status, appUsageBasis = basis)
            return 1
        }
        override suspend fun deleteSessionWakers(sessionId: String) = Unit
        override suspend fun insertSessionWakerRows(rows: List<SessionDeviceWaker>) = Unit
    }

    private val sessions = Sessions()
    private val usage = Usage(sessions)
    private val store = RoomSessionSnapshotStore(sessions, usage) { block -> block() }
    private val waker = DeviceWaker("WAKEUP_REASON", "alarm", 2, 20)

    private fun session(id: String, open: Boolean = false) = ChargeSession(
        id, SessionType.DISCHARGE, 1_000, if (open) null else 1_500,
        80, null, null, null, null, appUsageStatus = AppUsageStatus.PENDING,
    )
    private fun snapshot(time: Long) = AppUsageSnapshot(
        100, 3, time, listOf(AppUsageRow(1, "app", 3.0)), deviceWakers = listOf(waker), wakersComplete = true,
    )
    private suspend fun saveEnd(id: String, time: Long) {
        sessions.rows[id] = session(id)
        val end = snapshot(time)
        assertTrue(store.saveEnd(id, end, AppUsageDelta.compute(null, end)))
    }

    @Test fun saveBaselineRejectsClosedSession() = runBlocking {
        sessions.rows["closed"] = session("closed")

        val saved = store.saveBaseline("closed", snapshot(2_000))

        assertFalse("A closed session must reject a late baseline", saved)
        assertNull(usage.latestSnapshot("closed", AppSnapshotKind.BASELINE))
    }

    @Test fun saveBaselineAcceptsOpenSession() = runBlocking {
        sessions.rows["open"] = session("open", open = true)

        assertTrue(store.saveBaseline("open", snapshot(1_200)))
        assertEquals(1_200L, usage.latestSnapshot("open", AppSnapshotKind.BASELINE)!!.capturedAt)
    }

    @Test fun backwardClockSaveEndRetainsSnapshotAndWritesWakersBeforePruning() = runBlocking {
        repeat(3) { saveEnd("seed$it", 10_000L + it) }
        saveEnd("backward", 500)
        val header = usage.latestSnapshot("backward", AppSnapshotKind.END)!!
        assertEquals(500L, header.capturedAt)
        assertEquals(listOf(1), usage.uids.getValue(header.id).map { it.uid })
        assertEquals(listOf(waker.name), usage.wakers.getValue(header.id).map { it.name })
        assertEquals(AppUsageStatus.READY, sessions.rows.getValue("backward").appUsageStatus)
        assertEquals(listOf("app"), usage.breakdowns.getValue("backward").map { it.packageName })
        assertEquals(setOf("seed1", "seed2", "backward"), usage.headers.values.map { it.sessionId }.toSet())
    }

    @Test fun increasingClockKeepsThreeLatestAndOpenBaselineWithChildren() = runBlocking {
        sessions.rows["open"] = session("open", open = true)
        assertTrue(store.saveBaseline("open", snapshot(1)))
        repeat(5) { saveEnd("end$it", 10L + it) }
        assertEquals(setOf("open", "end2", "end3", "end4"), usage.headers.values.map { it.sessionId }.toSet())
        assertEquals(usage.headers.keys, usage.uids.keys)
        assertEquals(usage.headers.keys, usage.wakers.keys)
        assertEquals(1L, store.baseline("open")!!.capturedAt)
    }
}
