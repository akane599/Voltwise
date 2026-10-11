package com.akane.voltwise.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source invariant only: Compose owner lifetime is exercised by AdaptiveNavigationOwnerDeviceTest. */
class AdaptiveNavigationStructureTest {
    private fun source(path: String): String =
        listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .first { it.exists() }.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")

    @Test fun shellNavigationOwnerHasOneUnconditionalCallSite() {
        val shell = source("com/akane/voltwise/ui/screens/MainScreen.kt")
        assertEquals("NavGraph must not have distinct bar and rail call sites", 1,
            Regex("\\bNavGraph\\s*\\(").findAll(shell).count())
        assertEquals("The adaptive shell must invoke its content once", 1,
            Regex("\\bcontent\\s*\\(").findAll(shell).count())
        val row = shell.indexOf("Row(Modifier.fillMaxSize())")
        val content = shell.indexOf("content(", row)
        assertTrue("Navigation content must be inside the shared Row", row >= 0 && content > row)
        val depth = shell.substring(row, content).fold(0) { level, char ->
            level + when (char) { '{' -> 1; '}' -> -1; else -> 0 }
        }
        assertEquals("Navigation content must be outside the conditional rail group", 1, depth)
    }
}
