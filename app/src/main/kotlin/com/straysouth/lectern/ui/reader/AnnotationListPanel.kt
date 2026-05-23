package com.straysouth.lectern.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.db.Annotation
import com.straysouth.lectern.data.repository.AnnotationRepository
import java.text.DateFormat
import java.util.Date

/**
 * V2.2.2 — annotation list panel (ModalBottomSheet).
 *
 * Lists all annotations for the open book, ordered by `createdAt` ASC (same
 * order DAO returns). Per-row:
 *   - Type icon (`FormatColorFill` for highlight, `Notes` for note)
 *   - Body text if note, locator preview if highlight
 *   - Created date (locale-formatted)
 *   - Delete IconButton (parent surfaces an Indefinite Snackbar with Undo
 *     per AuDHD G.3; this Composable just signals the request)
 *
 * Tap a row → `onNavigateTo(annotation)` (parent triggers
 * `viewModel.requestNavigation(locator)` and closes the panel).
 *
 * No empty-state distinction — an empty list shows a single "no
 * annotations yet" line. AuDHD design rule: never hide affordances.
 *
 * Extracted into its own file (not inlined in ReaderOverlay) so the
 * existing detekt `TooManyFunctions` and `LongMethod` thresholds in
 * ReaderOverlay don't tighten further.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationListPanel(
    annotations: List<Annotation>,
    onNavigateTo: (Annotation) -> Unit,
    onDelete: (Annotation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.annotation_list_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (annotations.isEmpty()) {
                Text(
                    text = stringResource(R.string.annotation_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(annotations, key = { it.id }) { annotation ->
                        AnnotationRow(
                            annotation = annotation,
                            onTap = { onNavigateTo(annotation) },
                            onDelete = { onDelete(annotation) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(
    annotation: Annotation,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cdDelete = stringResource(R.string.cd_annotation_delete)
    val isNote = annotation.type == AnnotationRepository.TYPE_NOTE
    val cdRowType = stringResource(
        if (isNote) R.string.cd_annotation_type_note
        else R.string.cd_annotation_type_highlight,
    )
    val displayText = annotation.body
        ?: stringResource(R.string.annotation_row_highlight_placeholder)
    val createdLabel = DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(annotation.createdAt))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(annotation.id) {
                detectTapGestures(onTap = { onTap() })
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isNote) Icons.Filled.Notes else Icons.Filled.FormatColorFill,
            contentDescription = cdRowType,
            modifier = Modifier
                .size(24.dp)
                .padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                text = createdLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = cdDelete,
            )
        }
    }
}
