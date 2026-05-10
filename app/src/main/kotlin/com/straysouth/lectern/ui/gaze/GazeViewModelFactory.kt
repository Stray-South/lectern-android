package com.straysouth.lectern.ui.gaze

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.straysouth.lectern.data.repository.CalibrationRepository

/**
 * Injects CalibrationRepository into GazeViewModel, decoupling the ViewModel
 * from constructing its own dependencies.
 */
class GazeViewModelFactory(
    private val application: Application,
    private val calibrationRepository: CalibrationRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == GazeViewModel::class.java) {
            "GazeViewModelFactory only creates GazeViewModel"
        }
        return GazeViewModel(application, calibrationRepository) as T
    }
}
