package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pdfPagePrefs by preferencesDataStore(name = "pdf_page_prefs")

class PdfPageRepository(context: Context) {

    private val context = context.applicationContext

    private fun keyFor(bookId: String) = intPreferencesKey("page_$bookId")

    suspend fun save(bookId: String, pageIndex: Int) {
        context.pdfPagePrefs.edit { it[keyFor(bookId)] = pageIndex }
    }

    suspend fun get(bookId: String): Int =
        context.pdfPagePrefs.data.map { it[keyFor(bookId)] ?: 0 }.first()
}
