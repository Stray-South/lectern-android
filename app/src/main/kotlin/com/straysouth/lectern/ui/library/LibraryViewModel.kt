package com.straysouth.lectern.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val books: StateFlow<List<Book>> =
        AppDatabase.getInstance(application).bookDao().observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
}
