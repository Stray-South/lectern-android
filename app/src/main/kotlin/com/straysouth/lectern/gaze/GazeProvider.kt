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
 */
interface GazeProvider {
    val gazeState: StateFlow<GazeState>
    suspend fun start()
    suspend fun stop()
    suspend fun calibrate(points: List<CalibrationPoint>): CalibrationResult
}
