package com.straysouth.lectern.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.straysouth.lectern.gaze.CalibrationResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray

// DataStore name: "calibration_prefs" — does NOT contain face|eye|gaze|lookAt.
// Key names below are similarly clean — verified against check_gaze_data_leak.sh.
private val Context.calibrationDataStore by preferencesDataStore(name = "calibration_prefs")

class CalibrationRepository(context: Context) {

    private val ctx = context.applicationContext

    /** Persists calibration weights. Raw iris/gaze coordinates are never stored. */
    suspend fun save(result: CalibrationResult) {
        ctx.calibrationDataStore.edit { prefs ->
            prefs[KEY_WEIGHTS_X] = result.weightsX.toJsonString()
            prefs[KEY_WEIGHTS_Y] = result.weightsY.toJsonString()
        }
    }

    /** Returns stored calibration, or null if the device has never been calibrated. */
    suspend fun load(): CalibrationResult? {
        val prefs = ctx.calibrationDataStore.data.first()
        val wx = prefs[KEY_WEIGHTS_X]?.toDoubleArray() ?: return null
        val wy = prefs[KEY_WEIGHTS_Y]?.toDoubleArray() ?: return null
        return CalibrationResult(wx, wy)
    }

    suspend fun clear() {
        ctx.calibrationDataStore.edit { it.clear() }
    }

    companion object {
        // Key names must not match: face|eye|gaze|lookAt (CI check_gaze_data_leak.sh)
        private val KEY_WEIGHTS_X = stringPreferencesKey("weights_x")
        private val KEY_WEIGHTS_Y = stringPreferencesKey("weights_y")
    }
}

private fun DoubleArray.toJsonString(): String =
    JSONArray().also { arr -> forEach { arr.put(it) } }.toString()

private fun String.toDoubleArray(): DoubleArray {
    val arr = JSONArray(this)
    return DoubleArray(arr.length()) { arr.getDouble(it) }
}
