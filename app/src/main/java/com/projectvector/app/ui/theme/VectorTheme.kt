package com.projectvector.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object VectorColors {
    val Vector25 = Color(0xFFF9FBFF)
    val Vector50 = Color(0xFFF3F6FF)
    val Vector100 = Color(0xFFEDF2FE)
    val Vector200 = Color(0xFFCEDCFD)
    val Vector300 = Color(0xFFAFC7FC)
    val Vector400 = Color(0xFF729CFA)
    val Vector500 = Color(0xFF2E6CF7)
    val Vector600 = Color(0xFF052E8A)
    val Vector700 = Color(0xFF0846D1)
    val Vector800 = Color(0xFF031F5C)
    val Vector900 = Color(0xFF010819)
    val Success = Color(0xFF107A5B)
    val Warning = Color(0xFFA16207)
    val Danger = Color(0xFFB91C1C)
}

private val VectorLightScheme: ColorScheme = lightColorScheme(
    primary = VectorColors.Vector500,
    onPrimary = Color.White,
    primaryContainer = VectorColors.Vector100,
    onPrimaryContainer = VectorColors.Vector800,
    secondary = VectorColors.Vector700,
    onSecondary = Color.White,
    background = VectorColors.Vector25,
    onBackground = VectorColors.Vector900,
    surface = Color.White,
    onSurface = VectorColors.Vector900,
    error = VectorColors.Danger,
    onError = Color.White,
)

@Composable
fun VectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VectorLightScheme, content = content)
}
