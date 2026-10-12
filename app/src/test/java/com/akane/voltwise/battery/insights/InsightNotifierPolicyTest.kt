package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightNotifierPolicyTest {
    private var now = 1_000_000_000L
    private val store = FakeKeyValueStore()
    private val policy = InsightNotificationPolicy(store) { now }

    private fun finding(
        key: String,
        severity: Severity = Severity.HIGH,
        confidence: Confidence = Confidence.MEDIUM,
    ) = Finding(key, FindingType.BACKGROUND_RUNAWAY, severity, confidence, 1.0, Subject.Device, null, emptyList(), emptyList(), emptyList())

    private fun report(vararg findings: Finding, headline: Finding? = null) =
        InsightReport(now, findings.toList(), headline)

    @Test
    fun postedFindingLeavingReportRequestsCancellationDuringCooldown() {
        policy.markNotified(finding("a"))
        now += 1
        val withoutPostedFinding = report(finding("b"))
        assertNull(policy.select(withoutPostedFinding))

        assertTrue("The notification must be cancelled when its finding leaves the report", policy.shouldCancel(withoutPostedFinding))
    }

    @Test
    fun postedFindingStillInReportDoesNotRequestCancellation() {
        val posted = finding("a")
        policy.markNotified(posted)

        assertFalse(policy.shouldCancel(report(posted)))
        assertFalse(policy.shouldCancel(report(finding("b"), headline = posted)))
        assertFalse(policy.shouldCancel(report(finding("a", Severity.MEDIUM, Confidence.LOW))))
    }

    @Test
    fun emptyReportRequestsCancellationOnlyUntilItIsHandled() {
        policy.markNotified(finding("a"))
        val restarted = InsightNotificationPolicy(store) { now }
        assertTrue(restarted.shouldCancel(report()))
        assertTrue("Keep requesting cancellation until the manager succeeds", restarted.shouldCancel(report()))

        restarted.markCancelled()

        assertNull(store.getString(InsightNotificationPolicy.POSTED_KEY))
        assertFalse(restarted.shouldCancel(report()))
        assertFalse(InsightNotificationPolicy(store) { now }.shouldCancel(report(finding("b"))))
        assertEquals(now.toString(), store.getString(InsightNotificationPolicy.LAST_AT))
        assertEquals(setOf("a"), storedKeys())
        assertNull(restarted.select(report(finding("b"))))
    }

    @Test
    fun replacementNotificationTracksOnlyTheLatestPostedFinding() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS
        policy.markNotified(finding("b"))
        val restarted = InsightNotificationPolicy(store) { now }

        assertEquals("b", store.getString(InsightNotificationPolicy.POSTED_KEY))
        assertFalse(restarted.shouldCancel(report(finding("b"))))
        assertTrue(restarted.shouldCancel(report(finding("a"))))
    }

    @Test
    fun noPostedNotificationDoesNotRequestCancellation() {
        assertFalse(policy.shouldCancel(report()))
        assertFalse(policy.shouldCancel(report(finding("a"))))
        assertEquals(0, store.writes)
    }

    @Test
    fun highSeverityMediumConfidenceIsSelected() {
        assertEquals("a", policy.select(report(finding("a")))?.key)
    }

    @Test
    fun lowSeverityOrLowConfidenceIsIgnored() {
        assertNull(policy.select(report(finding("a", Severity.MEDIUM), finding("b", confidence = Confidence.LOW))))
    }

    @Test
    fun headlineIsPreferredOverOtherFindings() {
        val h = finding("headline")
        assertEquals("headline", policy.select(report(finding("a"), headline = h))?.key)
    }

    @Test
    fun cooldownBlocksUntilTwentyFourHours() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS - 1
        assertNull(policy.select(report(finding("b"))))
        now += 1
        assertEquals("b", policy.select(report(finding("b")))?.key)
    }

    @Test
    fun alreadyNotifiedKeyIsSkippedAfterCooldown() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS
        assertNull(policy.select(report(finding("a"))))
        assertEquals("b", policy.select(report(finding("a"), finding("b")))?.key)
    }

    @Test
    fun lastingHighFindingsNotifyOnceUntilTheyResolveAndReturn() {
        val a = finding("a")
        val b = finding("b")
        val first = policy.select(report(a, b))
        assertEquals("a", first?.key)
        policy.markNotified(requireNotNull(first))

        now += InsightNotificationPolicy.COOLDOWN_MS
        val restarted = InsightNotificationPolicy(store) { now }
        val second = restarted.select(report(a, b))
        assertEquals("b", second?.key)
        restarted.markNotified(requireNotNull(second))

        now += InsightNotificationPolicy.COOLDOWN_MS
        assertNull("Both lasting findings have already notified", restarted.select(report(a, b)))
        assertNull(restarted.select(report(b)))
        assertEquals("a", InsightNotificationPolicy(store) { now }.select(report(a, b))?.key)
    }

    @Test
    fun resolvedKeysArePrunedDuringCooldownWithoutResettingIt() {
        policy.markNotified(finding("a"))
        val notifiedAt = now
        now += 1
        assertNull(policy.select(report(finding("b"))))
        assertEquals(emptySet<String>(), storedKeys())
        assertEquals(notifiedAt.toString(), store.getString(InsightNotificationPolicy.LAST_AT))
        assertNull(policy.select(report(finding("a"), finding("b"))))

        now = notifiedAt + InsightNotificationPolicy.COOLDOWN_MS
        assertEquals("a", InsightNotificationPolicy(store) { now }.select(report(finding("a"), finding("b")))?.key)
    }

    @Test
    fun legacyLastKeyMigratesDuringCooldownAndDoesNotReturnAfterResolution() {
        store.edit(
            mapOf(
                InsightNotificationPolicy.LAST_KEY to "a",
                InsightNotificationPolicy.LAST_AT to now.toString(),
            ),
        )
        assertNull(policy.select(report(finding("a"), finding("b"))))
        assertEquals(setOf("a"), storedKeys())
        assertNull(store.getString(InsightNotificationPolicy.LAST_KEY))

        now += InsightNotificationPolicy.COOLDOWN_MS
        val b = InsightNotificationPolicy(store) { now }.select(report(finding("a"), finding("b")))
        assertEquals("b", b?.key)
        policy.markNotified(requireNotNull(b))
        assertEquals(setOf("a", "b"), storedKeys())
        assertNull(policy.select(report(finding("b"))))
        assertEquals(setOf("b"), storedKeys())

        now += InsightNotificationPolicy.COOLDOWN_MS
        assertEquals("a", InsightNotificationPolicy(store) { now }.select(report(finding("a"), finding("b")))?.key)
    }

    @Test
    fun legacyLastKeyIsMergedWithExistingNotifiedKeys() {
        store.edit(
            mapOf(
                InsightNotificationPolicy.LAST_KEY to "a",
                InsightNotificationPolicy.NOTIFIED_KEYS to Json.encodeToString(setOf("b")),
            ),
        )
        assertNull(policy.select(report(finding("a"), finding("b"))))
        assertEquals(setOf("a", "b"), storedKeys())
        assertNull(store.getString(InsightNotificationPolicy.LAST_KEY))
    }

    @Test
    fun notifiedKeysStayBoundedToTheTwelveCurrentFindings() {
        val findings = (1..12).map { finding("key-$it") }.toTypedArray()
        for (expected in findings) {
            val selected = policy.select(report(*findings, headline = findings.first()))
            assertEquals(expected.key, selected?.key)
            policy.markNotified(requireNotNull(selected))
            now += InsightNotificationPolicy.COOLDOWN_MS
        }
        assertEquals(findings.map { it.key }.toSet(), storedKeys())
        assertNull(InsightNotificationPolicy(store) { now }.select(report(*findings)))
        val writes = store.writes
        assertNull(policy.select(report(*findings)))
        assertEquals(writes, store.writes)

        assertEquals("new", policy.select(report(finding("new")))?.key)
        policy.markNotified(finding("new"))
        assertEquals(setOf("new"), storedKeys())
        assertNull(policy.select(report()))
        assertEquals(emptySet<String>(), storedKeys())
    }

    @Test
    fun lowerSeverityOrConfidenceDoesNotResolveANotifiedKey() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS
        assertNull(policy.select(report(finding("a", Severity.MEDIUM, Confidence.LOW))))
        assertNull(policy.select(report(finding("a"))))
        assertEquals(setOf("a"), storedKeys())
    }

    private fun storedKeys(): Set<String> =
        Json.decodeFromString(requireNotNull(store.getString(InsightNotificationPolicy.NOTIFIED_KEYS)))

    @Test
    fun stateSurvivesANewPolicyOverTheSameStore() {
        policy.markNotified(finding("a"))
        assertNull(InsightNotificationPolicy(store) { now }.select(report(finding("b"))))
    }

    @Test
    fun clockSetBackDoesNotBlockForever() {
        policy.markNotified(finding("a"))
        now -= 1
        assertEquals("b", policy.select(report(finding("b")))?.key)
    }
}
