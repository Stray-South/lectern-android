package com.straysouth.lectern.ui.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.straysouth.lectern.R

/**
 * V2.2.2 — modal dialog for entering note body text.
 *
 * Opened by the reader toolbar's "Note" action after the selection
 * locator has been resolved. The selection's locator is owned by the
 * caller; on save the dialog returns the body text and the caller
 * pairs it with the locator to call `viewModel.createNote(locator, body)`.
 *
 * AuDHD design rules: dismiss button always present; no auto-dismiss on
 * blank input (the Save button just stays disabled). No streak / urgency
 * copy.
 *
 * Blank or whitespace-only input disables Save. The caller never gets
 * a blank body — V2.2.2 distinguishes notes from highlights by the
 * presence of body text, so an empty note would be schema-incoherent.
 */
@Composable
internal fun NoteEntryDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var body by remember { mutableStateOf("") }
    val canSave = body.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_dialog_title)) },
        text = {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.note_dialog_body_label)) },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(body.trim()) },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.note_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.note_dialog_cancel))
            }
        },
    )
}
