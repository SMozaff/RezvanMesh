package com.rezvani.mesh.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RezvanMesh visual system.
 *
 * The dark palette is intentionally restrained: graphite surfaces carry the
 * interface, neon mesh green communicates healthy/active states, blue is
 * reserved for transport and technology affordances, amber is caution, and
 * red is reserved for emergency/failed states.
 */

// ---- Neon Mesh palette -------------------------------------------------
val MeshGreen       = Color(0xFF7CFF55)
val MeshGreenBright = Color(0xFF9BFF78)
val DeepMesh        = Color(0xFF285F32)
val MeshGreenDim    = Color(0xFF163A20)

// Technology and transport accents.
val SignalBlue      = Color(0xFF58A6FF)
val SignalBlueDim   = Color(0xFF173A5F)
val MeshPurple      = Color(0xFFB77CFF)

// Dark graphite surfaces.
val GraphiteDeep    = Color(0xFF05090A)
val Graphite        = Color(0xFF0B1113)
val Slate           = Color(0xFF11191B)
val Steel           = Color(0xFF263336)
val SteelDim        = Color(0xFF1A2527)
val SteelBright     = Color(0xFF3A4A4D)

// Text hierarchy for the dark experience.
val TextHigh        = Color(0xFFF2F6F3)
val TextMute        = Color(0xFFA7B2AD)
val TextDim         = Color(0xFF6E7B75)

// Semantic states.
val SemSuccess      = MeshGreen
val SemWarning      = Color(0xFFFFC857)
val SemCritical     = Color(0xFFFF4D5A)
val SemCriticalDark = Color(0xFFB52838)

// ---- Incident daylight palette ----------------------------------------
val BoneSurface     = Color(0xFFF5F8F5)
val BoneBackground  = Color(0xFFEAF0EB)
val BoneVariant     = Color(0xFFDCE7DE)
val IndustrialGrey  = Color(0xFFB8C6BC)
val DeepInk         = Color(0xFF216B2C)
val InkHigh         = Color(0xFF0D1710)
val InkMute         = Color(0xFF526158)
val InkDim          = Color(0xFF7C8A80)

// ---- Compatibility aliases --------------------------------------------
// Existing screens use these names. Keep them stable while routing them
// through the new visual system so the palette can be migrated incrementally.
val MeshCyan            = MeshGreen
val DarkBackground      = GraphiteDeep
val DarkSurface         = Graphite
val DarkSurfaceVariant  = Slate
val DarkBorder          = Steel
val PrimaryGreen        = MeshGreen
val PrimaryGreenHover   = MeshGreenBright
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
