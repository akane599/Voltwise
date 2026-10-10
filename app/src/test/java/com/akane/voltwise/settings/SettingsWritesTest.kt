package com.akane.voltwise.settings

import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import org.junit.Assert.*
import org.junit.Test

class SettingsWritesTest {
    @Test fun dropdownIndexBecomesTheEnumConstant() {
        assertEquals(StatusIconValue.POWER_W, SettingsWrites.normalize("statusIconValue", 2))
        assertEquals(CurrentUnitOverride.MILLIAMPS, SettingsWrites.normalize("currentUnitOverride", 2))
        assertEquals(CurrentSignOverride.INVERTED, SettingsWrites.normalize("currentSignOverride", 2))
        assertEquals(CurrentSignOverride.NORMAL, SettingsWrites.normalize("currentSignOverride", CurrentSignOverride.NORMAL))
        assertThrows(IllegalArgumentException::class.java) { SettingsWrites.normalize("statusIconValue", 5) }
        assertThrows(IllegalArgumentException::class.java) { SettingsWrites.normalize("currentUnitOverride", -1) }
    }

    @Test fun otherValuesPassThroughUnchanged() {
        assertEquals(3, SettingsWrites.normalize("dataRetentionIndex", 3))
        assertEquals(true, SettingsWrites.normalize("oledBlack", true))
        assertThrows(IllegalArgumentException::class.java) { SettingsWrites.normalize("themeIndex", 1) }
    }

    @Test fun designCapacityIsAutoOrWithinRange() {
        listOf(0, 1_000, 30_000).forEach { assertEquals(it, SettingsWrites.normalize("designCapacityMah", it)) }
        listOf(-1, 999, 30_001).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SettingsWrites.normalize("designCapacityMah", value) }
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsWrites.normalize("designCapacityMah", 4_500L) }
    }

    @Test fun estimatorsOnlySeeAValidDesignCapacity() {
        assertEquals(4_500, AppSettings(designCapacityMah = 4_500).designCapacityOverrideMah)
        assertEquals(DesignCapacity.AUTO, AppSettings(designCapacityMah = 500).designCapacityOverrideMah)
        assertEquals(DesignCapacity.AUTO, AppSettings(designCapacityMah = 40_000).designCapacityOverrideMah)
    }

    @Test fun overridesMapToCalibration() {
        assertEquals(listOf(null, CurrentUnit.MICROAMPS, CurrentUnit.MILLIAMPS), CurrentUnitOverride.entries.map { it.unit })
        assertEquals(listOf(null, CurrentSign.NORMAL, CurrentSign.INVERTED), CurrentSignOverride.entries.map { it.sign })
    }
}
