package com.straysouth.lectern.gaze

/**
 * One calibration sample: the known screen target paired with the iris
 * position observed while the user fixated that target.
 *
 * irisU / irisV are the average of both iris centers (landmarks 468 + 473),
 * normalised to [0, 1] in image space. Averaging both centers avoids the
 * left/right iris labeling ambiguity on front-camera mirrored images (ADR-AND-L).
 */
data class CalibrationPoint(
    val screenX: Float,
    val screenY: Float,
    val irisU: Float,
    val irisV: Float,
)
