package com.straysouth.lectern.gaze

import kotlinx.coroutines.flow.StateFlow

/**
 * Provides calibrated gaze state from the front camera.
 *
 * Threading: implementations use Dispatchers.Default.limitedParallelism(1)
 * for all state mutation (serial confined dispatcher — RULES.md §Threading).
 *
 * start() / stop() are suspending and must be called from a coroutine scope.
 * calibrate() blocks until all calibration points are collected and the
 * ridge regression model is fit.
 *
 * pauseAnalysis() / resumeAnalysis() are synchronous and cheap (~0 ms).
 * They stop/restart frame delivery to the FaceLandmarker without tearing
 * down CameraX bindings or the GPU delegate — used by the TTS bridge to
 * cut CPU/thermal load while speech is playing.
 */
interface GazeProvider {
    val gazeState: StateFlow<GazeState>
    suspend fun start()
    suspend fun stop()
    suspend fun calibrate(points: List<CalibrationPoint>): CalibrationResult
    /** Stop frame delivery to FaceLandmarker. Camera stays bound; GPU delegate stays warm. */
    fun pauseAnalysis()
    /** Restart frame delivery after pauseAnalysis(). State updates on next analyzed frame. */
    fun resumeAnalysis()
}
