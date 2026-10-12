package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.viewmodel.InsightMessageCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentResultCodesTest {
    private val feedback = InsightMessageCode.FEEDBACK_FAILED

    @Test fun `feedback failure is shown when the screen has no such notice`() {
        assertFalse(feedback in silentResultCodes(error = null))
        assertFalse(feedback in silentResultCodes(error = InsightMessageCode.ANALYSIS_FAILED))
    }

    @Test fun `feedback failure is silent when the screen shows it as its notice`() {
        assertTrue(feedback in silentResultCodes(error = feedback))
    }
}
