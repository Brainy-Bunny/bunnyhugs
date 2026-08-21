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
    // MINOR fix (ultimate-app-review round 1, item 4): was WarmTextDark (identical to onSurface above),
    // silently rendering every caption/chart-label/secondary-text at full emphasis instead of the
    // intentional de-emphasis this role exists for - see WarmTextMuted's own doc in Color.kt.
    onSurfaceVariant = WarmTextMuted,
    outline = WarmOutline,
    error = ErrorRed,
    errorContainer = ErrorRedContainer,
    // MINOR fix (ultimate-app-review round 1, item 4): every role below was previously left unset, so
    // Material3 silently filled it from its own baseline purple default - see Color.kt's matching doc for
    // where each of these values comes from.
    onError = OnErrorRed,
    onErrorContainer = OnErrorRedContainer,
    outlineVariant = WarmOutlineVariant,
    inverseSurface = WarmInverseSurfaceLight,
    inverseOnSurface = WarmInverseOnSurfaceLight,
    inversePrimary = WarmInversePrimaryLight,
    surfaceTint = BlushPink,
    scrim = Color.Black,
    surfaceContainerLowest = WarmSurfaceContainerLowest,
    surfaceContainerLow = WarmSurfaceContainerLow,
    surfaceContainer = WarmSurfaceContainer,
    surfaceContainerHigh = WarmSurfaceContainerHigh,
    surfaceContainerHighest = WarmSurfaceContainerHighest,
    // BUG fix (ultimate-app-review Round 2, Opus): see WarmSurfaceDim/WarmSurfaceBright's own doc in
    // Color.kt - the last two unset M3 surface roles in this scheme.
    surfaceDim = WarmSurfaceDim,
    surfaceBright = WarmSurfaceBright
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
    // MINOR fix (ultimate-app-review round 1, item 4): see LightColors' matching comment above - was
    // WarmTextLight (identical to onSurface above) in this scheme too.
    onSurfaceVariant = WarmTextMutedDark,
    outline = WarmOutlineDark,
    error = ErrorRedDark,
    errorContainer = ErrorRedContainerDark,
    onError = OnErrorRedDark,
    onErrorContainer = OnErrorRedContainerDark,
    outlineVariant = WarmOutlineVariantDark,
    inverseSurface = WarmInverseSurfaceDark,
    inverseOnSurface = WarmInverseOnSurfaceDark,
    inversePrimary = WarmInversePrimaryDark,
    surfaceTint = BlushPinkDark,
    scrim = Color.Black,
    surfaceContainerLowest = WarmSurfaceContainerLowestDark,
    surfaceContainerLow = WarmSurfaceContainerLowDark,
    surfaceContainer = WarmSurfaceContainerDark,
    surfaceContainerHigh = WarmSurfaceContainerHighDark,
    surfaceContainerHighest = WarmSurfaceContainerHighestDark,
    surfaceDim = WarmSurfaceDimDark,
    surfaceBright = WarmSurfaceBrightDark
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
