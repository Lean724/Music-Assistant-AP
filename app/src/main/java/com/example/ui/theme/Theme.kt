package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorSchemeDefault =
  darkColorScheme(
    primary = Color(0xFF0EA5E9), // Celeste Music Assistant by default
    secondary = DarkSurface,
    tertiary = Color(0xFF0EA5E9),
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnSurfaceSecondary
  )

private val LightColorScheme = DarkColorSchemeDefault

@Composable
fun MyApplicationTheme(
  accentColorStr: String = "#0EA5E9",
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val accentColor = try {
    Color(android.graphics.Color.parseColor(accentColorStr))
  } catch (e: Exception) {
    Color(0xFF0EA5E9)
  }

  val colorScheme = darkColorScheme(
    primary = accentColor,
    secondary = DarkSurface,
    tertiary = accentColor,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnSurfaceSecondary
  )

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
