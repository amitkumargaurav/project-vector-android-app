package com.projectvector.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.projectvector.app.R

@Composable
fun VectorTheme(content: @Composable () -> Unit) {
    val vectorLightScheme = lightColorScheme(
        primary = colorResource(R.color.vector_500),
        onPrimary = colorResource(android.R.color.white),
        primaryContainer = colorResource(R.color.vector_100),
        onPrimaryContainer = colorResource(R.color.vector_800),
        secondary = colorResource(R.color.vector_700),
        onSecondary = colorResource(android.R.color.white),
        background = colorResource(R.color.vector_25),
        onBackground = colorResource(R.color.vector_900),
        surface = colorResource(android.R.color.white),
        onSurface = colorResource(R.color.vector_900),
        error = colorResource(R.color.danger),
        onError = colorResource(android.R.color.white),
    )
    MaterialTheme(colorScheme = vectorLightScheme, content = content)
}
