package com.akane.voltwise.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey

/** The raw retention choice shared by migration, age cleanup, and Settings' not-set state. */
sealed interface Retention {
    data class Days(val days: Long) : Retention {
        init {
            require(days > 0)
        }
    }
    data object Forever : Retention
    data object Unset : Retention
}

internal val RETENTION_INDEX = intPreferencesKey("data_retention_index")

/** Missing choices use the default unless settings were recovered; a missing-after-recovery or out-of-range index never authorizes deletion. */
fun resolveRetention(prefs: Preferences): Retention {
    val index = prefs[RETENTION_INDEX]
        ?: if (prefs[SETTINGS_RECOVERED] == true) return Retention.Unset else AppSettings().dataRetentionIndex
    return when (index) {
        0 -> Retention.Days(7)
        1 -> Retention.Days(30)
        2 -> Retention.Days(90)
        3 -> Retention.Days(180)
        4 -> Retention.Days(365)
        RETENTION_FOREVER_INDEX -> Retention.Forever
        else -> Retention.Unset
    }
}
