package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.viewmodel.AppliedInsightAction
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.InsightMessageCode
import com.akane.voltwise.viewmodel.InsightsUiState
import com.akane.voltwise.viewmodel.RecommendationState
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class InsightsLogicTest {
    private fun evidence(observed: Double, baseline: Double?) =
        Evidence(Metric.POWER_MAH_PER_H, observed, baseline, Metric.POWER_MAH_PER_H.unit, 6)

    private fun finding(key: String, vararg recs: RecommendationState) = InsightFindingState(
        key, FindingType.APP_DRAIN_ANOMALY, Severity.HIGH, Confidence.HIGH, 1.0, Subject.App(10_001, "com.example"),
        null, listOf(evidence(2.0, 1.0)), emptyList(), recs.toList(), emptyList(),
    )

    private fun rec(action: ActionType, available: Boolean, applied: Boolean = false) =
        RecommendationState(action, reversible = true, requiresPrivilege = true, available = available, alreadyApplied = applied)

    @Test
    fun `evidence above a positive baseline reads as a ratio of usual`() {
        val comparison = evidence(41.6, 13.0).comparison()
        assertTrue(comparison is EvidenceComparison.Ratio)
        assertEquals(3.2, (comparison as EvidenceComparison.Ratio).times, 1e-9)
    }

    @Test
    fun `evidence below its baseline reads as usually, not as a fraction ratio`() {
        assertEquals(EvidenceComparison.BelowUsual, evidence(0.12, 0.64).comparison())
    }

    @Test
    fun `evidence without a usable baseline is the value alone`() {
        assertEquals(EvidenceComparison.Plain, evidence(4.5, null).comparison())
        assertEquals(EvidenceComparison.Plain, evidence(4.5, 0.0).comparison())
        assertEquals(EvidenceComparison.Plain, evidence(Double.NaN, 1.0).comparison())
    }

    @Test
    fun `an action effect is the signed change from before to after`() {
        assertEquals(-0.62, evidence(3.8, 10.0).relativeChange()!!, 1e-9)
        assertEquals(0.5, evidence(15.0, 10.0).relativeChange()!!, 1e-9)
        assertNull(evidence(3.8, null).relativeChange())
        assertNull(evidence(3.8, 0.0).relativeChange())
    }

    @Test
    fun `findings win over every empty-state reason`() {
        val state = InsightsUiState(headline = finding("a"), error = InsightMessageCode.ANALYSIS_FAILED, lowData = true)
        assertEquals(InsightsBody.FINDINGS, state.body())
        val onlyFixes = InsightsUiState(
            appliedActions = listOf(AppliedInsightAction(1, "k", ActionType.FORCE_STOP, "com.example", InsightActionStatus.ONE_SHOT, 1L, false, null)),
        )
        assertEquals(InsightsBody.FINDINGS, onlyFixes.body())
    }

    @Test
    fun `without findings a failure is an error, never all good`() {
        val state = InsightsUiState(lastAnalyzedAt = 1L, lowData = false, error = InsightMessageCode.ANALYSIS_FAILED)
        assertEquals(InsightsBody.ERROR, state.body())
    }

    @Test
    fun `without findings few sessions is still learning, no run is an invitation, else all good`() {
        assertEquals(InsightsBody.LEARNING, InsightsUiState(lastAnalyzedAt = 1L, lowData = true).body())
        assertEquals(InsightsBody.NEVER_ANALYZED, InsightsUiState(lastAnalyzedAt = null, lowData = false).body())
        assertEquals(InsightsBody.ALL_GOOD, InsightsUiState(lastAnalyzedAt = 1L, lowData = false).body())
    }

    @Test
    fun `key findings do not repeat the headline`() {
        val headline = finding("top")
        val state = InsightsUiState(headline = headline, keyFindings = listOf(headline, finding("second"), finding("third")))
        assertEquals(listOf("second", "third"), state.listedFindings().map { it.key })
    }

    @Test
    fun `primary fix prefers one that can run, then the applied one, then the first`() {
        val runnable = rec(ActionType.STANDBY_BUCKET_RESTRICTED, available = true)
        val applied = rec(ActionType.RESTRICT_BACKGROUND, available = false, applied = true)
        val locked = rec(ActionType.FORCE_STOP, available = false)
        assertEquals(runnable, finding("a", locked, applied, runnable).primaryRecommendation())
        assertEquals(applied, finding("b", locked, applied).primaryRecommendation())
        assertEquals(locked, finding("c", locked).primaryRecommendation())
        assertNull(finding("d").primaryRecommendation())
    }

    @Test
    fun `pending apply finds its finding among headline, key findings and changes`() {
        val change = finding("trend")
        val state = InsightsUiState(headline = finding("top"), changes = listOf(change))
        assertEquals(change, state.findingFor("trend"))
        assertNull(state.findingFor("gone"))
    }

    @Test
    fun `every engine enum and result code has its own string`() {
        fun <T> assertDistinct(name: String, values: List<T>, res: (T) -> Int) {
            val ids = values.map(res)
            assertEquals("$name maps two values to one string", values.size, ids.toSet().size)
        }
        assertDistinct("FindingType", FindingType.entries) { it.titleRes() }
        assertDistinct("Metric", Metric.entries) { it.labelRes() }
        assertDistinct("ActionType label", ActionType.entries) { it.labelRes() }
        assertDistinct("ActionType effect", ActionType.entries) { it.effectRes() }
        assertDistinct("Severity", Severity.entries) { it.labelRes() }
        assertDistinct("Confidence", Confidence.entries) { it.labelRes() }
        assertDistinct("InsightActionStatus", InsightActionStatus.entries) { it.labelRes() }
        assertDistinct("InsightMessageCode", InsightMessageCode.entries) { it.messageRes() }
    }

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
    private val placeholder = Regex("%(\\d+)\\$[sd]")

    /** name → format placeholders of every translatable string and plural item in one locale's strings_insights.xml. */
    private fun translatable(dir: String): Map<String, Set<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings_insights.xml"))
        val result = mutableMapOf<String, Set<String>>()
        listOf("string", "plurals").forEach { tag ->
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                result[element.getAttribute("name")] = placeholder.findAll(element.textContent).map { it.value }.toSet()
            }
        }
        return result
    }

    @Test
    fun `Spanish and Turkish translate every Insights string with the same placeholders`() {
        val english = translatable("values")
        for (dir in listOf("values-es", "values-tr")) {
            val translated = translatable(dir)
            assertEquals("$dir keys", english.keys, translated.keys)
            english.forEach { (name, args) -> assertEquals("$dir $name placeholders", args, translated.getValue(name)) }
        }
    }
}
