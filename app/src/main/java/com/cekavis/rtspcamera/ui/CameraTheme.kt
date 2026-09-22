package com.cekavis.rtspcamera.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBF3AD),
    onPrimary = Color(0xFF163810),
    primaryContainer = Color(0xFF284E21),
    onPrimaryContainer = Color(0xFFD5FFCA),
    secondary = Color(0xFFBCCCB6),
    background = Color(0xFF0B100D),
    onBackground = Color(0xFFE1E9DF),
    surface = Color(0xFF101711),
    onSurface = Color(0xFFE1E9DF),
    surfaceVariant = Color(0xFF263027),
    onSurfaceVariant = Color(0xFFBECABB),
    outline = Color(0xFF85917F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF386B2B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBF3AD),
    onPrimaryContainer = Color(0xFF14380D),
    secondary = Color(0xFF54634E),
    background = Color(0xFFF5FAF1),
    onBackground = Color(0xFF161E14),
    surface = Color(0xFFF5FAF1),
    onSurface = Color(0xFF161E14),
    surfaceVariant = Color(0xFFDEE7D8),
    onSurfaceVariant = Color(0xFF424D3D),
)

@Composable
fun CameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val brand = if (darkTheme) DarkColors else LightColors
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dynamic.copy(
            primary = brand.primary,
            onPrimary = brand.onPrimary,
            primaryContainer = brand.primaryContainer,
            onPrimaryContainer = brand.onPrimaryContainer,
            background = brand.background,
            surface = brand.surface,
        )
    } else {
        brand
    }
    MaterialTheme(colorScheme = colors, content = content)
}
