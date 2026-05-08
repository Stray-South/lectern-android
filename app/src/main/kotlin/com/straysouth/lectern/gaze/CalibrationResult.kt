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
) {
    // DoubleArray equality is structural, not referential.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationResult) return false
        return weightsX.contentEquals(other.weightsX) &&
            weightsY.contentEquals(other.weightsY)
    }

    override fun hashCode(): Int = 31 * weightsX.contentHashCode() + weightsY.contentHashCode()
}
