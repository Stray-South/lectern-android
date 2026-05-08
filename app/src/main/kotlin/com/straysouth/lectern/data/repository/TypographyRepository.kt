package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.typographyDataStore by preferencesDataStore(name = "typography_prefs")

class TypographyRepository(context: Context) {

    private val ctx = context.applicationContext

    fun observe(): Flow<TypographyPrefs> = ctx.typographyDataStore.data.map { prefs ->
        TypographyPrefs(
            fontFamily = prefs[KEY_FONT_FAMILY] ?: "default",
            fontSize = prefs[KEY_FONT_SIZE] ?: 1.0,
            lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.5,
            theme = prefs[KEY_THEME] ?: "light",
        )
    }

    suspend fun save(prefs: TypographyPrefs) {
        ctx.typographyDataStore.edit { settings ->
            settings[KEY_FONT_FAMILY] = prefs.fontFamily
            settings[KEY_FONT_SIZE] = prefs.fontSize
            settings[KEY_LINE_HEIGHT] = prefs.lineHeight
            settings[KEY_THEME] = prefs.theme
        }
    }

    companion object {
        private val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        private val KEY_FONT_SIZE = doublePreferencesKey("font_size")
        private val KEY_LINE_HEIGHT = doublePreferencesKey("line_height")
        private val KEY_THEME = stringPreferencesKey("theme")
    }
}
