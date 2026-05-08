package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.focusBandDataStore by preferencesDataStore(name = "focus_band_prefs")

class FocusBandRepository(context: Context) {

    private val ctx = context.applicationContext

    fun observe(): Flow<FocusBandPrefs> = ctx.focusBandDataStore.data.map { prefs ->
        FocusBandPrefs(enabled = prefs[KEY_ENABLED] ?: true)
    }

    suspend fun save(prefs: FocusBandPrefs) {
        ctx.focusBandDataStore.edit { settings ->
            settings[KEY_ENABLED] = prefs.enabled
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
