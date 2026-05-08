package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ttsDataStore by preferencesDataStore(name = "tts_prefs")

class TtsRepository(context: Context) {

    private val ctx = context.applicationContext

    fun observe(): Flow<TtsPrefs> = ctx.ttsDataStore.data.map { prefs ->
        TtsPrefs(
            speed = prefs[KEY_SPEED] ?: 1.0,
        )
    }

    suspend fun save(prefs: TtsPrefs) {
        ctx.ttsDataStore.edit { settings ->
            settings[KEY_SPEED] = prefs.speed
        }
    }

    companion object {
        private val KEY_SPEED = doublePreferencesKey("speed")
    }
}
