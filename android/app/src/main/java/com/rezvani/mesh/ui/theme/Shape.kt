package com.rezvani.mesh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Infrastructure-grade geometry (spec section 4): consistent 12px geometric
// corner radius across components, with tighter/looser steps where Material
// maps them (chips, sheets).
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)