package com.rezvani.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MeshGreen,
    onPrimary = Color(0xFF071607),
    primaryContainer = DeepMesh,
    onPrimaryContainer = MeshGreenBright,
    secondary = SignalBlue,
    onSecondary = Color(0xFF061525),
    secondaryContainer = SignalBlueDim,
    onSecondaryContainer = Color(0xFFD9E9FF),
    tertiary = MeshPurple,
    onTertiary = Color(0xFF180D27),
    tertiaryContainer = Color(0xFF43266A),
    onTertiaryContainer = Color(0xFFEEDDFF),
    background = GraphiteDeep,
    onBackground = TextHigh,
    surface = Graphite,
    onSurface = TextHigh,
    surfaceVariant = Slate,
    onSurfaceVariant = TextMute,
    error = SemCritical,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Steel,
    outlineVariant = SteelDim,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4ECE5),
    inverseOnSurface = Color(0xFF101510),
    inversePrimary = DeepInk
)

private val LightColorScheme = lightColorScheme(
    primary = DeepInk,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5F5C8),
    onPrimaryContainer = Color(0xFF002107),
    secondary = Color(0xFF2368A5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E5FF),
    onSecondaryContainer = Color(0xFF001D36),
    tertiary = Color(0xFF7650A8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEDDFF),
    onTertiaryContainer = Color(0xFF2A114D),
    background = BoneBackground,
    onBackground = InkHigh,
    surface = BoneSurface,
    onSurface = InkHigh,
    surfaceVariant = BoneVariant,
    onSurfaceVariant = InkMute,
    error = SemCriticalDark,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = IndustrialGrey,
    outlineVariant = Color(0xFFD1DCD3),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF29332B),
    inverseOnSurface = Color(0xFFF1F8F1),
    inversePrimary = MeshGreen
)

@Composable
fun RezvanMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
