package org.tamodak.hisar.ui.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import org.tamodak.hisar.data.model.SimpleFont

private val HisarBlue = Color(0xFF1F4FBF)
private val LightColors = lightColorScheme(primary = HisarBlue)
private val DarkColors = darkColorScheme(primary = Color(0xFFB3C5FF))

/**
 * Hisar's theme, like Chrome's: Material You colours from the wallpaper where the platform has
 * them (Android 12+), Hisar blue otherwise, following the system's light or dark setting.
 * Simple mode replaces it with white on black in the chosen font.
 */
@Composable
fun HisarTheme(
    simpleMode: Boolean = false,
    simpleFont: SimpleFont = SimpleFont.SANS,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        simpleMode -> SimpleModeColors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val typography = if (simpleMode) Typography().withFont(simpleFont.family) else Typography()
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val SimpleFont.family: FontFamily
    get() = when (this) {
        SimpleFont.SANS -> FontFamily.SansSerif
        SimpleFont.SERIF -> FontFamily.Serif
        SimpleFont.MONO -> FontFamily.Monospace
        SimpleFont.CONDENSED -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    }

/** Simple mode: white on black, nothing else. */
private val SimpleModeColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color.White,
    secondary = Color.White, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color.White,
    tertiary = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color.White,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFF3A3A3A),
    error = Color.White, onError = Color.Black,
)

private fun Typography.withFont(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family), headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family), bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
