package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.R
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.viewmodel.AppliedInsightAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightActionPresentationTest {
    @Test
    fun `restricted request presents Rare for recommendation and consent on Android 9 and 10`() {
        for (sdk in listOf(28, 29)) {
            val copy = ActionType.STANDBY_BUCKET_RESTRICTED.presentation(sdk)
            assertEquals("SDK $sdk row, dialog title and confirm label", R.string.insights_action_standby_rare, copy.labelRes)
            assertEquals("SDK $sdk fix row and dialog effect", R.string.insights_effect_standby_rare, copy.effectRes)
            assertEquals("SDK $sdk consent side effects", R.string.insights_side_standby_rare, copy.sideEffectsRes)
        }
    }

    @Test
    fun `restricted request presents Restricted from Android 11`() {
        for (sdk in listOf(30, 37)) {
            val copy = ActionType.STANDBY_BUCKET_RESTRICTED.presentation(sdk)
            assertEquals(R.string.insights_action_standby_restricted, copy.labelRes)
            assertEquals(R.string.insights_effect_standby_restricted, copy.effectRes)
            assertEquals(R.string.insights_side_standby_restricted, copy.sideEffectsRes)
        }
    }

    @Test
    fun `applied Rare fallback retains actual bucket copy after Android upgrade`() {
        val history = history(ActionType.STANDBY_BUCKET_RESTRICTED, "RARE")
        for (sdk in listOf(28, 29, 30, 37)) {
            val copy = requireNotNull(history.presentation(sdk))
            assertEquals("SDK $sdk history and undo description", R.string.insights_action_standby_rare, copy.labelRes)
            assertEquals(R.string.insights_effect_standby_rare, copy.effectRes)
            assertEquals(R.string.insights_side_standby_rare, copy.sideEffectsRes)
        }
        assertEquals(ActionType.STANDBY_BUCKET_RESTRICTED, history.action)
    }

    @Test
    fun `applied Restricted target takes precedence over current SDK fallback`() {
        val copy = requireNotNull(history(ActionType.STANDBY_BUCKET_RESTRICTED, "RESTRICTED").presentation(29))
        assertEquals(R.string.insights_action_standby_restricted, copy.labelRes)
        assertEquals(R.string.insights_effect_standby_restricted, copy.effectRes)
        assertEquals(R.string.insights_side_standby_restricted, copy.sideEffectsRes)
    }

    @Test
    fun `missing or unrecognized historical target uses current SDK presentation`() {
        for (target in listOf(null, "FUTURE_BUCKET")) {
            val history = history(ActionType.STANDBY_BUCKET_RESTRICTED, target)
            assertEquals(R.string.insights_action_standby_rare, history.presentation(29)?.labelRes)
            assertEquals(R.string.insights_action_standby_restricted, history.presentation(30)?.labelRes)
        }
    }

    @Test
    fun `unrelated actions keep their copy and unknown action remains unknown`() {
        for (sdk in listOf(28, 29, 30)) {
            val background = history(ActionType.RESTRICT_BACKGROUND, "RARE").presentation(sdk)
            assertEquals(R.string.insights_action_restrict_background, background?.labelRes)
            assertEquals(R.string.insights_effect_restrict_background, background?.effectRes)
            assertEquals(R.string.insights_side_restrict_background, background?.sideEffectsRes)
            val rare = ActionType.STANDBY_BUCKET_RARE.presentation(sdk)
            assertEquals(R.string.insights_action_standby_rare, rare.labelRes)
            assertEquals(R.string.insights_effect_standby_rare, rare.effectRes)
            assertEquals(R.string.insights_side_standby_rare, rare.sideEffectsRes)
            assertNull(history(null, "RARE").presentation(sdk))
        }
    }

    private fun history(action: ActionType?, target: String?) = AppliedInsightAction(
        id = 1, findingKey = "finding", action = action, packageName = "com.example.app",
        status = InsightActionStatus.APPLIED, appliedAt = 1L, undoable = true, effect = null,
        targetState = target,
    )
}
