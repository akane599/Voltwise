package com.akane.voltwise.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import java.io.File
import java.io.IOException

internal val SETTINGS_RECOVERED = booleanPreferencesKey("__settings_recovered__")

internal fun createSettingsDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(SETTINGS_RECOVERED to true) },
    scope = scope,
    produceFile = { file },
)

internal fun DataStore<Preferences>.withDefaultsOnReadFailure(): DataStore<Preferences> {
    val store = this
    return object : DataStore<Preferences> by store {
        override val data = store.data.retryWhen { failure, _ ->
            if (failure is IOException) {
                emit(emptyPreferences())
                // Keep lifetime collectors alive without spinning on an unreadable store.
                delay(1_000)
                true
            } else {
                false
            }
        }
    }
}
