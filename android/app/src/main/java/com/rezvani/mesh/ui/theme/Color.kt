package com.rezvani.mesh.ui.theme

import androidx.compose.ui.graphics.Color

// =====================================================================
// REZVAN MESH - Brand palette (spec section 2)
// Canonical names below; legacy aliases at the bottom keep existing
// screens compiling unchanged while the values shift to the new system.
// =====================================================================

// ---- Base Emissive Palette (Default Dark) ----
val MeshCyan      = Color(0xFF19D3F3) // Primary Mesh Cyan
val DeepMesh      = Color(0xFF0A7F9D) // Deep Mesh
val SignalBlue    = Color(0xFF4EC9FF) // Signal Blue
val Graphite      = Color(0xFF15181C) // Surface
val Slate         = Color(0xFF22272E) // Sub-surface
val Steel         = Color(0xFF39424E) // Borders
val GraphiteDeep  = Color(0xFF101317) // App background (one step below surface)
val SteelDim      = Color(0xFF2B323B) // Low-opacity border variant

val TextHigh      = Color(0xFFE2E8EC) // Primary text on dark
val TextMute      = Color(0xFF93A1AD) // Secondary text on dark
val TextDim       = Color(0xFF6B7682) // Tertiary/disabled text on dark

// ---- Semantic (shared) ----
val SemSuccess      = Color(0xFF22C55E)
val SemWarning      = Color(0xFFF59E0B)
val SemCritical     = Color(0xFFEF4444)
val SemCriticalDark = Color(0xFFB91C1C)

// ---- Incident Daylight Palette (High-Contrast Fallback) ----
val BoneSurface     = Color(0xFFF4F5F6) // Muted Bone surface
val BoneBackground  = Color(0xFFECEEF0)
val BoneVariant     = Color(0xFFE1E5E9)
val IndustrialGrey  = Color(0xFFC5CBD3) // Borders
val DeepInk         = Color(0xFF085F75) // Primary Ink (Deep Ink Blue)
val InkHigh         = Color(0xFF0E1418)
val InkMute         = Color(0xFF4A5560)
val InkDim          = Color(0xFF8A95A0)

// =====================================================================
// Legacy aliases - DO NOT REMOVE. Screens reference these names directly.
// They now resolve to the brand palette above.
// =====================================================================
val DarkBackground      = GraphiteDeep
val DarkSurface         = Graphite
val DarkSurfaceVariant  = Slate
val DarkBorder          = Steel

val PrimaryGreen        = MeshCyan      // (name retained for compatibility; now Mesh Cyan)
val PrimaryGreenHover   = SignalBlue
val PrimaryGreenDark    = DeepMesh

val DangerRed           = SemCritical
val SuccessGreen        = SemSuccess
val WarningOrange       = SemWarning

val TextWhite           = Color(0xFFFFFFFF)
val TextPrimaryDark     = TextHigh
val TextMutedDark       = TextMute
val TextDimDark         = TextDim
val ErrorContainerDark  = SemCritical.copy(alpha = 0.15f)

val LightBackground     = BoneBackground
val LightSurface        = BoneSurface
val LightSurfaceVariant = BoneVariant
val LightBorder         = IndustrialGrey

val TextPrimaryLight    = InkHigh
val TextMutedLight      = InkMute
val TextDimLight        = InkDim
val ErrorContainerLight = SemCritical.copy(alpha = 0.08f)