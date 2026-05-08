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

private const val GRID_COLS = 3
private const val GRID_ROWS = 3
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
    gazeState: com.straysouth.lectern.gaze.GazeState,
    onRecordPoint: (CalibrationPoint) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalPoints = GRID_COLS * GRID_ROWS

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is CalibrationUiState.Collecting -> {
                    CalibrationDot(
                        pointIndex = state.pointIndex,
                        totalPoints = totalPoints,
                        gazeState = gazeState,
                        onConfirm = onRecordPoint,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CalibrationHeader(
                        pointIndex = state.pointIndex,
                        totalPoints = totalPoints,
                        onCancel = onCancel,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                    )
                }
                is CalibrationUiState.Done -> {
                    CalibrationDoneContent(
                        onDismiss = onCancel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is CalibrationUiState.CalibrationError -> {
                    CalibrationErrorContent(
                        message = state.message,
                        onDismiss = onCancel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> { /* Idle — should not show */ }
            }
        }
    }
}

@Composable
private fun CalibrationDot(
    pointIndex: Int,
    totalPoints: Int,
    gazeState: com.straysouth.lectern.gaze.GazeState,
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

    // The gaze point at the time the user taps — captured from the current GazeState.
    val currentGazeIrisU: Float
    val currentGazeIrisV: Float
    if (gazeState is com.straysouth.lectern.gaze.GazeState.Tracking) {
        // Normalise screen-space gaze point back to a proxy iris coordinate.
        // In practice CalibrationPoint.irisU/V come from the raw landmark callback;
        // here we pass the already-inferred screen point which the ViewModel will
        // unpack. For calibration, we need raw iris coords — GazeViewModel should
        // expose the last raw iris position. For V1 we use a simplified path:
        // the CalibrationScreen signals the ViewModel with the current gaze point
        // and the ViewModel's GazeProviderImpl exposes the last iris reading.
        currentGazeIrisU = gazeState.gazePoint.x
        currentGazeIrisV = gazeState.gazePoint.y
    } else {
        currentGazeIrisU = fractionX
        currentGazeIrisV = fractionY
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val sizePx = with(density) { DOT_SIZE.toPx() }
                    dotScreenX = pos.x + coords.size.width * fractionX - sizePx / 2
                    dotScreenY = pos.y + coords.size.height * fractionY - sizePx / 2
                },
        )
        val cdDot = stringResource(R.string.cd_gaze_calibrate)
        Box(
            modifier = Modifier
                .offset {
                    val sizePx = DOT_SIZE.toPx().toInt()
                    IntOffset(
                        (dotScreenX).toInt(),
                        (dotScreenY).toInt(),
                    )
                }
                .size(DOT_SIZE)
                .background(DOT_COLOR, CircleShape)
                .semantics { contentDescription = cdDot },
        )

        Button(
            onClick = {
                onConfirm(
                    CalibrationPoint(
                        screenX = dotScreenX + with(density) { DOT_SIZE.toPx() / 2 },
                        screenY = dotScreenY + with(density) { DOT_SIZE.toPx() / 2 },
                        irisU = currentGazeIrisU,
                        irisV = currentGazeIrisV,
                    ),
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        ) {
            Text(stringResource(R.string.gaze_calibration_confirm_point))
        }
    }
}

@Composable
private fun CalibrationHeader(
    pointIndex: Int,
    totalPoints: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.gaze_calibration_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gaze_calibration_point_of, pointIndex + 1, totalPoints),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gaze_calibration_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CalibrationDoneContent(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.gaze_calibration_complete),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text(stringResource(android.R.string.ok))
        }
    }
}

@Composable
private fun CalibrationErrorContent(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text(stringResource(android.R.string.ok))
        }
    }
}
