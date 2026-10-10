package com.akane.voltwise.ui.screens.insights

import android.graphics.Bitmap
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * The screens' produceState label lookups: a cancelled lookup must stay cancelled, or the old producer resumes and
 * overwrites the newer subject's labels with "Unknown app".
 */
class AppInfoOrNullTest {
    private class FakeSource(private val lookup: (String) -> AppInfo) : AppInfoSource {
        override suspend fun info(packageName: String): AppInfo = lookup(packageName)
        override suspend fun icon(packageName: String): Bitmap? = null
    }

    @Test
    fun `a cancelled lookup rethrows instead of reading as unknown`() = runTest {
        val cancelled = CancellationException("headline changed")
        val source = FakeSource { throw cancelled }
        try {
            source.infoOrNull("com.example.a")
            fail("CancellationException was swallowed into null")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
    }

    @Test
    fun `a failed lookup reads as unknown`() = runTest {
        val source = FakeSource { throw IllegalStateException("package manager died") }
        assertNull(source.infoOrNull("com.example.a"))
    }

    @Test
    fun `a found app is returned`() = runTest {
        val info = AppInfo("com.example.a", "Example", isSystem = false, installed = true)
        assertEquals(info, FakeSource { info }.infoOrNull("com.example.a"))
    }
}
