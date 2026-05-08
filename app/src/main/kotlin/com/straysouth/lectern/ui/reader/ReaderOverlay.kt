package com.straysouth.lectern.ui.reader

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
import androidx.compose.material.icons.filled.TextFormat
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.repository.TtsPrefs
import com.straysouth.lectern.data.repository.TypographyPrefs

@Composable
internal fun ReaderOverlay(
    state: EpubReaderViewModel.State,
    typographyPrefs: TypographyPrefs,
    ttsUiState: TtsUiState,
    ttsPrefs: TtsPrefs,
    onBack: () -> Unit,
    onTypographyChange: (TypographyPrefs) -> Unit,
    onTtsPlay: () -> Unit,
    onTtsPause: () -> Unit,
    onTtsStop: () -> Unit,
    onTtsSpeedChange: (Double) -> Unit,
) {
    when (state) {
        EpubReaderViewModel.State.Loading -> LoadingOverlay()
        is EpubReaderViewModel.State.Error -> ErrorOverlay(message = state.message, onBack = onBack)
        is EpubReaderViewModel.State.Ready -> ReadyOverlay(
            typographyPrefs = typographyPrefs,
            ttsUiState = ttsUiState,
            ttsPrefs = ttsPrefs,
            onBack = onBack,
            onTypographyChange = onTypographyChange,
            onTtsPlay = onTtsPlay,
            onTtsPause = onTtsPause,
            onTtsStop = onTtsStop,
            onTtsSpeedChange = onTtsSpeedChange,
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
    onBack: () -> Unit,
    onTypographyChange: (TypographyPrefs) -> Unit,
    onTtsPlay: () -> Unit,
    onTtsPause: () -> Unit,
    onTtsStop: () -> Unit,
    onTtsSpeedChange: (Double) -> Unit,
) {
    var showPanel by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        // Floating toolbar — semi-opaque so the reader text remains visible beneath.
        Surface(
            modifier = Modifier.align(Alignment.TopStart),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                val cdBack = stringResource(R.string.cd_back)
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = cdBack)
                }
                val cdTypography = stringResource(R.string.cd_typography)
                IconButton(onClick = { showPanel = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.TextFormat, contentDescription = cdTypography)
                }
            }
        }

        TtsBar(
            state = ttsUiState,
            prefs = ttsPrefs,
            onPlay = onTtsPlay,
            onPause = onTtsPause,
            onStop = onTtsStop,
            onSpeedChange = onTtsSpeedChange,
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
