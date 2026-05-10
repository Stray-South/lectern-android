package com.straysouth.lectern.ui.gaze

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.straysouth.lectern.data.repository.CalibrationRepository
import com.straysouth.lectern.gaze.CalibrationPoint
import com.straysouth.lectern.gaze.CalibrationResult
import com.straysouth.lectern.gaze.GazeProvider
import com.straysouth.lectern.gaze.GazeProviderImpl
import com.straysouth.lectern.gaze.GazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleOwner

private const val TAG = "GazeViewModel"

/** Calibration UI state machine. */
sealed class CalibrationUiState {
    object Idle : CalibrationUiState()
    data class Collecting(val pointIndex: Int, val totalPoints: Int) : CalibrationUiState()
    data class Done(val result: CalibrationResult) : CalibrationUiState()
    data class CalibrationError(val message: String) : CalibrationUiState()
}

/**
 * Activity-scoped ViewModel — single CameraX session shared across reader fragments.
 * GazeProviderImpl is created lazily on first toggleGaze() call.
 *
 * Permission check is delegated to the caller: the Fragment must hold CAMERA
 * permission before calling toggleGaze(). Use needsPermission StateFlow to
 * signal that a runtime request is required.
 */
class GazeViewModel(application: Application) : AndroidViewModel(application) {

    private val calibrationRepository = CalibrationRepository(application)

    private val _gazeEnabled = MutableStateFlow(false)
    val gazeEnabled: StateFlow<Boolean> = _gazeEnabled.asStateFlow()

    private val _gazeState = MutableStateFlow<GazeState>(GazeState.Paused)
    val gazeState: StateFlow<GazeState> = _gazeState.asStateFlow()

    private val _calibrationUiState = MutableStateFlow<CalibrationUiState>(CalibrationUiState.Idle)
    val calibrationUiState: StateFlow<CalibrationUiState> = _calibrationUiState.asStateFlow()

    // True when gaze was toggled on but camera permission is not yet granted.
    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    // Set by MainActivity after the ViewModel is obtained — avoids ProcessLifecycleOwner dependency.
    private var lifecycleOwner: LifecycleOwner? = null
    private var provider: GazeProvider? = null
    private val pendingCalibrationPoints = mutableListOf<CalibrationPoint>()
    // True while TTS is playing and has suppressed gaze inference. Cleared by stopGazeInternal()
    // so a manual gaze-off during TTS does not leave the flag dangling.
    private var gazePausedByTts = false

    /** Called from MainActivity.onCreate() — must be set before toggleGaze(). */
    fun attachLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun onPermissionResult(granted: Boolean) {
        _needsPermission.value = false
        if (granted) startGazeInternal() else _gazeEnabled.value = false
    }

    /**
     * Toggles gaze on or off. Caller must verify CAMERA permission first.
     * If permission is absent, sets needsPermission=true and waits for
     * onPermissionResult() to be called.
     *
     * @param hasPermission pass the result of checkSelfPermission(CAMERA) == GRANTED
     */
    fun toggleGaze(hasPermission: Boolean) {
        if (_gazeEnabled.value) {
            stopGazeInternal()
        } else {
            if (hasPermission) {
                startGazeInternal()
            } else {
                _needsPermission.value = true
            }
        }
    }

    /**
     * Called by EpubReaderFragment's TTS bridge when TTS starts playing.
     * Stops frame delivery without tearing down CameraX or the GPU delegate (~0 ms).
     * No-op if gaze is disabled or already paused by TTS.
     */
    fun pauseForTts() {
        if (_gazeEnabled.value && !gazePausedByTts) {
            provider?.pauseAnalysis()
            gazePausedByTts = true
        }
    }

    /**
     * Called by EpubReaderFragment's TTS bridge when TTS pauses or stops.
     * Restores frame delivery; state updates to Tracking on next analyzed frame.
     * No-op if gaze was not paused by TTS.
     */
    fun resumeFromTts() {
        if (gazePausedByTts) {
            gazePausedByTts = false
            provider?.resumeAnalysis()
        }
    }

    fun startCalibration(totalPoints: Int) {
        pendingCalibrationPoints.clear()
        _calibrationUiState.value = CalibrationUiState.Collecting(0, totalPoints)
    }

    fun recordCalibrationPoint(point: CalibrationPoint, totalPoints: Int) {
        pendingCalibrationPoints.add(point)
        val next = pendingCalibrationPoints.size
        if (next < totalPoints) {
            _calibrationUiState.value = CalibrationUiState.Collecting(next, totalPoints)
        } else {
            finishCalibration()
        }
    }

    fun cancelCalibration() {
        pendingCalibrationPoints.clear()
        _calibrationUiState.value = CalibrationUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        stopGazeInternal()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun startGazeInternal() {
        val owner = lifecycleOwner
        if (owner == null) {
            Log.e(TAG, "LifecycleOwner not attached — call attachLifecycleOwner() from MainActivity first")
            return
        }
        _gazeEnabled.value = true
        val p = GazeProviderImpl(
            context = getApplication(),
            lifecycleOwner = owner,
            calibrationRepository = calibrationRepository,
        )
        provider = p
        // Collect gaze state updates.
        viewModelScope.launch {
            p.gazeState.collect { _gazeState.value = it }
        }
        // Start provider (binds CameraX, creates FaceLandmarker).
        viewModelScope.launch {
            try {
                p.start()
            } catch (e: java.io.IOException) {
                Log.e(TAG, "GazeProvider.start() failed", e)
                _gazeEnabled.value = false
            } catch (e: SecurityException) {
                // Thrown by CameraX when CAMERA permission is revoked at runtime (Android 11+).
                // The process is not killed on revocation — CameraX throws on next bind attempt.
                Log.e(TAG, "GazeProvider.start() failed — camera permission revoked", e)
                _gazeEnabled.value = false
            }
        }
    }

    private fun stopGazeInternal() {
        gazePausedByTts = false   // clear TTS flag so re-enable works cleanly
        _gazeEnabled.value = false
        _gazeState.value = GazeState.Paused
        viewModelScope.launch {
            try {
                provider?.stop()
            } catch (e: java.io.IOException) {
                Log.e(TAG, "GazeProvider.stop() failed", e)
            }
            provider = null
        }
    }

    private fun finishCalibration() {
        val points = pendingCalibrationPoints.toList()
        pendingCalibrationPoints.clear()
        viewModelScope.launch {
            try {
                val result = requireNotNull(provider) { "GazeProvider not started" }
                    .calibrate(points)
                _calibrationUiState.value = CalibrationUiState.Done(result)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Calibration failed", e)
                _calibrationUiState.value = CalibrationUiState.CalibrationError(e.message ?: "Calibration failed")
            } catch (e: IllegalStateException) {
                // Thrown by ridge() when calibration data is degenerate (not positive definite).
                Log.e(TAG, "Calibration failed — degenerate data", e)
                _calibrationUiState.value = CalibrationUiState.CalibrationError(e.message ?: "Calibration failed")
            }
        }
    }
}

