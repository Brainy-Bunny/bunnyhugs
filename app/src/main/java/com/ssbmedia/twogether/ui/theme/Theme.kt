package com.ssbmedia.twogether.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BlushPink,
    onPrimary = Color.White,
    primaryContainer = BlushPinkContainer,
    onPrimaryContainer = OnBlushPinkContainer,
    secondary = Lavender,
    onSecondary = Color.White,
    secondaryContainer = LavenderContainer,
    onSecondaryContainer = OnLavenderContainer,
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = CoralContainer,
    onTertiaryContainer = OnCoralContainer,
    background = WarmCream,
    onBackground = WarmTextDark,
    surface = WarmCreamSurface,
    onSurface = WarmTextDark,
    surfaceVariant = WarmCreamSurfaceVariant,
    onSurfaceVariant = WarmTextDark,
    outline = WarmOutline,
    error = ErrorRed,
    errorContainer = ErrorRedContainer
)

private val DarkColors = darkColorScheme(
    primary = BlushPinkDark,
    onPrimary = Color.Black,
    primaryContainer = BlushPinkContainerDark,
    onPrimaryContainer = OnBlushPinkContainerDark,
    secondary = LavenderDark,
    onSecondary = Color.Black,
    secondaryContainer = LavenderContainerDark,
    onSecondaryContainer = OnLavenderContainerDark,
    tertiary = CoralDark,
    onTertiary = Color.Black,
    tertiaryContainer = CoralContainerDark,
    onTertiaryContainer = OnCoralContainerDark,
    background = WarmDarkBackground,
    onBackground = WarmTextLight,
    surface = WarmDarkSurface,
    onSurface = WarmTextLight,
    surfaceVariant = WarmDarkSurfaceVariant,
    onSurfaceVariant = WarmTextLight,
    outline = WarmOutlineDark,
    error = ErrorRedDark,
    errorContainer = ErrorRedContainerDark
)

private val TwogetherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TwogetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TwogetherTypography,
        shapes = TwogetherShapes,
        content = content
    )
}
