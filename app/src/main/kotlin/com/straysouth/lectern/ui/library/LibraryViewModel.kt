package com.straysouth.lectern.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.db.Book
import kotlinx.coroutines.flow.Flow

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val books: Flow<List<Book>> =
        AppDatabase.getInstance(application).bookDao().observeAll()
}
