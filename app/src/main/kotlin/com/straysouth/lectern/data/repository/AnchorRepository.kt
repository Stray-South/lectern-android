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

private val Context.anchorPrefs by preferencesDataStore(name = "anchor_prefs")

class AnchorRepository(context: Context) {

    private val context = context.applicationContext

    private fun keyFor(bookId: String) = stringPreferencesKey("anchor_$bookId")

    suspend fun save(bookId: String, locator: Locator) {
        context.anchorPrefs.edit { prefs ->
            prefs[keyFor(bookId)] = locator.toJSON().toString()
        }
    }

    fun observe(bookId: String): Flow<Locator?> =
        context.anchorPrefs.data.map { prefs ->
            prefs[keyFor(bookId)]?.let { json ->
                runCatching { Locator.fromJSON(JSONObject(json)) }
                    .onFailure { Log.w("AnchorRepository", "Failed to parse anchor for $bookId", it) }
                    .getOrNull()
            }
        }

    suspend fun get(bookId: String): Locator? = observe(bookId).first()

    suspend fun clear(bookId: String) {
        context.anchorPrefs.edit { prefs ->
            prefs.remove(keyFor(bookId))
        }
    }
}
