package com.straysouth.lectern.gaze

/**
 * Ridge regression weights for screen-X and screen-Y prediction.
 *
 * Feature vector per point: [u, v, u², v², u·v, 1]  (6 features)
 * where (u, v) = average iris center in normalised image space.
 *
 * weightsX · features → predicted screenX
 * weightsY · features → predicted screenY
 */
data class CalibrationResult(
    val weightsX: DoubleArray,
    val weightsY: DoubleArray,
    /** Mean Euclidean error across calibration points in screen pixels. 0 if unknown. */
    val meanErrorPx: Float = 0f,
) {
    // DoubleArray equality is structural, not referential.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationResult) return false
        return weightsX.contentEquals(other.weightsX) &&
            weightsY.contentEquals(other.weightsY) &&
            meanErrorPx.compareTo(other.meanErrorPx) == 0
    }

    override fun hashCode(): Int {
        var result = weightsX.contentHashCode()
        result = 31 * result + weightsY.contentHashCode()
        result = 31 * result + meanErrorPx.hashCode()
        return result
    }
}
