package com.straysouth.lectern.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.repository.FocusBandPrefs
import com.straysouth.lectern.data.repository.TtsPrefs
import com.straysouth.lectern.data.repository.TypographyPrefs
import com.straysouth.lectern.gaze.GazeState

@Composable
internal fun ReaderOverlay(
    state: EpubReaderViewModel.State,
    typographyPrefs: TypographyPrefs,
    ttsUiState: TtsUiState,
    ttsPrefs: TtsPrefs,
    focusBandPrefs: FocusBandPrefs,
    anchorActive: Boolean,
    gazeState: GazeState,
    gazeEnabled: Boolean,
    onBack: () -> Unit,
    onTypographyChange: (TypographyPrefs) -> Unit,
    onTtsPlay: () -> Unit,
    onTtsPause: () -> Unit,
    onTtsStop: () -> Unit,
    onTtsSpeedChange: (Double) -> Unit,
    onFocusBandChange: (FocusBandPrefs) -> Unit,
    onDismissTtsUnavailable: () -> Unit,
    onAnchorDismiss: () -> Unit,
    onGazeToggle: () -> Unit,
    onCalibrate: () -> Unit,
    // V2.2 — invoked when the user taps the "Highlight" toolbar action. The
    // Fragment resolves the WebView's current selection (suspend) and persists
    // an Annotation; this Composable just signals the request.
    onHighlight: () -> Unit,
) {
    when (state) {
        EpubReaderViewModel.State.Loading -> LoadingOverlay()
        is EpubReaderViewModel.State.Error -> ErrorOverlay(message = state.message, onBack = onBack)
        is EpubReaderViewModel.State.Ready -> ReadyOverlay(
            typographyPrefs = typographyPrefs,
            ttsUiState = ttsUiState,
            ttsPrefs = ttsPrefs,
            focusBandPrefs = focusBandPrefs,
            anchorActive = anchorActive,
            gazeState = gazeState,
            gazeEnabled = gazeEnabled,
            onBack = onBack,
            onTypographyChange = onTypographyChange,
            onTtsPlay = onTtsPlay,
            onTtsPause = onTtsPause,
            onTtsStop = onTtsStop,
            onTtsSpeedChange = onTtsSpeedChange,
            onFocusBandChange = onFocusBandChange,
            onDismissTtsUnavailable = onDismissTtsUnavailable,
            onAnchorDismiss = onAnchorDismiss,
            onGazeToggle = onGazeToggle,
            onCalibrate = onCalibrate,
            onHighlight = onHighlight,
        )
    }
}

@Composable
private fun LoadingOverlay() {
    val cdLoading = stringResource(R.string.cd_reader_loading)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cdLoading },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorOverlay(message: String, onBack: () -> Unit) {
    val cdError = stringResource(R.string.cd_reader_error)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cdError },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.btn_back_to_library))
            }
        }
    }
}

@Composable
private fun ReadyOverlay(
    typographyPrefs: TypographyPrefs,
    ttsUiState: TtsUiState,
    ttsPrefs: TtsPrefs,
    focusBandPrefs: FocusBandPrefs,
    anchorActive: Boolean,
    gazeState: GazeState,
    gazeEnabled: Boolean,
    onBack: () -> Unit,
    onTypographyChange: (TypographyPrefs) -> Unit,
    onTtsPlay: () -> Unit,
    onTtsPause: () -> Unit,
    onTtsStop: () -> Unit,
    onTtsSpeedChange: (Double) -> Unit,
    onFocusBandChange: (FocusBandPrefs) -> Unit,
    onDismissTtsUnavailable: () -> Unit,
    onAnchorDismiss: () -> Unit,
    onGazeToggle: () -> Unit,
    onCalibrate: () -> Unit,
    // V2.2 — invoked when the user taps the "Highlight" toolbar action. The
    // Fragment resolves the WebView's current selection (suspend) and persists
    // an Annotation; this Composable just signals the request.
    onHighlight: () -> Unit,
) {
    var showPanel by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        // V1 pixel overlay — drawn first so toolbar/TtsBar render above it.
        // ADR-AND-L Sprint 13 amendment: semi-transparent band at gazePoint.y.
        GazeFocusBandOverlay(
            gazeState = gazeState,
            enabled = focusBandPrefs.gazeOverlayEnabled,
            modifier = Modifier.fillMaxSize(),
        )

        ReaderToolbar(
            anchorActive = anchorActive,
            gazeState = gazeState,
            gazeEnabled = gazeEnabled,
            onBack = onBack,
            onTypography = { showPanel = true },
            onAnchorDismiss = onAnchorDismiss,
            onGazeToggle = onGazeToggle,
            onCalibrate = onCalibrate,
            onHighlight = onHighlight,
            modifier = Modifier.align(Alignment.TopStart),
        )

        TtsBar(
            state = ttsUiState,
            prefs = ttsPrefs,
            focusBandPrefs = focusBandPrefs,
            onPlay = onTtsPlay,
            onPause = onTtsPause,
            onStop = onTtsStop,
            onSpeedChange = onTtsSpeedChange,
            onFocusBandChange = onFocusBandChange,
            onDismissUnavailable = onDismissTtsUnavailable,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )

        if (showPanel) {
            TypographyPanel(
                prefs = typographyPrefs,
                onPrefsChange = onTypographyChange,
                onDismiss = { showPanel = false },
            )
        }
    }
}

