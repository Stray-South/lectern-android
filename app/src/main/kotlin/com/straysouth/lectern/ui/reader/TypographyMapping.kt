package com.straysouth.lectern.ui.reader

import com.straysouth.lectern.data.repository.TypographyPrefs
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

// Maps our persisted TypographyPrefs to Readium's EpubPreferences.
// "default" fontFamily → null so Readium uses publisher CSS font.
internal fun TypographyPrefs.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontFamily = when (fontFamily) {
        "serif" -> FontFamily("Georgia, serif")
        "sans" -> FontFamily("Helvetica Neue, sans-serif")
        "dyslexic" -> FontFamily.OPEN_DYSLEXIC
        else -> null
    },
    fontSize = fontSize,
    lineHeight = lineHeight,
    theme = when (theme) {
        "sepia" -> Theme.SEPIA
        "dark" -> Theme.DARK
        else -> Theme.LIGHT
    },
)
