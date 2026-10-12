package com.akane.voltwise.ui.screens

import com.akane.voltwise.R
import com.akane.voltwise.ui.format.formatMah
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppsFormatTest {
    private val us = Locale.US
    private val tr = Locale.forLanguageTag("tr-TR")

    @Test fun bytesUseSiStepsWithOneDecimalUnderAHundred() {
        fun check(bytes: Long, value: String, unit: Int, locale: Locale = us) {
            val size = byteSize(bytes, locale)
            assertEquals(value, size.value)
            assertEquals(unit, size.unit)
        }
        check(0, "0", R.string.apps_unit_bytes)
        check(999, "999", R.string.apps_unit_bytes)
        check(1_500, "1.5", R.string.apps_unit_kb)
        check(12_400_000, "12.4", R.string.apps_unit_mb)
        check(421_000_000, "421", R.string.apps_unit_mb)
        check(1_262_000_000, "1.3", R.string.apps_unit_gb)
        // Beyond GB stays in GB, grouped.
        check(3_400_000_000_000, "3,400", R.string.apps_unit_gb)
        check(1_262_000_000, "1,3", R.string.apps_unit_gb, tr)
        check(-5, "0", R.string.apps_unit_bytes)
        // Rounding that reaches 1000 moves up a unit; decimals follow the rounded value.
        check(999_999, "1.0", R.string.apps_unit_mb)
        check(99_960, "100", R.string.apps_unit_kb)
        check(999_600, "1.0", R.string.apps_unit_mb)
        check(999_999_999, "1.0", R.string.apps_unit_gb)
    }

    @Test fun durationsUnderAMinuteKeepTheirSeconds() {
        fun check(ms: Long, value: String, unit: Int) {
            val duration = appDuration(ms, us)
            assertEquals(value, duration.value)
            assertEquals(unit, duration.unit)
        }
        check(0, "0", R.string.apps_unit_seconds)
        check(40_900, "40", R.string.apps_unit_seconds)
        check(59_999, "59", R.string.apps_unit_seconds)
        check(60_000, "1", R.string.now_unit_minutes)
        check(84 * 60_000L, "1:24", R.string.now_unit_hours)
    }

    @Test fun smallMahKeepADecimal() {
        assertEquals("0.4", formatMah(0.4, us))
        assertEquals("9.9", formatMah(9.94, us))
        assertEquals("124", formatMah(124.4, us))
        assertEquals("1,240", formatMah(1_240.0, us))
    }
}
