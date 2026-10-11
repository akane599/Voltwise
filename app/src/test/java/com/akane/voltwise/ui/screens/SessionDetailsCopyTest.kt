package com.akane.voltwise.ui.screens

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDetailsCopyTest {
    @Test
    fun `absolute basis explains missing comparable unplug reading in every supported locale`() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        val expected = mapOf(
            "values" to "Since the last full charge (no comparable reading at unplug)",
            "values-es" to "Desde la última carga completa (sin lectura comparable al desconectar)",
            "values-tr" to "Son tam şarjdan beri (fişten çekilirken karşılaştırılabilir okuma yok)",
        )
        expected.forEach { (locale, copy) ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resources, "$locale/strings_sessiondetails.xml"))
            val strings = document.getElementsByTagName("string")
            val absolute = (0 until strings.length).map { strings.item(it) }.single {
                it.attributes.getNamedItem("name").nodeValue == "sessiondetails_apps_basis_absolute"
            }
            assertEquals(locale, copy, absolute.textContent)
        }
    }
}
