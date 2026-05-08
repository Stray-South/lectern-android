package com.straysouth.lectern.ui.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.db.AppDatabase
import com.straysouth.lectern.data.repository.LocatorRepository
import com.straysouth.lectern.data.repository.PublicationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val pubRepository = PublicationRepository(application)
    private val locatorRepository = LocatorRepository(application)
    private val bookDao = AppDatabase.getInstance(application).bookDao()

    sealed class State {
        object Loading : State()
        data class Ready(
            val publication: Publication,
            val navigatorFactory: EpubNavigatorFactory,
            val initialLocator: Locator?,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    // Tracked separately so onCleared() can close regardless of current _state value
    private var _publication: Publication? = null
    private val _loading = java.util.concurrent.atomic.AtomicBoolean(false)

    fun load(bookId: String) {
        if (_publication != null || !_loading.compareAndSet(false, true)) return
        viewModelScope.launch {
            val filePath = bookDao.getById(bookId)?.filePath
            if (filePath == null) {
                _loading.set(false)
                _state.value = State.Error("Book not found")
                return@launch
            }
            val savedLocator = locatorRepository.get(bookId)
            pubRepository.open(Uri.parse(filePath))
                .onSuccess { publication ->
                    _publication = publication
                    bookDao.updateLastOpened(bookId, System.currentTimeMillis())
                    _state.value = State.Ready(
                        publication = publication,
                        navigatorFactory = EpubNavigatorFactory(publication),
                        initialLocator = savedLocator,
                    )
                    _loading.set(false)
                }
                .onFailure { error ->
                    _loading.set(false)
                    Log.e("EpubReaderViewModel", "Failed to open publication", error)
                    _state.value = State.Error("Failed to open publication")
                }
        }
    }

    // Called by the reader once EpubNavigatorFragment.currentLocator is observed.
    fun saveLocator(bookId: String, locator: Locator) {
        viewModelScope.launch {
            locatorRepository.save(bookId, locator)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _publication?.close()
    }
}
