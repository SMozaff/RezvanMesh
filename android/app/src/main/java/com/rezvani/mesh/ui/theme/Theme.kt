package com.rezvani.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Default Dark = Base Emissive palette (spec section 2)
private val DarkColorScheme = darkColorScheme(
    primary = MeshCyan,
    onPrimary = Color(0xFF04222A),
    primaryContainer = DeepMesh,
    onPrimaryContainer = Color(0xFFBDEFFB),
    secondary = SignalBlue,
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF103A4A),
    onSecondaryContainer = Color(0xFFC4E7FA),
    tertiary = DeepMesh,
    onTertiary = Color(0xFFC9EEFB),
    tertiaryContainer = Color(0xFF0E5063),
    onTertiaryContainer = Color(0xFFCFEFFA),
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
    inverseSurface = Color(0xFFE2E8EC),
    inverseOnSurface = Color(0xFF15181C),
    inversePrimary = DeepInk
)

// Incident Daylight = High-Contrast Fallback (spec section 2)
private val LightColorScheme = lightColorScheme(
    primary = DeepInk,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8E9F7),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = DeepMesh,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9F2),
    onSecondaryContainer = Color(0xFF071E26),
    tertiary = Color(0xFF0A6E86),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4E9F5),
    onTertiaryContainer = Color(0xFF001F28),
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
    outlineVariant = Color(0xFFD7DCE2),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2A2F34),
    inverseOnSurface = Color(0xFFF4F5F6),
    inversePrimary = MeshCyan
)

@Composable
fun RezvanMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,   // <-- was missing: custom type scale now actually applied
        shapes = Shapes,           // <-- new: 12px geometric shape system
        content = content
    )
}