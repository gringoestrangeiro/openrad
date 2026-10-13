package com.openrad.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed colors from the eframe desktop (desktop/src/app.rs). Do not change without reason.
val Bg = Color(0xFF0D1219)
val Surface = Color(0xFF141B25)
val Raised = Color(0xFF1A232F)
val Border = Color(0xFF273341)
val TextMain = Color(0xFFE6EDF4)
val Muted = Color(0xFF8D9DB0)
val Mint = Color(0xFF62E2B5)
val Blue = Color(0xFF84A4FF)
val Amber = Color(0xFFF4BE64)
val Red = Color(0xFFF8828F)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF0D1219),
    secondary = Blue,
    tertiary = Amber,
    error = Red,
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Border,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7A5F),
    secondary = Color(0xFF3B5BDB),
    tertiary = Color(0xFF9A6B1A),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun OpenRadTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
