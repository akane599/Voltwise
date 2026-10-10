package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageDelta
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.apps.AppStatsResult
import com.akane.voltwise.battery.apps.AppStatsSource
import com.akane.voltwise.battery.apps.RoomSessionSnapshotStore
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.apps.toAppUsageSnapshot
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import com.akane.voltwise.battery.insights.testSession
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.measurement.PowerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppCounterPipelineTest {
    private val rawCounters = listOf<Pair<Metric, (Int, Long) -> String>>(
        Metric.WAKEUP_ALARMS_PER_H to { uid, n -> "9,$uid,l,wua,tag,$n" },
        Metric.JOBS_PER_H to { uid, n -> "9,$uid,l,jb,job,0,$n,0,0" },
        Metric.GPS_MS_PER_H to { uid, n -> "9,$uid,l,sr,-10000,$n,1" },
        Metric.CPU_MS_PER_H to { uid, n -> "9,$uid,l,cpu,$n,0" },
        Metric.FGS_MS_PER_H to { uid, n -> "9,$uid,l,fgs,$n,1" },
        Metric.POWER_MAH_PER_H to { _, _ -> "" },
    )

    private data class Counter(
        val metric: Metric,
        val read: (AppUsageRow) -> Long?,
        val set: (AppUsageRow, Long?) -> AppUsageRow,
    )

    private val extended = listOf(
        Counter(Metric.WAKEUP_ALARMS_PER_H, AppUsageRow::wakeupAlarms) { r, n -> r.copy(wakeupAlarms = n) },
        Counter(Metric.PARTIAL_WAKELOCKS_PER_H, AppUsageRow::partialWakelockCount) { r, n -> r.copy(partialWakelockCount = n) },
        Counter(Metric.PARTIAL_WAKELOCK_BG_SHARE, AppUsageRow::partialWakelockBgMs) { r, n -> r.copy(partialWakelockBgMs = n) },
        Counter(Metric.JOBS_PER_H, AppUsageRow::jobCount) { r, n -> r.copy(jobCount = n) },
        Counter(Metric.JOB_MS_PER_H, AppUsageRow::jobMs) { r, n -> r.copy(jobMs = n) },
        Counter(Metric.SYNCS_PER_H, AppUsageRow::syncCount) { r, n -> r.copy(syncCount = n) },
        Counter(Metric.FGS_MS_PER_H, AppUsageRow::fgServiceMs) { r, n -> r.copy(fgServiceMs = n) },
        Counter(Metric.TOP_MS_PER_H, AppUsageRow::topMs) { r, n -> r.copy(topMs = n) },
        Counter(Metric.RADIO_ACTIVE_MS_PER_H, AppUsageRow::mobileActiveMs) { r, n -> r.copy(mobileActiveMs = n) },
        Counter(Metric.GPS_MS_PER_H, AppUsageRow::gpsMs) { r, n -> r.copy(gpsMs = n) },
        Counter(Metric.SENSOR_MS_PER_H, AppUsageRow::sensorMs) { r, n -> r.copy(sensorMs = n) },
    )
    private val legacy = listOf(
        Counter(Metric.CPU_MS_PER_H, AppUsageRow::cpuTimeMs) { r, n -> r.copy(cpuTimeMs = n) },
        Counter(Metric.FOREGROUND_MS_PER_H, AppUsageRow::foregroundTimeMs) { r, n -> r.copy(foregroundTimeMs = n) },
        Counter(Metric.BG_TIME_SHARE, AppUsageRow::backgroundTimeMs) { r, n -> r.copy(backgroundTimeMs = n) },
        Counter(Metric.WAKELOCK_MS_PER_H, AppUsageRow::wakelockTimeMs) { r, n -> r.copy(wakelockTimeMs = n) },
        Counter(Metric.MOBILE_BYTES_PER_H, AppUsageRow::mobileBytes) { r, n -> r.copy(mobileBytes = n) },
        Counter(Metric.WIFI_BYTES_PER_H, AppUsageRow::wifiBytes) { r, n -> r.copy(wifiBytes = n) },
    )

    @Test fun `lost or decreased supported counters cannot become action improvements through storage`() {
        for (counter in extended) for (folded in listOf(false, true)) for (end in listOf(null, 50L)) {
            val input = pipeline(counter, folded, base = 100L, end = end)
            val label = "${counter.metric}, folded=$folded, end=$end"
            assertTrue("$label must not report an improvement from missing observations",
                ActionEffects.detect(input).isEmpty())
            val after = AppWindows.select(input).takeLast(2)
            assertEquals(2, after.size)
            for (window in after) {
                assertNull("$label must provide neither a measured zero nor an incomplete tail bound",
                    AppWindows.point(window, Subject.App(UID, APP), counter.metric))
                if (!folded) assertNotNull("$label must retain the unknown-only app", window.row(Subject.App(UID, APP)))
            }
        }
    }

    @Test fun `measured zero counters still produce an action improvement`() {
        for (counter in extended) for (folded in listOf(false, true)) {
            val input = pipeline(counter, folded, base = 100L, end = 100L)
            val effect = ActionEffects.detect(input).single()
            val evidence = effect.evidence.single()
            assertEquals(counter.metric, evidence.metric)
            assertEquals(0.0, evidence.observed, 0.0)
            assertEquals(if (counter.metric == Metric.PARTIAL_WAKELOCK_BG_SHARE) 100.0 / HOUR else 100.0,
                evidence.baseline!!, 1e-12)
            assertEquals(4, evidence.sessions)
            assertEquals(Direction.DOWN, effect.direction)
        }
    }

    @Test fun `supported sparse absence on both sides stays a measured zero`() {
        for (counter in extended) for (folded in listOf(false, true)) {
            val input = pipeline(counter, folded, base = null, end = null)
            assertEquals(0.0, ActionEffects.detect(input).single().evidence.single().observed, 0.0)
        }
    }

    @Test fun `unsupported counters do not retain empty rows or produce an action effect`() {
        for (counter in extended) for (folded in listOf(false, true)) {
            val input = pipeline(counter, folded, base = null, end = null, supported = false)
            assertTrue(ActionEffects.detect(input).isEmpty())
            assertTrue(input.appSessions.filter { it.sessionId in setOf("s2", "s3") }.none { it.uid == UID })
            for (window in AppWindows.select(input).takeLast(2)) {
                assertNull(AppWindows.point(window, Subject.App(UID, APP), counter.metric))
            }
        }
    }

    @Test fun `every nullable tail total requires all contributors and preserves known zero`() {
        for (counter in legacy + extended) {
            val leader = AppUsageRow(20_000, "leader", 3.0)
            val a = AppUsageRow(20_001, "tail.a", 2.0)
            val b = AppUsageRow(20_002, "tail.b", 1.0)
            val wakers = (0 until 10).map {
                AppUsageRow(30_000 + it, "waker.$it", 2.5, wakeupAlarms = 100, partialWakelockBgMs = 100)
            }
            for ((left, right, expected) in listOf(
                Triple(null, 0L, null), Triple(null, 30L, null), Triple(null, null, null),
                Triple(0L, 0L, 0L), Triple(10L, 20L, 30L),
            )) {
                val rows = listOf(leader, counter.set(a, left), counter.set(b, right)) + wakers
                val result = AppUsageDelta.compute(null, AppUsageSnapshot(1, 1, HOUR, rows), topN = 1)
                assertEquals("${counter.metric}: $left + $right", expected, counter.read(result.rows.single { it.isOthers }))
                assertEquals("The power total is independent of counter availability", 3.0,
                    result.rows.single { it.isOthers }.powerMah, 0.0)
            }
        }
    }

    @Test fun `a whole UID rejected by the parser cannot become a zero or bound after storage`() = runTest {
        for ((metric, record) in rawCounters) for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = true)
            assertTrue("$metric, folded=$folded must not report improvement from a rejected UID",
                ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals("Only complete capture intervals remain eligible", listOf("s0", "s1"),
                AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                val window = AppWindows.select(input).firstOrNull { it.session.id == id }
                assertNull("$metric, folded=$folded must not invent a measured zero or upper bound",
                    window?.let { AppWindows.point(it, Subject.App(UID, APP), metric) })
                assertEquals("Accepted app B keeps its measured DELTA power", 1.0,
                    input.appSessions.single { it.sessionId == id && (it.uid == UID + 1 || it.isOthers) }.powerMah, 0.0)
            }
        }
    }

    @Test fun `parsed complete UID coverage keeps known zero counter improvements`() = runTest {
        for ((metric, record) in rawCounters.filterNot { it.first == Metric.POWER_MAH_PER_H }) {
            for (folded in listOf(false, true)) {
                val input = parsedPipeline(record, folded, missingUid = false)
                val effects = ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) }))
                assertEquals("$metric, folded=$folded keeps complete-window zero controls", 1, effects.size)
                val effect = effects.single()
                assertEquals(metric, effect.evidence.single().metric)
                assertEquals(100.0, effect.evidence.single().baseline!!, 0.0)
                assertEquals(0.0, effect.evidence.single().observed, 0.0)
            }
        }
    }

    @Test fun `collector withholds END coverage for rejected UIDs absent from the baseline`() = runTest {
        for ((metric, record) in rawCounters) for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = true, collectorScope = this,
                omitUidFromAfterBaseline = true)
            assertTrue("$metric, folded=$folded must not infer improvement from END rejection",
                ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals("Only the complete pre-action windows remain eligible", listOf("s0", "s1"),
                AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                assertNull("Collector must persist unavailable END coverage", input.sessions.single { it.id == id }.appWindow)
                assertEquals("Accepted B keeps its DELTA power for browsing", 1.0,
                    input.appSessions.single { it.sessionId == id && (it.uid == UID + 1 || it.isOthers) }.powerMah, 0.0)
            }
        }
    }

    @Test fun `collector preserves complete END zero counter improvements`() = runTest {
        for ((metric, record) in rawCounters.filterNot { it.first == Metric.POWER_MAH_PER_H }) {
            for (folded in listOf(false, true)) {
                val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this)
                assertEquals(4, AppWindows.select(input).size)
                val effects = ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) }))
                assertEquals("$metric, folded=$folded keeps complete collected zero controls", 1, effects.size)
                assertEquals(0.0, effects.single().evidence.single().observed, 0.0)
            }
        }
    }

    @Test fun `collector skips rejected baselines and keeps clean END totals absolute`() = runTest {
        for ((metric, record) in rawCounters) for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                collectBaselines = true, rejectAfterBaselineUid = true, afterEndCount = 150)
            assertTrue("$metric, folded=$folded must not compare cumulative totals as session deltas",
                ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals(listOf("s0", "s1"), AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                assertNull(input.sessions.single { it.id == id }.appWindow)
                val rows = input.appSessions.filter { it.sessionId == id }
                val accepted = rows.firstOrNull { it.uid == UID } ?: rows.single { it.isOthers }
                assertEquals("Accepted absolute charge remains accurate for browsing", if (folded) 604.0 else 4.0,
                    rows.sumOf { it.powerMah }, 0.0)
                val count = when (metric) {
                    Metric.WAKEUP_ALARMS_PER_H -> accepted.wakeupAlarms
                    Metric.JOBS_PER_H -> accepted.jobCount
                    Metric.GPS_MS_PER_H -> accepted.gpsMs
                    Metric.CPU_MS_PER_H -> accepted.cpuMs
                    Metric.FGS_MS_PER_H -> accepted.fgServiceMs
                    else -> null
                }
                if (metric != Metric.POWER_MAH_PER_H) assertEquals("Absolute counter retains its cumulative total", 150L, count)
            }
        }
    }

    @Test fun `collector keeps a complete baseline delta rather than cumulative counters`() = runTest {
        for ((metric, record) in rawCounters.filterNot { it.first == Metric.POWER_MAH_PER_H }) {
            val input = parsedPipeline(record, folded = false, missingUid = false, collectorScope = this,
                collectBaselines = true, afterEndCount = 150)
            val effects = ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) }))
            assertEquals("$metric keeps a measurable complete-baseline effect", 1, effects.size)
            assertEquals(100.0, effects.single().evidence.single().baseline!!, 0.0)
            assertEquals("Only the 150 minus 100 session delta is observed", 50.0,
                effects.single().evidence.single().observed, 0.0)
            assertEquals(4, AppWindows.select(input).size)
        }
    }

    @Test fun `collector withholds END coverage for explicitly invalid app counters`() = runTest {
        for ((metric, bad) in listOf(Metric.WAKEUP_ALARMS_PER_H to "9,$UID,l,wua,tag,NaN",
            Metric.FGS_MS_PER_H to "9,$UID,l,fgs,NaN,1")) {
            val record = rawCounters.single { it.first == metric }.second
            for (folded in listOf(false, true)) {
                val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                    omitAfterBaselineCounter = true, badAfterEndRecord = bad)
                assertTrue("$metric, folded=$folded invalid records cannot become an improvement",
                    ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
                assertEquals(listOf("s0", "s1"), AppWindows.select(input).map { it.session.id })
            }
        }
    }

    @Test fun `collector skips explicitly invalid counter baselines and uses absolute END totals`() = runTest {
        for ((metric, bad) in listOf(Metric.WAKEUP_ALARMS_PER_H to "9,$UID,l,wua,tag,NaN",
            Metric.FGS_MS_PER_H to "9,$UID,l,fgs,NaN,1")) {
            val record = rawCounters.single { it.first == metric }.second
            val input = parsedPipeline(record, folded = false, missingUid = false, collectorScope = this,
                collectBaselines = true, badAfterBaselineRecord = bad, afterEndCount = 150)
            assertTrue(ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals(listOf("s0", "s1"), AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                val accepted = input.appSessions.single { it.sessionId == id && it.uid == UID }
                assertEquals(2.0, accepted.powerMah, 0.0)
                assertEquals(150L, if (metric == Metric.WAKEUP_ALARMS_PER_H) accepted.wakeupAlarms else accepted.fgServiceMs)
            }
        }
    }

    @Test fun `collector withholds END coverage when wakeup alarm count is truncated`() = runTest {
        assertTruncatedEndCannotImprove(Metric.WAKEUP_ALARMS_PER_H, "9,$UID,l,wua,tag")
    }

    @Test fun `collector withholds END coverage when foreground service duration is truncated`() = runTest {
        assertTruncatedEndCannotImprove(Metric.FGS_MS_PER_H, "9,$UID,l,fgs")
    }

    @Test fun `collector skips baseline with truncated wakeup alarm count and retains absolute END totals`() = runTest {
        assertTruncatedBaselineStaysAbsolute(Metric.WAKEUP_ALARMS_PER_H, "9,$UID,l,wua,tag")
    }

    @Test fun `collector skips baseline with truncated foreground service duration and retains absolute END totals`() = runTest {
        assertTruncatedBaselineStaysAbsolute(Metric.FGS_MS_PER_H, "9,$UID,l,fgs")
    }

    private suspend fun TestScope.assertTruncatedEndCannotImprove(metric: Metric, truncated: String) {
        val record = rawCounters.single { it.first == metric }.second
        for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                omitAfterBaselineCounter = true, badAfterEndRecord = truncated)
            val label = "$metric, folded=$folded"
            assertTrue("$label must not turn a truncated record into a 100 to zero improvement",
                ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals("$label keeps only the complete pre-action windows", listOf("s0", "s1"),
                AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                assertNull("$label cannot certify the incomplete END capture", input.sessions.single { it.id == id }.appWindow)
                assertEquals("$label retains accepted B's measured DELTA power for browsing", 1.0,
                    input.appSessions.single { it.sessionId == id && (it.uid == UID + 1 || it.isOthers) }.powerMah, 0.0)
            }
        }
    }

    private suspend fun TestScope.assertTruncatedBaselineStaysAbsolute(metric: Metric, truncated: String) {
        val record = rawCounters.single { it.first == metric }.second
        for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                collectBaselines = true, badAfterBaselineRecord = truncated, afterEndCount = 150)
            val label = "$metric, folded=$folded"
            assertTrue("$label must not compare absolute END totals as session deltas",
                ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) })).isEmpty())
            assertEquals("$label keeps only the complete pre-action windows", listOf("s0", "s1"),
                AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) {
                assertNull("$label has no complete baseline capture", input.sessions.single { it.id == id }.appWindow)
                val rows = input.appSessions.filter { it.sessionId == id }
                assertEquals("$label retains all accepted absolute END power for browsing", if (folded) 604.0 else 4.0,
                    rows.sumOf { it.powerMah }, 0.0)
                val accepted = rows.firstOrNull { it.uid == UID } ?: rows.single { it.isOthers }
                assertEquals("$label preserves the absolute 150 count rather than inventing a delta", 150L,
                    if (metric == Metric.WAKEUP_ALARMS_PER_H) accepted.wakeupAlarms else accepted.fgServiceMs)
            }
        }
    }

    @Test fun `collector withholds END coverage for positive app evidence without a power row`() = runTest {
        val record = rawCounters.single { it.first == Metric.WAKEUP_ALARMS_PER_H }.second
        for (folded in listOf(false, true)) {
            val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                omitUidFromAfterBaseline = true, omitAfterEndPowerUid = true, afterEndCount = 150)
            assertTrue(ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = Metric.WAKEUP_ALARMS_PER_H) })).isEmpty())
            assertEquals(listOf("s0", "s1"), AppWindows.select(input).map { it.session.id })
            for (id in listOf("s2", "s3")) assertEquals(1.0,
                input.appSessions.single { it.sessionId == id && (it.uid == UID + 1 || it.isOthers) }.powerMah, 0.0)
        }
    }

    @Test fun `collector retains legitimate sparse absence and ignores unrelated malformed device records`() = runTest {
        for (metric in listOf(Metric.WAKEUP_ALARMS_PER_H, Metric.FGS_MS_PER_H)) {
            val record = rawCounters.single { it.first == metric }.second
            for (folded in listOf(false, true)) {
                val input = parsedPipeline(record, folded, missingUid = false, collectorScope = this,
                    omitAfterBaselineCounter = true, omitAfterEndCounter = true,
                    unrelatedAfterEndRecord = "9,0,l,kwl,device.lock,NaN,1")
                assertEquals(4, AppWindows.select(input).size)
                val effects = ActionEffects.detect(input.copy(actions = input.actions.map { it.copy(metric = metric) }))
                assertEquals("$metric, folded=$folded known sparse absence remains an improvement", 1, effects.size)
                assertEquals(0.0, effects.single().evidence.single().observed, 0.0)
            }
        }
    }

    private suspend fun parsedPipeline(
        record: (Int, Long) -> String,
        folded: Boolean,
        missingUid: Boolean,
        collectorScope: TestScope? = null,
        omitUidFromAfterBaseline: Boolean = false,
        collectBaselines: Boolean = false,
        rejectAfterBaselineUid: Boolean = false,
        afterEndCount: Long = 100,
        omitAfterBaselineCounter: Boolean = false,
        omitAfterEndCounter: Boolean = false,
        badAfterEndRecord: String? = null,
        badAfterBaselineRecord: String? = null,
        omitAfterEndPowerUid: Boolean = false,
        unrelatedAfterEndRecord: String? = null,
    ) = run {
        val sessions = mutableMapOf<String, ChargeSession>()
        val active = MutableStateFlow<ChargeSession?>(null)
        val stored = mutableMapOf<String, List<SessionAppUsage>>()
        val headers = mutableListOf<AppSnapshot>()
        val snapshotRows = mutableMapOf<Long, List<AppSnapshotUid>>()
        val sessionDao = object : UnusedSessionDao() {
            override fun activeFlow() = active
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flowOf(sessions.values.toList())
            override suspend fun byId(id: String) = sessions[id]
            override suspend fun update(session: ChargeSession): Int {
                sessions[session.sessionId] = session
                return 1
            }
        }
        val appDao = object : UnusedAppUsageDao() {
            override suspend fun latestSnapshot(sessionId: String, kind: AppSnapshotKind) =
                headers.lastOrNull { it.sessionId == sessionId && it.kind == kind }
            override suspend fun snapshotUids(snapshotId: Long) = snapshotRows.getValue(snapshotId)
            override suspend fun snapshotWakers(snapshotId: Long) = emptyList<SnapshotDeviceWaker>()
            override suspend fun insertSnapshotHeader(snapshot: AppSnapshot): Long {
                val id = headers.size.toLong() + 1
                headers += snapshot.copy(id = id)
                return id
            }
            override suspend fun insertSnapshotUids(rows: List<AppSnapshotUid>) {
                rows.groupBy { it.snapshotId }.forEach { (id, values) -> snapshotRows[id] = values }
            }
            override suspend fun insertSnapshotWakers(rows: List<SnapshotDeviceWaker>) = Unit
            override suspend fun pruneSnapshots(keepLatest: Int) = 0
            override suspend fun pruneOrphanSnapshots() = 0
            override suspend fun deleteSessionUsage(sessionId: String) { stored.remove(sessionId) }
            override suspend fun insertSessionUsage(rows: List<SessionAppUsage>) {
                rows.groupBy { it.sessionId }.forEach { (id, values) -> stored[id] = values }
            }
            override suspend fun deleteSessionWakers(sessionId: String) = Unit
            override suspend fun insertSessionWakerRows(rows: List<SessionDeviceWaker>) = Unit
            override suspend fun setAppUsageStatus(sessionId: String, status: AppUsageStatus, basis: AppUsageBasis?): Int {
                sessions[sessionId] = sessions.getValue(sessionId).copy(appUsageStatus = status, appUsageBasis = basis)
                return 1
            }
        }
        val store = RoomSessionSnapshotStore(sessionDao, appDao) { block -> block() }
        val endReads = ArrayDeque<BatteryStatsParser.FullSnapshot>()
        val stats = object : AppStatsSource {
            override suspend fun snapshot(force: Boolean) = AppStatsResult.Ready(endReads.removeFirst())
        }
        val transitions = MutableSharedFlow<PowerTransition>(extraBufferCapacity = 4)
        val collector = SessionSnapshotCollector(stats, store, transitions, log = {}, warn = { error(it) })
        val collectorJob = collectorScope?.backgroundScope?.launch { collector.run() }
        collectorScope?.runCurrent()
        for (index in 0..3) {
            val source = session(index)
            fun dump(at: Long, end: Boolean): BatteryStatsParser.FullSnapshot {
                val appUids = if (index >= 2 && !end && omitUidFromAfterBaseline) listOf(UID + 1) else listOf(UID, UID + 1)
                val uids = appUids + if (index >= 2 && folded) (20_000 until 20_030).toList() else emptyList()
                val raw = listOf("9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000") + uids.flatMap { uid ->
                    val count = if (uid != UID) 0L else if (index < 2) { if (end) 100L else 0L }
                        else if (end && missingUid) 150L else if (end) afterEndCount else 100L
                    val power = when {
                        uid == UID && index >= 2 && end && missingUid -> "NaN"
                        uid == UID && index >= 2 && !end && rejectAfterBaselineUid -> "NaN"
                        !end || (uid == UID && index >= 2 && afterEndCount == 100L) -> "1.0"
                        uid >= 20_000 -> "20.0"
                        else -> "2.0"
                    }
                    val counter = when {
                        uid != UID || index < 2 -> record(uid, count)
                        end && badAfterEndRecord != null -> badAfterEndRecord
                        !end && badAfterBaselineRecord != null -> badAfterBaselineRecord
                        (!end && omitAfterBaselineCounter) || (end && omitAfterEndCounter) -> ""
                        else -> record(uid, count)
                    }
                    listOf("9,0,i,uid,$uid,${if (uid == UID) APP else "app.$uid"}",
                        if (uid == UID && index >= 2 && end && omitAfterEndPowerUid) "" else "9,$uid,l,pwi,uid,$power", counter)
                }
                val device = if (index >= 2 && end) unrelatedAfterEndRecord.orEmpty() else ""
                return BatteryStatsParser.parseCheckin((raw + device).joinToString("\n")).copy(capturedAt = at)
            }
            val parsedBaseline = dump(source.startMs, end = false)
            val baseline = parsedBaseline.toAppUsageSnapshot()
            if (index >= 2 && rejectAfterBaselineUid) assertEquals(1, parsedBaseline.rejectedAppPowerRecords)
            val parsedEnd = dump(source.endMs, end = true)
            if (index >= 2 && missingUid) {
                assertEquals(1, parsedEnd.rejectedAppPowerRecords)
                assertFalse("The rejected power UID is absent even when its counters parsed", parsedEnd.apps.any { it.uid == UID })
            }
            if (index >= 2 && (badAfterEndRecord != null || omitAfterEndPowerUid)) {
                assertEquals("Counter or orphan quality is distinct from power rejection", 0, parsedEnd.rejectedAppPowerRecords)
            }
            sessions[source.id] = testSession(source.id).copy(startTime = source.startMs, endTime = source.endMs,
                appUsageStatus = AppUsageStatus.PENDING, appUsageBasis = null,
                appCaptureStartMs = null, appCaptureEndMs = null)
            if (collectBaselines) {
                checkNotNull(collectorScope)
                sessions[source.id] = sessions.getValue(source.id).copy(endTime = null, activeKey = 1)
                active.value = sessions.getValue(source.id)
                endReads += parsedBaseline
                collectorScope.runCurrent()
                collectorScope.advanceTimeBy(SessionSnapshotCollector.BASELINE_DEBOUNCE_MS)
                collectorScope.runCurrent()
                sessions[source.id] = sessions.getValue(source.id).copy(endTime = source.endMs, activeKey = null)
                active.value = null
            } else {
                sessions[source.id] = sessions.getValue(source.id).copy(endTime = null, activeKey = 1)
                assertTrue(store.saveBaseline(source.id, baseline))
                sessions[source.id] = sessions.getValue(source.id).copy(endTime = source.endMs, activeKey = null)
            }
            val end = parsedEnd.toAppUsageSnapshot()
            if (collectorScope == null) {
                val result = AppUsageDelta.compute(store.baseline(source.id), end)
                assertTrue(store.saveEnd(source.id, end, result))
            } else {
                endReads += parsedEnd
                assertTrue(transitions.tryEmit(PowerTransition(PowerState.DISCHARGING, PowerState.CHARGING,
                    source.endMs, 1, source.id, null)))
                collectorScope.runCurrent()
                collectorScope.advanceTimeBy(SessionSnapshotCollector.END_DEBOUNCE_MS)
                collectorScope.runCurrent()
            }
            assertEquals(if (index >= 2 && (rejectAfterBaselineUid || badAfterBaselineRecord != null))
                AppUsageBasis.ABSOLUTE else AppUsageBasis.DELTA,
                sessions.getValue(source.id).appUsageBasis)
            assertEquals("Partial app reads stay available for browsing", AppUsageStatus.READY,
                sessions.getValue(source.id).appUsageStatus)
            assertTrue("Stored rows contain only accepted, finite app power", stored.getValue(source.id).all {
                it.powerMah.isFinite() && it.powerMah >= 0.0
            })
        }
        collectorJob?.cancel()
        InsightInputsBuilder.build(sessions.getValue("s3").endTime!! + HOUR, 5L, 4_000_000L,
            sessions.values.toList(), emptyList(), stored.values.flatten(), emptyList(), emptyList(), emptySet(), emptyList(), emptyList())
            .copy(actions = listOf(AppliedActionInput(2, "WAKEUP_STORM:$APP", ActionType.RESTRICT_BACKGROUND,
                APP, UID, sessions.getValue("s1").endTime!! + HOUR, ActionStatus.APPLIED)))
    }

    private fun pipeline(counter: Counter, folded: Boolean, base: Long?, end: Long?, supported: Boolean = true) = run {
        val sessions = (0..3).map { index ->
            val source = session(index)
            testSession(source.id).copy(startTime = source.startMs, endTime = source.endMs,
                appCaptureStartMs = source.startMs, appCaptureEndMs = source.endMs)
        }
        val stored = sessions.flatMapIndexed { index, s ->
            val beforeAction = index < 2
            val app = AppUsageRow(UID, APP, 0.0, foregroundTimeMs = 0L)
            val other = AppUsageRow(UID + 1, "example.other", 0.0)
            val leaders = if (!beforeAction && folded) (0 until 30).map {
                AppUsageRow(20_000 + it, "leader.$it", 0.0)
            } else emptyList()
            val background = listOf(other) + leaders
            val baselineRows = background.map { counter.set(it, if (supported || beforeAction) 0L else null) } +
                counter.set(app, if (beforeAction) 0L else base)
            val endRows = background.map { counter.set(it.copy(powerMah = if (it.uid == other.uid) 1.0 else 10.0),
                if (supported || beforeAction) 0L else null) } +
                counter.set(app.copy(powerMah = if (beforeAction) 2.0 else 0.0), if (beforeAction) 100L else end)
            val result = AppUsageDelta.compute(
                AppUsageSnapshot(1, 1, s.startTime, baselineRows),
                AppUsageSnapshot(1, 1, s.endTime!!, endRows),
            )
            assertEquals(AppUsageBasis.DELTA, result.basis)
            assertTrue("Selection remains bounded", result.rows.size <= 41)
            result.rows.mapIndexed { rank, row -> row.toSessionUsage(s.sessionId, rank, result.basis) }
        }
        val input = InsightInputsBuilder.build(
            sessions.last().endTime!! + HOUR, 5L, 4_000_000L, sessions, emptyList(), stored,
            emptyList(), emptyList(), emptySet(), emptyList(), emptyList(),
        )
        input.copy(actions = listOf(AppliedActionInput(1, "BACKGROUND_LOCATION:$APP", ActionType.RESTRICT_BACKGROUND,
            APP, UID, sessions[1].endTime!! + HOUR, ActionStatus.APPLIED, counter.metric)))
    }
}
