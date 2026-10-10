package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.insights.model.*
import org.junit.Assert.*
import org.junit.Test

internal fun testFinding(key: String = "DOZE_BLOCKED:device", severity: Severity = Severity.MEDIUM) = Finding(
    key, FindingType.DOZE_BLOCKED, severity, Confidence.HIGH, 72.0, Subject.Device, Direction.UP,
    listOf(Evidence(Metric.DEEP_DOZE_SHARE, 0.02, 0.7, MetricUnit.SHARE, 5)),
    listOf(SeriesPoint(1, 0.4, 0.6, 0.8), SeriesPoint(2, 0.02, null, null)),
    listOf(Recommendation(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS, true, false)),
    listOf(Attribution(AttributionKind.KERNEL_WAKELOCK, "waker", null, 40.0, MetricUnit.MS, 5)),
)

class FindingCodecTest {
    private val currentRecommendations = listOf(
        Recommendation(ActionType.RESTRICT_BACKGROUND, true, true),
        Recommendation(ActionType.STANDBY_BUCKET_RESTRICTED, true, true),
        Recommendation(ActionType.STANDBY_BUCKET_RARE, true, true),
        Recommendation(ActionType.FORCE_STOP, false, true),
        Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true),
        Recommendation(ActionType.OPEN_APP_SETTINGS, true, false),
        Recommendation(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS, true, false),
        Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, true, false),
    )

    @Test fun storedWrongUnitDecodesToMetricUnit() {
        val finding = testFinding()
        val stored = FindingCodec.encode(finding, 1)
        for (version in 1..FindingCodec.EVIDENCE_VERSION) {
            val decoded = FindingCodec.decode(stored.copy(
                evidenceVersion = version,
                evidenceJson = stored.evidenceJson.replace("\"SHARE\"", "\"CELSIUS\""),
            ))!!
            assertEquals("v$version derives the evidence unit from its metric", finding.evidence, decoded.evidence)
        }
    }

    @Test fun storedInvertedFlagsDecodeToActionRules() {
        val finding = testFinding().copy(recommendations = currentRecommendations)
        val inverted = finding.copy(recommendations = currentRecommendations.map {
            it.copy(reversible = !it.reversible, requiresPrivilege = !it.requiresPrivilege)
        })
        val stored = FindingCodec.encode(inverted, 1)
        for (version in 1..FindingCodec.EVIDENCE_VERSION) {
            val decoded = FindingCodec.decode(stored.copy(evidenceVersion = version))!!
            assertEquals("v$version derives recommendation flags from the action", finding.recommendations, decoded.recommendations)
        }
    }

    @Test fun currentActionRulesRoundTripAndCompatibilityFieldsAreWritten() {
        val finding = testFinding().copy(recommendations = currentRecommendations)
        val stored = FindingCodec.encode(finding, 1)
        assertEquals(ActionType.entries.toSet(), currentRecommendations.map { it.action }.toSet())
        assertEquals(finding, FindingCodec.decode(stored))
        assertTrue(stored.evidenceJson.contains("\"unit\":\"SHARE\""))
        for (rec in currentRecommendations) {
            assertTrue(stored.evidenceJson.contains(
                """{"action":"${rec.action.name}","reversible":${rec.reversible},"requiresPrivilege":${rec.requiresPrivilege}}""",
            ))
        }
    }

    @Test fun roundTripDeviceAndAppIncludingAttributions() {
        for (finding in listOf(testFinding(), testFinding().copy(subject = Subject.App(10001, "example.app")))) {
            val stored = FindingCodec.encode(finding, 10, 20)
            assertEquals(2, stored.evidenceVersion)
            assertEquals(finding, FindingCodec.decode(stored))
            assertEquals(10L, stored.firstSeenAt)
            assertEquals(20L, stored.lastSeenAt)
        }
    }

    @Test fun oversizeSeriesDropsOldestPointsAndKeepsNewest() {
        val finding = testFinding().copy(series = (0L..2_000L).map { SeriesPoint(it, 0.12345, 0.6789, 0.9876) })
        val stored = FindingCodec.encode(finding, 1)
        val decoded = FindingCodec.decode(stored)!!
        assertTrue(stored.evidenceJson.toByteArray(Charsets.UTF_8).size <= FindingCodec.MAX_BYTES)
        assertTrue(decoded.series.size in 1 until finding.series.size)
        assertEquals(finding.series.takeLast(decoded.series.size), decoded.series)
        assertEquals(finding.evidence, decoded.evidence)
        assertEquals(finding.attributions, decoded.attributions)
    }

    @Test fun capUsesUtf8BytesAndTruncatesAttributionsBeforeEvidence() {
        val finding = testFinding().copy(series = emptyList(), attributions = listOf(
            Attribution(AttributionKind.WAKEUP_REASON, "充".repeat(8_000), null, 2.0, MetricUnit.COUNT, 4)))
        val stored = FindingCodec.encode(finding, 1)
        val decoded = FindingCodec.decode(stored)!!
        assertTrue(stored.evidenceJson.toByteArray(Charsets.UTF_8).size <= FindingCodec.MAX_BYTES)
        assertEquals(emptyList<Attribution>(), decoded.attributions)
        assertEquals(finding.evidence, decoded.evidence)
    }

    @Test fun olderVersionHasEmptyAttributionsAndUnknownVersionOrBadJsonIsSkipped() {
        val stored = FindingCodec.encode(testFinding(), 1)
        assertEquals(emptyList<Attribution>(), FindingCodec.decode(stored.copy(evidenceVersion = 1))!!.attributions)
        assertNotNull(FindingCodec.decode(stored.copy(evidenceVersion = 1,
            evidenceJson = """{"direction":"UP","evidence":[],"series":[],"recommendations":[]}""")))
        assertNull(FindingCodec.decode(stored.copy(evidenceVersion = 99)))
        assertNull(FindingCodec.decode(stored.copy(evidenceJson = "{broken")))
        assertNull(FindingCodec.decode(stored.copy(type = "FUTURE_TYPE")))
        assertNull(FindingCodec.decode(stored.copy(severity = "FUTURE_SEVERITY")))
        assertNull(FindingCodec.decode(stored.copy(confidence = "FUTURE_CONFIDENCE")))
        assertNull(FindingCodec.decode(stored.copy(uid = 10001)))
    }

    @Test fun unknownEnumItemsAreDroppedWithoutLosingOtherItems() {
        val stored = FindingCodec.encode(testFinding(), 1)
        val changed = stored.evidenceJson.replace("DEEP_DOZE_SHARE", "FUTURE_METRIC")
            .replace("OPEN_BATTERY_OPTIMIZATION_SETTINGS", "FUTURE_ACTION")
            .replace("KERNEL_WAKELOCK", "FUTURE_KIND").replace("\"UP\"", "\"FUTURE_DIRECTION\"")
        val decoded = FindingCodec.decode(stored.copy(evidenceJson = changed))!!
        assertTrue(decoded.evidence.isEmpty())
        assertTrue(decoded.recommendations.isEmpty())
        assertTrue(decoded.attributions.isEmpty())
        assertNull(decoded.direction)
        assertEquals(testFinding().series, decoded.series)
        val unknownUnit = stored.evidenceJson.replace("\"SHARE\"", "\"FUTURE_UNIT\"")
        assertEquals(testFinding().evidence, FindingCodec.decode(stored.copy(evidenceJson = unknownUnit))!!.evidence)
    }
}
