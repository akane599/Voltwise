package com.akane.voltwise.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    private val key = intPreferencesKey("data_retention_index")

    @Test fun eachFiniteIndexResolvesToItsDays() {
        listOf(7L, 30L, 90L, 180L, 365L).forEachIndexed { index, days ->
            assertEquals("index $index", Retention.Days(days), resolveRetention(preferencesOf(key to index)))
        }
    }

    @Test fun foreverIsDistinctFromUnset() {
        assertEquals(Retention.Forever, resolveRetention(preferencesOf(key to RETENTION_FOREVER_INDEX)))
    }

    @Test fun absentKeyUsesNinetyDaysUnlessSettingsRecovered() {
        assertEquals(Retention.Days(90), resolveRetention(emptyPreferences()))
        assertEquals(Retention.Days(90), resolveRetention(preferencesOf(SETTINGS_RECOVERED to false)))
        assertEquals(Retention.Unset, resolveRetention(preferencesOf(SETTINGS_RECOVERED to true)))
    }

    @Test fun explicitChoicesWinOverTheRecoveryMarker() {
        (0..RETENTION_FOREVER_INDEX).forEach { index ->
            assertEquals(resolveRetention(preferencesOf(key to index)),
                resolveRetention(preferencesOf(key to index, SETTINGS_RECOVERED to true)))
        }
    }

    @Test fun outOfRangeIndicesAreUnsetWithOrWithoutRecovery() {
        listOf(Int.MIN_VALUE, -1, RETENTION_FOREVER_INDEX + 1, 99, Int.MAX_VALUE).forEach { index ->
            assertEquals("index $index", Retention.Unset, resolveRetention(preferencesOf(key to index)))
            assertEquals("recovered index $index", Retention.Unset,
                resolveRetention(preferencesOf(key to index, SETTINGS_RECOVERED to true)))
        }
    }
}
