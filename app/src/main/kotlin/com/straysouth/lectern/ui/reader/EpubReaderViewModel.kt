package com.straysouth.lectern.ui.reader

import android.app.Application
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

    fun load(bookId: String, fileUriString: String) {
        viewModelScope.launch {
            val savedLocator = locatorRepository.get(bookId)

            pubRepository.open(android.net.Uri.parse(fileUriString))
                .onSuccess { publication ->
                    bookDao.updateLastOpened(bookId, System.currentTimeMillis())
                    _state.value = State.Ready(
                        publication = publication,
                        navigatorFactory = EpubNavigatorFactory(publication),
                        initialLocator = savedLocator,
                    )
                }
                .onFailure { error ->
                    _state.value = State.Error(error.message ?: "Failed to open publication")
                }
        }
    }

    fun saveLocator(bookId: String, locator: Locator) {
        viewModelScope.launch {
            locatorRepository.save(bookId, locator)
        }
    }

    override fun onCleared() {
        super.onCleared()
        (_state.value as? State.Ready)?.publication?.close()
    }
}
