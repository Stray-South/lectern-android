package com.straysouth.lectern.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.repository.TtsPrefs

private val SPEED_OPTIONS = listOf(0.5, 1.0, 1.5, 2.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsBar(
    state: TtsUiState,
    prefs: TtsPrefs,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayPauseButton(state = state, onPlay = onPlay, onPause = onPause)

            AnimatedVisibility(visible = state is TtsUiState.Active) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.cd_tts_stop),
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            SpeedChips(currentSpeed = prefs.speed, onSpeedChange = onSpeedChange)
        }
    }
}

@Composable
private fun PlayPauseButton(state: TtsUiState, onPlay: () -> Unit, onPause: () -> Unit) {
    val isPlaying = state is TtsUiState.Active && state.isPlaying
    IconButton(
        onClick = if (isPlaying) onPause else onPlay,
        modifier = Modifier.size(48.dp),
    ) {
        if (isPlaying) {
            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.cd_tts_pause))
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_tts_play))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedChips(currentSpeed: Double, onSpeedChange: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SPEED_OPTIONS.forEach { speed ->
            FilterChip(
                selected = currentSpeed == speed,
                onClick = { onSpeedChange(speed) },
                label = { Text(text = formatSpeed(speed)) },
                modifier = Modifier.heightIn(min = 32.dp),
            )
        }
    }
}

private fun formatSpeed(speed: Double): String = when (speed) {
    0.5 -> "½×"
    1.0 -> "1×"
    1.5 -> "1½×"
    2.0 -> "2×"
    else -> "${speed}×"
}
