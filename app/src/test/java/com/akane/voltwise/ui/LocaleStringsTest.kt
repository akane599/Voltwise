package com.akane.voltwise.ui

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Locale string templates read straight from the resource XML (no Android runtime on the JVM). */
class LocaleStringsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
    private val locales = listOf("values", "values-es", "values-tr")

    private fun elements(dir: String, tag: String): List<Element> =
        File(res, dir).listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }.orEmpty().flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    private fun string(dir: String, name: String): String? =
        elements(dir, "string").firstOrNull { it.getAttribute("name") == name }?.textContent

    private fun plural(dir: String, name: String): Map<String, String> {
        val plurals = elements(dir, "plurals").firstOrNull { it.getAttribute("name") == name } ?: return emptyMap()
        val items = plurals.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
    }

    @Test fun alertLevelPutsThePercentSignWhereTheLocalePercentTemplateDoes() {
        for (dir in locales) {
            val percentFirst = string(dir, "percent_value")!!.trimStart().startsWith("%%")
            val alert = string(dir, "alert_level_reported")!!
            assertEquals("$dir alert_level_reported: $alert", percentFirst, "%%%1\$d" in alert)
        }
        assertTrue(String.format(Locale.forLanguageTag("tr-TR"), string("values-tr", "alert_level_reported")!!, 94).contains("%94"))
    }

    @Test fun historyCaptionIsAPluralInEveryLocale() {
        for (dir in locales) {
            assertNull("$dir still has a plain apps_details_history_caption", string(dir, "apps_details_history_caption"))
            val items = plural(dir, "apps_details_history_caption")
            assertTrue("$dir apps_details_history_caption needs one and other: $items", items.keys.containsAll(listOf("one", "other")))
        }
        val en = plural("values", "apps_details_history_caption")
        assertEquals("Individually recorded in 1 of 1 session", String.format(Locale.US, en.getValue("one"), 1, 1))
        assertEquals("Individually recorded in 2 of 5 sessions", String.format(Locale.US, en.getValue("other"), 2, 5))
    }

    @Test fun calibrationEvidenceIsAPluralInEveryLocale() {
        for (dir in locales) {
            assertNull("$dir still has a plain settings_evidence_windows", string(dir, "settings_evidence_windows"))
            val items = plural(dir, "settings_evidence_windows")
            assertTrue("$dir settings_evidence_windows needs one and other: $items", items.keys.containsAll(listOf("one", "other")))
            items.forEach { (quantity, text) -> assertTrue("$dir $quantity: $text", "%1\$d" in text) }
        }
        val en = plural("values", "settings_evidence_windows")
        assertEquals("From 1 matching charge-counter window.", String.format(Locale.US, en.getValue("one"), 1))
        assertEquals("From 4 matching charge-counter windows.", String.format(Locale.US, en.getValue("other"), 4))
    }
}
