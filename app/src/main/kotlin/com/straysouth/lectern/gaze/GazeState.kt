package com.straysouth.lectern.gaze

import android.graphics.PointF

/**
 * Represents the current gaze tracking output.
 *
 * NoFace and Paused NEVER trigger any UI intervention — holding the last
 * known position or rendering nothing is the correct response. This matches
 * the iOS ADR-J constraint: absence of face detection is a non-event.
 */
sealed class GazeState {
    /** Face detected; calibrated screen-space gaze point available. */
    data class Tracking(val gazePoint: PointF) : GazeState()

    /** No face detected in the current frame. Hold last state silently. */
    object NoFace : GazeState()

    /** Gaze pipeline paused — thermal throttle, gaze disabled, or not yet calibrated. */
    object Paused : GazeState()
}
