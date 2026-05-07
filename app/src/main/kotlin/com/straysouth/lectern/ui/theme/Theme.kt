package com.straysouth.lectern.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WarmOffWhite = Color(0xFFFAF8F4)
private val NearBlack = Color(0xFF1C1B17)
private val AccentViolet = Color(0xFF907AFF)

private val LightColors = lightColorScheme(
    background = WarmOffWhite,
    surface = WarmOffWhite,
    onBackground = NearBlack,
    onSurface = NearBlack,
    primary = AccentViolet,
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF1A1A1A),
    onBackground = Color(0xFFC8C2B5),
    onSurface = Color(0xFFC8C2B5),
    primary = AccentViolet,
    onPrimary = Color.White,
)

@Composable
fun LecternTheme(
    darkTheme: Boolean = false, // User-controlled, not system-follow
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
