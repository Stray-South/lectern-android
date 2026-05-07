package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

private val Context.readerPrefs by preferencesDataStore(name = "reader_prefs")

class LocatorRepository(private val context: Context) {

    private fun keyFor(bookId: String) = stringPreferencesKey("locator_$bookId")

    suspend fun save(bookId: String, locator: Locator) {
        context.readerPrefs.edit { prefs ->
            prefs[keyFor(bookId)] = locator.toJSON().toString()
        }
    }

    fun observe(bookId: String): Flow<Locator?> =
        context.readerPrefs.data.map { prefs ->
            prefs[keyFor(bookId)]?.let { json ->
                runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
            }
        }

    suspend fun get(bookId: String): Locator? = observe(bookId).first()
}
