package com.straysouth.lectern.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.ui.window.SecureWindow

/**
 * V2.3 — annotation review screen.
 *
 * Single-card display, two actions ("Got it" / "Review later"). No streak,
 * no daily goal, no urgency copy (RULES.md §AuDHD + check_banned_strings.sh).
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // V2.3 fix (cross-feature audit sev-1): review screen renders user-authored
    // annotation body text — ADR-AND-R V2 reconsideration trigger 1 fires for
    // this surface, same as the EPUB reader for the same reason. The
    // reference-counted WindowSecurityController keeps FLAG_SECURE set as long
    // as any sensitive screen is composed; clears on dispose.
    SecureWindow()
    val state by viewModel.state.collectAsState()
    val cdScreen = stringResource(R.string.cd_review_screen)

    LaunchedEffect(Unit) { viewModel.load() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = cdScreen },
    ) {
        when (val current = state) {
            ReviewUiState.Loading -> ReviewLoadingOverlay()
            ReviewUiState.Empty -> ReviewEmptyOverlay(onBack)
            ReviewUiState.Done -> ReviewDoneOverlay(onBack)
            is ReviewUiState.Ready -> ReviewReadyOverlay(
                state = current,
                onGotIt = viewModel::markReviewedAndAdvance,
                onLater = viewModel::skipCurrent,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ReviewLoadingOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReviewEmptyOverlay(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.review_empty_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.btn_back_to_library))
        }
    }
}

@Composable
private fun ReviewDoneOverlay(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.review_done_label),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.btn_back_to_library))
        }
    }
}

@Composable
private fun ReviewReadyOverlay(
    state: ReviewUiState.Ready,
    onGotIt: () -> Unit,
    onLater: () -> Unit,
    onBack: () -> Unit,
) {
    val cdBack = stringResource(R.string.cd_back)
    val ann = state.currentAnnotation ?: return

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = cdBack)
            }
            Text(
                text = stringResource(R.string.review_remaining, state.totalRemaining),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().weight(1f).padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val body = ann.body
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.review_highlight_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onLater,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.review_later_action))
            }
            Button(
                onClick = onGotIt,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.review_got_it_action))
            }
        }
    }
}
