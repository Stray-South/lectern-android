package com.straysouth.lectern.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

private val Context.readerPrefs by preferencesDataStore(name = "reader_prefs")

class LocatorRepository(context: Context) {

    private val context = context.applicationContext

    private fun keyFor(bookId: String) = stringPreferencesKey("locator_$bookId")

    suspend fun save(bookId: String, locator: Locator) {
        context.readerPrefs.edit { prefs ->
            prefs[keyFor(bookId)] = locator.toJSON().toString()
        }
    }

    fun observe(bookId: String): Flow<Locator?> =
        context.readerPrefs.data.map { prefs ->
            prefs[keyFor(bookId)]?.let { json ->
                runCatching { Locator.fromJSON(JSONObject(json)) }
                    .onFailure { Log.w("LocatorRepository", "Failed to parse locator for $bookId", it) }
                    .getOrNull()
            }
        }

    suspend fun get(bookId: String): Locator? = observe(bookId).first()

    suspend fun remove(bookId: String) {
        context.readerPrefs.edit { prefs -> prefs.remove(keyFor(bookId)) }
    }
}
