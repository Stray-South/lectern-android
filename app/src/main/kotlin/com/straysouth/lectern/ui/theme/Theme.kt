package com.straysouth.lectern.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WarmOffWhite = Color(0xFFFAF8F4)
private val NearBlack    = Color(0xFF1C1B17)

// Light scheme primary: #5C4AE4 — 5.57:1 on WarmOffWhite (AA), 5.90:1 White-on-button (AA)
private val VioletLight  = Color(0xFF5C4AE4)
// Dark scheme primary: #B4A5FF — 8.16:1 on DarkSurface (AAA), 8.04:1 NearBlack-on-button (AAA)
private val VioletDark   = Color(0xFFB4A5FF)

private val LightColors = lightColorScheme(
    background   = WarmOffWhite,
    surface      = WarmOffWhite,
    onBackground = NearBlack,
    onSurface    = NearBlack,
    primary      = VioletLight,
    onPrimary    = Color.White,
)

private val DarkColors = darkColorScheme(
    background   = Color(0xFF000000),
    surface      = Color(0xFF1A1A1A),
    onBackground = Color(0xFFC8C2B5),
    onSurface    = Color(0xFFC8C2B5),
    primary      = VioletDark,
    onPrimary    = NearBlack,
)

@Composable
fun LecternTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
