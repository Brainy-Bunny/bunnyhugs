package com.ssbmedia.twogether.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette — blush pink, lavender, warm cream, soft coral accent
val BlushPink = Color(0xFFEE8FAE)
val BlushPinkContainer = Color(0xFFFFE1EC)
val OnBlushPinkContainer = Color(0xFF5C1230)

val Lavender = Color(0xFF9C86D6)
val LavenderContainer = Color(0xFFEAE1FB)
val OnLavenderContainer = Color(0xFF2C1F52)

val Coral = Color(0xFFFF8A75)
val CoralContainer = Color(0xFFFFDBD1)
val OnCoralContainer = Color(0xFF5C1C0E)

val WarmCream = Color(0xFFFFF7F0)
val WarmCreamSurface = Color(0xFFFFFDF9)
val WarmCreamSurfaceVariant = Color(0xFFF3E4E8)
val WarmTextDark = Color(0xFF4A3B39)
val WarmOutline = Color(0xFFD8C3C9)

// MINOR fix (ultimate-app-review round 1, item 4 - theming gaps): onSurfaceVariant used to be set
// IDENTICAL to onSurface in both color schemes (see Theme.kt's old LightColors/DarkColors), so every
// caption/chart-label/secondary-text styled with it silently rendered at full emphasis instead of the
// intentionally de-emphasized look the rest of the app already gets via the established
// `.copy(alpha = 0.45f..0.6f)` convention (see e.g. Components.kt's StatCard chevron, CalendarScreen's
// day-cell icon, StatsScreen's chevron). WarmTextMuted is that same de-emphasis baked into a genuine,
// concrete color token instead - computed as WarmTextDark blended ~55% toward WarmCreamSurfaceVariant
// (the same blend ratio that alpha convention already uses elsewhere), rather than copying the alpha
// trick into the theme token itself (a Color's alpha channel on a solid Material3 role isn't guaranteed
// to composite the same way against every background it's ever drawn over).
val WarmTextMuted = Color(0xFF8C7975)

val ErrorRed = Color(0xFFBA1A2C)
val ErrorRedContainer = Color(0xFFFFDAD9)
val ErrorRedDark = Color(0xFFFFB4AA)
val ErrorRedContainerDark = Color(0xFF93000A)
// MINOR fix (ultimate-app-review round 1, item 4): onError/onErrorContainer text-on-error-color roles,
// derived the same way M3's own baseline scheme derives them for a saturated-red-family error color -
// dark text/errorContainer pairs to keep contrast on this palette's light, pinkish error tones.
val OnErrorRed = Color(0xFFFFFFFF)
val OnErrorRedContainer = Color(0xFF410002)
val OnErrorRedDark = Color(0xFF690005)
val OnErrorRedContainerDark = Color(0xFFFFDAD9)

// Dark palette — stays warm & cozy, never turns cold/clinical
val BlushPinkDark = Color(0xFFF2A6C0)
val BlushPinkContainerDark = Color(0xFF5C1230)
val OnBlushPinkContainerDark = Color(0xFFFFD8E6)

val LavenderDark = Color(0xFFC9B7F5)
val LavenderContainerDark = Color(0xFF3D2E66)
val OnLavenderContainerDark = Color(0xFFE7DAFF)

val CoralDark = Color(0xFFFFAB97)
val CoralContainerDark = Color(0xFF6E2B18)
val OnCoralContainerDark = Color(0xFFFFDBD1)

val WarmDarkBackground = Color(0xFF241A20)
val WarmDarkSurface = Color(0xFF2C2027)
val WarmDarkSurfaceVariant = Color(0xFF4A383E)
val WarmTextLight = Color(0xFFF6E8E4)
val WarmOutlineDark = Color(0xFF8A7278)
// MINOR fix (ultimate-app-review round 1, item 4): dark-scheme counterpart of WarmTextMuted above - same
// ~55% blend of WarmTextLight toward WarmDarkSurfaceVariant.
val WarmTextMutedDark = Color(0xFFAE9793)

// MINOR fix (ultimate-app-review round 1, item 4): the outline/inverse/surfaceTint/surfaceContainer* M3
// roles below were all left unset in both schemes, so Material3 silently filled them from its OWN
// baseline purple defaults - live-screenshot-confirmed leaking into NavigationBar and every AlertDialog
// (26 call sites) against this app's warm-cream palette. Every value below is derived FROM this palette's
// own already-defined colors (never a generic Material purple-adjacent default) so the whole scheme reads
// as one consistent warm-toned system regardless of which role a given composable happens to pull from.

// outlineVariant: a lighter/lower-contrast divider than WarmOutline/WarmOutlineDark - WarmOutline blended
// ~50% toward the scheme's own surface color.
val WarmOutlineVariant = Color(0xFFECE0E1)
val WarmOutlineVariantDark = Color(0xFF5B4950)

// inverseSurface/inverseOnSurface/inversePrimary: the LIGHT scheme's inverse roles reuse the DARK
// scheme's own surface/text/primary colors (and vice versa) - genuinely a swap between this app's two
// existing palettes, not a third invented one.
val WarmInverseSurfaceLight = WarmDarkSurface
val WarmInverseOnSurfaceLight = WarmTextLight
val WarmInversePrimaryLight = BlushPinkDark
val WarmInverseSurfaceDark = WarmCreamSurface
val WarmInverseOnSurfaceDark = WarmTextDark
val WarmInversePrimaryDark = BlushPink

// surfaceContainer* family: the graduated "how elevated is this surface" scale NavigationBar/AlertDialog/
// Card's own tonal-elevation math pulls from - a warm-cream gradient in the light scheme (from
// near-white at Lowest up to WarmCreamSurfaceVariant at Highest) and the mirror-image warm-dark gradient
// in the dark scheme (from near-black at Lowest up to WarmDarkSurfaceVariant at Highest), reusing this
// palette's own WarmCream*/WarmDark* colors as anchor points wherever they already line up.
val WarmSurfaceContainerLowest = Color(0xFFFFFFFF)
val WarmSurfaceContainerLow = WarmCream
val WarmSurfaceContainer = Color(0xFFFCEFEA)
val WarmSurfaceContainerHigh = Color(0xFFF6E7E4)
val WarmSurfaceContainerHighest = WarmCreamSurfaceVariant

val WarmSurfaceContainerLowestDark = Color(0xFF1B1216)
val WarmSurfaceContainerLowDark = WarmDarkBackground
val WarmSurfaceContainerDark = WarmDarkSurface
val WarmSurfaceContainerHighDark = Color(0xFF382A30)
val WarmSurfaceContainerHighestDark = WarmDarkSurfaceVariant

// BUG fix (ultimate-app-review Round 2, Opus): surfaceDim/surfaceBright were the only two M3 surface
// roles still left unset in this scheme (same class of gap this file's other roles already closed -
// see the container ramp above) - latent (no component currently pulls them), but still worth closing
// for the same "every role explicitly set, none silently falling back to M3's baseline purple"
// completeness this whole palette already established. Extends the SAME container ramp one step past
// each end: surfaceDim sits below Highest (darker/more saturated, same warm dusty-rose family) as the
// dimmest surface tone; surfaceBright sits at/above Lowest (already near-white in light mode, so
// pure white) as the brightest.
val WarmSurfaceDim = Color(0xFFE6D2D6)
val WarmSurfaceBright = Color(0xFFFFFFFF)
val WarmSurfaceDimDark = Color(0xFF120C0E)
val WarmSurfaceBrightDark = Color(0xFF57434A)
