package com.hollow.vpn.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Hollow Editorial Design System Tokens (matched to Hollow VPN Redesign)
val HollowCream = Color(0xFFF3F0EA)
val HollowCreamSurface = Color(0xFFE9E4DA)
val HollowCreamSurfaceHigh = Color(0xFFE1DBD0)
val HollowCreamDivider = Color(0xFFD9D4CA)

val HollowInk = Color(0xFF141414)
val HollowInkSecondary = Color(0xFF6B665E)
val HollowInkMuted = Color(0xFF8A847A)

val HollowOrange = Color(0xFFFF4B2B)
val HollowOrangeContainer = Color(0xFFFFE4DF)

// Connecting Phase (.busy — Obsidian + Amber)
val HollowObsidian = Color(0xFF141414)
val HollowObsidianSurface = Color(0xFF202020)
val HollowObsidianDivider = Color(0xFF2E2E2E)
val HollowObsidianMuted = Color(0xFF9A958C)
val HollowAmber = Color(0xFFFFB020)

// Connected Phase (.ok — Forest Green + Lime)
val HollowForest = Color(0xFF0E3B2C)
val HollowForestCard = Color(0xFF14513B)
val HollowForestDivider = Color(0xFF25563F)
val HollowForestMuted = Color(0xFF9DBBAE)
val HollowLime = Color(0xFFC8F169)
val HollowLimeBarMuted = Color(0xFF426E47)

val ExpressiveLightColorScheme = lightColorScheme(
    primary = HollowInk,
    onPrimary = HollowCream,
    primaryContainer = HollowCreamSurface,
    onPrimaryContainer = HollowInk,
    secondary = HollowOrange,
    onSecondary = Color.White,
    secondaryContainer = HollowCreamSurface,
    onSecondaryContainer = HollowInk,
    tertiary = HollowForest,
    onTertiary = HollowCream,
    tertiaryContainer = Color(0xFFD8EFE0),
    onTertiaryContainer = HollowForest,
    error = HollowOrange,
    onError = Color.White,
    errorContainer = HollowOrangeContainer,
    onErrorContainer = Color(0xFF7A1705),
    background = HollowCream,
    onBackground = HollowInk,
    surface = HollowCream,
    onSurface = HollowInk,
    surfaceVariant = HollowCreamSurface,
    onSurfaceVariant = HollowInkSecondary,
    outline = Color(0xFFBDB7AC),
    outlineVariant = HollowCreamDivider,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = HollowCream,
    surfaceContainer = HollowCreamSurface,
    surfaceContainerHigh = HollowCreamSurfaceHigh,
    surfaceContainerHighest = Color(0xFFD5CFC3),
    inverseSurface = HollowInk,
    inverseOnSurface = HollowCream
)

val ExpressiveDarkColorScheme = darkColorScheme(
    primary = HollowCream,
    onPrimary = HollowInk,
    primaryContainer = HollowObsidianSurface,
    onPrimaryContainer = HollowCream,
    secondary = HollowOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF282724),
    onSecondaryContainer = HollowCream,
    tertiary = HollowLime,
    onTertiary = HollowInk,
    tertiaryContainer = HollowForestCard,
    onTertiaryContainer = HollowCream,
    error = Color(0xFFFF6B4A),
    onError = HollowInk,
    errorContainer = Color(0xFF54180B),
    onErrorContainer = Color(0xFFFFD8CF),
    background = HollowObsidian,
    onBackground = HollowCream,
    surface = HollowObsidian,
    onSurface = HollowCream,
    surfaceVariant = HollowObsidianSurface,
    onSurfaceVariant = HollowObsidianMuted,
    outline = Color(0xFF494741),
    outlineVariant = HollowObsidianDivider,
    surfaceContainerLowest = Color(0xFF0B0B0A),
    surfaceContainerLow = HollowObsidian,
    surfaceContainer = HollowObsidianSurface,
    surfaceContainerHigh = Color(0xFF252421),
    surfaceContainerHighest = Color(0xFF31302C),
    inverseSurface = HollowCream,
    inverseOnSurface = HollowInk
)

// Semantic signal quality accents
val SignalStrongLight = Color(0xFF15803D)
val SignalMediumLight = Color(0xFFB45309)
val SignalWeakLight = Color(0xFFFF4B2B)

val SignalStrongDark = Color(0xFFC8F169)
val SignalMediumDark = Color(0xFFFFB020)
val SignalWeakDark = Color(0xFFFF6B4A)
