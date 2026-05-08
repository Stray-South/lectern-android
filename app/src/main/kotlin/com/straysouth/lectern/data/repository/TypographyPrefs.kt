package com.straysouth.lectern.data.repository

data class TypographyPrefs(
    // "default" | "serif" | "sans" | "dyslexic"
    val fontFamily: String = "default",
    // Readium fontSize multiplier: 1.0 = 100 %
    val fontSize: Double = 1.0,
    // CSS line-height multiplier (unitless); WCAG 1.4.12 minimum is 1.5
    val lineHeight: Double = 1.5,
    // "light" | "sepia" | "dark"
    val theme: String = "light",
)
