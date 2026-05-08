package com.straysouth.lectern.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.straysouth.lectern.R
import com.straysouth.lectern.data.repository.TypographyPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypographyPanel(
    prefs: TypographyPrefs,
    onPrefsChange: (TypographyPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(stringResource(R.string.typography_font_family), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FontFamilyPicker(prefs, onPrefsChange)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.typography_font_size), style = MaterialTheme.typography.labelLarge)
            // 6 stops: 0.75, 1.0, 1.25, 1.5, 1.75, 2.0 → steps = 4 (intermediate only)
            Slider(
                value = prefs.fontSize.toFloat(),
                onValueChange = { onPrefsChange(prefs.copy(fontSize = it.toDouble())) },
                valueRange = 0.75f..2.0f,
                steps = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.typography_line_height), style = MaterialTheme.typography.labelLarge)
            // 5 stops: 1.2, 1.4, 1.6, 1.8, 2.0 → steps = 3
            Slider(
                value = prefs.lineHeight.toFloat(),
                onValueChange = { onPrefsChange(prefs.copy(lineHeight = it.toDouble())) },
                valueRange = 1.2f..2.0f,
                steps = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.typography_theme), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            ThemePicker(prefs, onPrefsChange)
        }
    }
}

@Composable
private fun FontFamilyPicker(prefs: TypographyPrefs, onPrefsChange: (TypographyPrefs) -> Unit) {
    val families = listOf("default", "serif", "sans", "dyslexic")
    val labels = listOf(
        stringResource(R.string.typography_font_default),
        stringResource(R.string.typography_font_serif),
        stringResource(R.string.typography_font_sans),
        stringResource(R.string.typography_font_dyslexic),
    )
    SegmentedButtonRow(
        options = labels,
        selectedIndex = families.indexOf(prefs.fontFamily).coerceAtLeast(0),
        onSelect = { onPrefsChange(prefs.copy(fontFamily = families[it])) },
    )
}

@Composable
private fun ThemePicker(prefs: TypographyPrefs, onPrefsChange: (TypographyPrefs) -> Unit) {
    val themes = listOf("light", "sepia", "dark")
    val labels = listOf(
        stringResource(R.string.typography_theme_light),
        stringResource(R.string.typography_theme_sepia),
        stringResource(R.string.typography_theme_dark),
    )
    SegmentedButtonRow(
        options = labels,
        selectedIndex = themes.indexOf(prefs.theme).coerceAtLeast(0),
        onSelect = { onPrefsChange(prefs.copy(theme = themes[it])) },
    )
}

@Composable
private fun SegmentedButtonRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            OutlinedButton(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = if (selectedIndex == i) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Text(text = label, maxLines = 1)
            }
        }
    }
}