// Warm amber at ~15% alpha — matches FOCUS_BAND_TINT palette in EpubReaderFragment.
private val GAZE_BAND_COLOR = Color(0x26FFE082)
private val GAZE_BAND_HEIGHT = 52.dp

/**
 * V1 gaze→line overlay (ADR-AND-L Sprint 13 amendment).
 * Draws a semi-transparent horizontal band at calibrated gazePoint.y.
 * Not line-semantically aware — pure pixel overlay over the WebView.
 *
 * TODO(ADR-AND-L): Focus Band V2 — deferred to V3.
 * When a native BasicText surface exists, replace with:
 *   bandCenterY = gazePoint.y → getLineForOffset → drawRect dim above/below
 */
@Composable
private fun GazeFocusBandOverlay(
    gazeState: GazeState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val tracking = gazeState as? GazeState.Tracking ?: return
    val bandY = tracking.gazePoint.y
    val bandHeightPx = with(LocalDensity.current) { GAZE_BAND_HEIGHT.toPx() }
    Canvas(modifier = modifier) {
        val halfBand = bandHeightPx / 2f
        drawRect(
            color = GAZE_BAND_COLOR,
            topLeft = Offset(0f, bandY - halfBand),
            size = Size(size.width, bandHeightPx),
        )
    }
}

@Composable
private fun ReaderToolbar(
    anchorActive: Boolean,
    gazeState: GazeState,
    gazeEnabled: Boolean,
    onBack: () -> Unit,
    onTypography: () -> Unit,
    onAnchorDismiss: () -> Unit,
    onGazeToggle: () -> Unit,
    onCalibrate: () -> Unit,
    // V2.2 — user taps to highlight the currently-selected text in the WebView.
    onHighlight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Semi-opaque so the reader text remains visible beneath.
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            val cdBack = stringResource(R.string.cd_back)
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = cdBack)
            }
            val cdTypography = stringResource(R.string.cd_typography)
            IconButton(onClick = onTypography, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.TextFormat, contentDescription = cdTypography)
            }
            // Gaze toggle — filled eye = tracking active, outlined = off.
            val cdGaze = stringResource(R.string.cd_gaze_toggle)
            val isTracking = gazeEnabled && gazeState is GazeState.Tracking
            IconButton(onClick = onGazeToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (isTracking) Icons.Filled.RemoveRedEye
                                  else Icons.Outlined.RemoveRedEye,
                    contentDescription = cdGaze,
                    tint = if (isTracking) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface,
                )
            }
            // Calibrate button — visible only when gaze is enabled (camera already running).
            AnimatedVisibility(
                visible = gazeEnabled,
                enter = fadeIn(androidx.compose.animation.core.tween(200)),
                exit = fadeOut(androidx.compose.animation.core.tween(200)),
            ) {
                val cdCalibrate = stringResource(R.string.cd_gaze_calibrate_start)
                IconButton(onClick = onCalibrate, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = cdCalibrate)
                }
            }
            // Anchor dismiss — visible with ≤200ms fade per AuDHD design rules.
            AnimatedVisibility(
                visible = anchorActive,
                enter = fadeIn(androidx.compose.animation.core.tween(200)),
                exit = fadeOut(androidx.compose.animation.core.tween(200)),
            ) {
                val cdAnchor = stringResource(R.string.cd_anchor_dismiss)
                IconButton(onClick = onAnchorDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (anchorActive) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = cdAnchor,
                    )
                }
            }
            // V2.2 — Highlight button. Always visible (user always able to highlight).
            // No selection-active gating: the suspend currentSelection() in the Fragment
            // resolves to null if there's no active selection and the call no-ops.
            val cdHighlight = stringResource(R.string.cd_highlight)
            IconButton(onClick = onHighlight, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.FormatColorFill,
                    contentDescription = cdHighlight,
                )
            }
        }
    }
}
