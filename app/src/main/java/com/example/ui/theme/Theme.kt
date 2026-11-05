package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val V2RayLightColorScheme = lightColorScheme(
    primary = ForestEmerald,
    onPrimary = Color.White,
    primaryContainer = SelectedMintCard,
    onPrimaryContainer = ForestEmeraldDark,
    secondary = EmeraldSwitchActive,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F4EE),
    onSecondaryContainer = ForestEmerald,
    tertiary = AuraMidTeal,
    background = SageBackgroundTop,
    onBackground = TextPrimaryDark,
    surface = PureWhiteCard,
    onSurface = TextPrimaryDark,
    surfaceVariant = InputFieldBg,
    onSurfaceVariant = TextSecondaryMuted,
    outline = SubtleCardBorder,
    error = DisconnectCrimson,
    errorContainer = DisconnectPillBg,
    onErrorContainer = DisconnectCrimson
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = V2RayLightColorScheme,
        typography = Typography,
        content = content
    )
}
