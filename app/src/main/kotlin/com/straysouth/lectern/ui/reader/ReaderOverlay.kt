package com.straysouth.lectern.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R

@Composable
internal fun ReaderOverlay(
    state: EpubReaderViewModel.State,
    onBack: () -> Unit,
) {
    when (state) {
        EpubReaderViewModel.State.Loading -> {
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
        is EpubReaderViewModel.State.Error -> {
            val cdError = stringResource(R.string.cd_reader_error)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = cdError },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.btn_back_to_library))
                    }
                }
            }
        }
        is EpubReaderViewModel.State.Ready -> Unit
    }
}
