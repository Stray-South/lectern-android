package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rsvpDataStore by preferencesDataStore(name = "rsvp_prefs")

class RsvpRepository(context: Context) {

    private val ctx = context.applicationContext

    fun observe(): Flow<RsvpPrefs> = ctx.rsvpDataStore.data.map { prefs ->
        RsvpPrefs(
            wpm = (prefs[KEY_WPM] ?: RsvpPrefs.DEFAULT_WPM)
                .coerceIn(RsvpPrefs.MIN_WPM, RsvpPrefs.MAX_WPM),
            pauseOnPunctuation = prefs[KEY_PAUSE] ?: true,
        )
    }

    suspend fun save(prefs: RsvpPrefs) {
        ctx.rsvpDataStore.edit { settings ->
            settings[KEY_WPM] = prefs.wpm.coerceIn(RsvpPrefs.MIN_WPM, RsvpPrefs.MAX_WPM)
            settings[KEY_PAUSE] = prefs.pauseOnPunctuation
        }
    }

    companion object {
        private val KEY_WPM = intPreferencesKey("wpm")
        private val KEY_PAUSE = booleanPreferencesKey("pause_on_punctuation")
    }
}
