package com.ssbmedia.twogether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Friendly system-font typography — no downloaded font, keeps things fully offline.
val TwogetherTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    // BUG fix (ultimate-app-review Round 2, Opus): displaySmall/headlineSmall were left undefined,
    // same class of gap titleSmall's own fix above already closed once - silently falling back to M3's
    // baseline (Normal weight) default, unlike every other style in this scale, at real call sites
    // (BadgesScreen, MainActivity, MilestonesScreen, SettingsScreen). Sized/weighted to interpolate
    // between their neighbors, same approach titleSmall's own fix used: displaySmall Bold (matching
    // both displayMedium above and headlineLarge below) at 36sp, between their 32sp/28sp; headlineSmall
    // SemiBold (matching both headlineMedium above and titleLarge below) at 22sp, between their
    // 24sp/20sp.
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    // MINOR fix (ultimate-app-review round 1, item 4): titleSmall was left undefined, silently falling
    // back to M3's baseline default (inconsistent with every other style in this scale) at 9+ real call
    // sites (BadgesScreen, CalendarScreen, OurListsScreen, MomentsScreen, HoursDetailScreen). Sized/weighted
    // to interpolate between its neighbors: same 14sp size and 0.1sp letterSpacing as labelLarge/titleMedium,
    // but SemiBold (titleMedium's weight, not labelLarge's Medium) so it still reads as a title rather than
    // a label, with a taller 20sp lineHeight (matching bodyMedium's) for a touch more breathing room than
    // labelLarge's tightly-set 18sp.
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
)
