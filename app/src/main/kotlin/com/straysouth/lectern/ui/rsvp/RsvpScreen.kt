package com.straysouth.lectern.ui.rsvp

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.repository.RsvpPrefs

/**
 * V2.4 — RSVP reader Composable. Single-word display driven by [RsvpViewModel].
 *
 * AuDHD design rules:
 *   - Always-visible Back affordance (Compose body surface; no hidden gestures).
 *   - Pause is a single tap on the central word; no chord, no long-press.
 *   - No streak / progress copy beyond a plain "X / Y" counter.
 */
@Composable
fun RsvpScreen(
    source: RsvpSource,
    viewModel: RsvpViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val prefs by viewModel.prefs.collectAsState()
    val cdScreen = stringResource(R.string.cd_rsvp_screen)

    LaunchedEffect(source) {
        viewModel.load(source)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = cdScreen },
    ) {
        when (val current = state) {
            RsvpUiState.Loading -> RsvpLoadingOverlay()
            is RsvpUiState.Error -> RsvpErrorOverlay(current.message, onBack)
            RsvpUiState.Done -> RsvpDoneOverlay(onBack)
            is RsvpUiState.Ready -> RsvpReadyOverlay(
                state = current,
                prefs = prefs,
                onPlayPause = viewModel::togglePlayPause,
                onSeek = viewModel::seek,
                onWpmChange = viewModel::updateWpm,
                onPauseOnPunctuationChange = viewModel::updatePauseOnPunctuation,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun RsvpLoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun RsvpErrorOverlay(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.btn_back_to_library))
        }
    }
}

@Composable
private fun RsvpDoneOverlay(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rsvp_done_label),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.btn_back_to_library))
        }
    }
}

@Composable
private fun RsvpReadyOverlay(
    state: RsvpUiState.Ready,
    prefs: RsvpPrefs,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onWpmChange: (Int) -> Unit,
    onPauseOnPunctuationChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val cdBack = stringResource(R.string.cd_back)
    val cdPlayPause = stringResource(
        if (state.isPlaying) R.string.cd_rsvp_pause else R.string.cd_rsvp_play,
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = cdBack)
            }
            Text(
                text = stringResource(R.string.rsvp_position, state.currentIndex + 1, state.totalWords),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.currentWord.orEmpty(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LinearProgressIndicator(
            progress = { (state.currentIndex + 1).toFloat() / state.totalWords.coerceAtLeast(1) },
            modifier = Modifier.fillMaxSize().height(4.dp).padding(vertical = 8.dp),
        )

        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = cdPlayPause,
                    modifier = Modifier.size(48.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rsvp_wpm_label, prefs.wpm),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = prefs.wpm.toFloat(),
                    onValueChange = { onWpmChange(it.toInt()) },
                    valueRange = RsvpPrefs.MIN_WPM.toFloat()..RsvpPrefs.MAX_WPM.toFloat(),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = prefs.pauseOnPunctuation,
                onCheckedChange = onPauseOnPunctuationChange,
            )
            Text(
                text = stringResource(R.string.rsvp_pause_on_punctuation_label),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Slider(
            value = state.currentIndex.toFloat(),
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..(state.totalWords - 1).coerceAtLeast(1).toFloat(),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
