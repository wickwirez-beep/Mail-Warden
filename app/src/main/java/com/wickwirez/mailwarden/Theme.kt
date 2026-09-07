package com.wickwirez.mailwarden

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WardenRed = Color(0xFFC1121F)
private val WardenRedDark = Color(0xFF7A0B14)
private val Steel = Color(0xFFD6D6D6)
private val SteelDim = Color(0xFF9A9A9A)
private val Charcoal = Color(0xFF1A1A1C)
private val CharcoalLight = Color(0xFF262629)

private val WardenColors = darkColorScheme(
    primary = WardenRed,
    onPrimary = Color.White,
    primaryContainer = WardenRedDark,
    onPrimaryContainer = Steel,
    secondary = SteelDim,
    onSecondary = Color.Black,
    secondaryContainer = CharcoalLight,
    onSecondaryContainer = Steel,
    background = Charcoal,
    onBackground = Steel,
    surface = Charcoal,
    onSurface = Steel,
    surfaceVariant = CharcoalLight,
    onSurfaceVariant = SteelDim,
    outline = SteelDim,
    error = WardenRed,
    onError = Color.White
)

@Composable
fun MailWardenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WardenColors,
        content = content
    )
}
