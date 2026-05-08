package com.straysouth.lectern.ui.gaze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.gaze.CalibrationPoint
import com.straysouth.lectern.gaze.GazeState

private const val GRID_COLS = 3
internal const val CALIBRATION_TOTAL_POINTS = GRID_COLS * GRID_COLS
private val GRID_FRACTIONS = listOf(0.1f, 0.5f, 0.9f)
private val DOT_SIZE = 24.dp
private val DOT_COLOR = Color(0xFFFF6B35)  // warm orange — visible on all reader themes

/**
 * Full-screen calibration overlay. Shows one target dot at a time in a 3×3 grid.
 * The user fixates the dot then taps "Confirm" to record the sample.
 *
 * Screen coordinates are captured via onGloballyPositioned() so they reflect the
 * actual rendered position — correct across all screen sizes and densities.
 */
@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    gazeState: GazeState,
    onRecordPoint: (CalibrationPoint) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalPoints = GRID_COLS * GRID_COLS

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is CalibrationUiState.Collecting -> {
                    CalibrationDot(
                        pointIndex = state.pointIndex,
                        gazeState = gazeState,
                        onConfirm = onRecordPoint,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CalibrationHeader(
                        pointIndex = state.pointIndex,
                        totalPoints = totalPoints,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                    )
                }
                is CalibrationUiState.Done ->
                    CalibrationDoneContent(
                        onDismiss = onCancel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is CalibrationUiState.CalibrationError ->
                    CalibrationErrorContent(
                        message = state.message,
                        onDismiss = onCancel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                else -> { /* Idle — should not show */ }
            }
        }
    }
}

@Composable
private fun CalibrationDot(
    pointIndex: Int,
    gazeState: GazeState,
    onConfirm: (CalibrationPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val col = pointIndex % GRID_COLS
    val row = pointIndex / GRID_COLS
    val fractionX = GRID_FRACTIONS[col]
    val fractionY = GRID_FRACTIONS[row]
    val density = LocalDensity.current
    var dotScreenX by remember { mutableStateOf(0f) }
    var dotScreenY by remember { mutableStateOf(0f) }

    Box(modifier = modifier) {
        DotPositionMeasurer(fractionX, fractionY) { x, y -> dotScreenX = x; dotScreenY = y }
        val cdDot = stringResource(R.string.cd_gaze_calibrate)
        Box(
            modifier = Modifier
                .offset { IntOffset(dotScreenX.toInt(), dotScreenY.toInt()) }
                .size(DOT_SIZE)
                .background(DOT_COLOR, CircleShape)
                .semantics { contentDescription = cdDot },
        )
        Button(
            onClick = {
                // Read iris UV at tap time — avoids stale composition-time snapshot.
                val uv = gazeIrisUV(gazeState, fractionX, fractionY)
                val halfDot = with(density) { DOT_SIZE.toPx() / 2 }
                onConfirm(CalibrationPoint(dotScreenX + halfDot, dotScreenY + halfDot, uv.first, uv.second))
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        ) { Text(stringResource(R.string.gaze_calibration_confirm_point)) }
    }
}

/** Invisible full-size box that measures dot screen position on layout. */
@Composable
private fun DotPositionMeasurer(
    fractionX: Float,
    fractionY: Float,
    onPosition: (x: Float, y: Float) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val halfDotPx = with(density) { DOT_SIZE.toPx() / 2 }
                onPosition(
                    pos.x + coords.size.width * fractionX - halfDotPx,
                    pos.y + coords.size.height * fractionY - halfDotPx,
                )
            },
    )
}

private fun gazeIrisUV(state: GazeState, fallbackU: Float, fallbackV: Float): Pair<Float, Float> =
    // Use raw image-space iris UV — NOT gazePoint (calibrated screen pixels).
    if (state is GazeState.Tracking) state.irisU to state.irisV
    else fallbackU to fallbackV

@Composable
private fun CalibrationHeader(
    pointIndex: Int,
    totalPoints: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.gaze_calibration_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.gaze_calibration_point_of, pointIndex + 1, totalPoints),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.gaze_calibration_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CalibrationDoneContent(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.gaze_calibration_complete), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
    }
}

@Composable
private fun CalibrationErrorContent(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
    }
}
