package com.example.smartcartbilling.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary        = Blue600,
    onPrimary      = White,
    primaryContainer = Blue50,
    secondary      = Slate700,
    onSecondary    = White,
    background     = Background,
    onBackground   = Slate900,
    surface        = White,
    onSurface      = Slate900,
    surfaceVariant = Slate50,
    outline        = Slate200,
    tertiary       = Green600,
    tertiaryContainer = Green50
)

@Composable
fun SmartCartBillingTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = White.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = Typography,
        content     = content
    )
}