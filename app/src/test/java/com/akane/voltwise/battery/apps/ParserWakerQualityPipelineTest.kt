package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import com.akane.voltwise.battery.insights.engine.detectors.device.attributions
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.util.BatteryStatsParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real parser, mapper, production store, delta and consumers; DAO fakes are not Room validation. */
class ParserWakerQualityPipelineTest {
    private class Sessions : UnusedSessionDao() {
        var session = ChargeSession("session", SessionType.DISCHARGE, 1_000_000, null,
            80, 70, null, null, null, appUsageStatus = AppUsageStatus.PENDING)
        override suspend fun byId(id: String) = session.takeIf { it.sessionId == id }
        override suspend fun update(session: ChargeSession): Int {
            this.session = session
            return 1
        }
    }

    private class Usage(private val sessions: Sessions) : UnusedAppUsageDao() {
        val headers = mutableMapOf<Long, AppSnapshot>()
        val uidRows = mutableMapOf<Long, List<AppSnapshotUid>>()
        val snapshotWakers = mutableMapOf<Long, List<SnapshotDeviceWaker>>()
        var appRows = emptyList<SessionAppUsage>()
        var deviceRows = emptyList<SessionDeviceWaker>()
        override suspend fun insertSnapshotHeader(snapshot: AppSnapshot): Long =
            (headers.size + 1L).also { headers[it] = snapshot.copy(id = it) }
        override suspend fun insertSnapshotUids(rows: List<AppSnapshotUid>) {
            rows.groupBy { it.snapshotId }.forEach { (id, values) -> check(id in headers); uidRows[id] = values }
        }
        override suspend fun insertSnapshotWakers(rows: List<SnapshotDeviceWaker>) {
            rows.groupBy { it.snapshotId }.forEach { (id, values) -> check(id in headers); snapshotWakers[id] = values }
        }
        override suspend fun latestSnapshot(sessionId: String, kind: AppSnapshotKind) =
            headers.values.lastOrNull { it.sessionId == sessionId && it.kind == kind }
        override suspend fun snapshotUids(snapshotId: Long) = uidRows[snapshotId].orEmpty()
        override suspend fun snapshotWakers(snapshotId: Long) = snapshotWakers[snapshotId].orEmpty()
        override suspend fun pruneSnapshots(keepLatest: Int) = 0
        override suspend fun pruneOrphanSnapshots() = 0
        override suspend fun deleteSessionUsage(sessionId: String) { appRows = emptyList() }
        override suspend fun insertSessionUsage(rows: List<SessionAppUsage>) { appRows = rows }
        override suspend fun setAppUsageStatus(sessionId: String, status: AppUsageStatus, basis: AppUsageBasis?): Int {
            sessions.session = sessions.session.copy(appUsageStatus = status, appUsageBasis = basis)
            return 1
        }
        override suspend fun deleteSessionWakers(sessionId: String) { deviceRows = emptyList() }
        override suspend fun insertSessionWakerRows(rows: List<SessionDeviceWaker>) { deviceRows = rows }
    }

    private fun parse(power: Double, wakers: String, capturedAt: Long) = BatteryStatsParser.parseCheckin(
        "9,0,l,bt,3,9000000,8000000,0,0,100,7000000\n" +
            "9,10001,l,pwi,uid,$power\n9,10001,l,wua,app_alarm,0\n$wakers",
    ).copy(capturedAt = capturedAt)

    @Test fun rejectedBaselineWakersStayUnknownThroughStoredDeltaAndDeviceCauses() = runBlocking {
        for (tag in listOf("kwl", "wr")) for (bad in listOf(
            "worker,unknown,2", "\"worker,100,2", "worker,100,unknown",
        )) {
            verifyPipeline(tag, "9,0,l,$tag,$bad", complete = false, includeWorker = false)
        }
    }

    @Test fun genuinelyAbsentAndReportedZeroBaselinesStillStartNewWakersAtZero() = runBlocking {
        for (tag in listOf("kwl", "wr")) for (before in listOf("", "9,0,l,$tag,worker,0,0")) {
            verifyPipeline(tag, before, complete = true, includeWorker = true)
        }
    }

    private suspend fun verifyPipeline(tag: String, before: String, complete: Boolean, includeWorker: Boolean) {
        val label = "$tag $before"
        val sessions = Sessions()
        val usage = Usage(sessions)
        val store = RoomSessionSnapshotStore(sessions, usage) { block -> block() }
        val baselineParse = parse(1.0, "$before\n9,0,l,$tag,neighbor,100,2", 1_000_000)
        val endParse = parse(3.0, "9,0,l,$tag,worker,7200000,900\n9,0,l,$tag,neighbor,160,5", 4_600_000)
        assertTrue(label, baselineParse.hasValidWindow)
        assertTrue("Bad wakers must not invalidate apps: $label", baselineParse.appMeasurementsComplete)
        assertTrue(endParse.appMeasurementsComplete)
        assertTrue(store.saveBaseline("session", baselineParse.toAppUsageSnapshot()))
        val storedBaseline = store.baseline("session")!!
        assertEquals(label, complete, usage.headers.values.single().wakersComplete)
        assertEquals(label, complete, storedBaseline.wakersComplete)
        val end = endParse.toAppUsageSnapshot()
        val result = AppUsageDelta.compute(storedBaseline, end)
        assertEquals(label, AppUsageBasis.DELTA, result.basis)
        assertEquals(2.0, result.rows.single().powerMah, 0.0)
        assertEquals(1_000_000L, result.captureStartMs)
        sessions.session = sessions.session.copy(endTime = end.capturedAt, activeKey = null)
        assertTrue(store.saveEnd("session", end, result))
        assertEquals(AppUsageStatus.READY, sessions.session.appUsageStatus)
        assertEquals(2.0, usage.appRows.single().powerMah, 0.0)
        assertEquals(label, if (includeWorker) setOf("worker", "neighbor") else setOf("neighbor"),
            usage.deviceRows.map { it.name }.toSet())
        val neighbor = usage.deviceRows.single { it.name == "neighbor" }
        assertEquals(3L, neighbor.count)
        assertEquals(60L, neighbor.totalMs)
        val input = InsightInputsBuilder.build(4_600_001, 0, 4_000_000,
            listOf(sessions.session), emptyList(), usage.appRows, usage.deviceRows, emptyList(), emptySet(), emptyList(), emptyList())
        assertEquals("Device rejection must preserve the certified app interval", 1, AppWindows.select(input).size)
        val causes = attributions(input, "session")
        assertEquals(label, if (includeWorker) setOf("worker", "neighbor") else setOf("neighbor"), causes.map { it.name }.toSet())
        assertEquals(if (tag == "kwl") 60.0 else 3.0, causes.single { it.name == "neighbor" }.value, 0.0)
        if (includeWorker) assertEquals(if (tag == "kwl") 7_200_000.0 else 900.0,
            causes.single { it.name == "worker" }.value, 0.0)
    }
}
