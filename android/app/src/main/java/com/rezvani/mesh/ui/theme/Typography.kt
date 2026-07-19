package com.rezvani.mesh.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rezvani.mesh.R

// IBM Plex Sans Arabic: covers Latin, Arabic, Farsi, Urdu, and Kashmiri in
// one family - designed by IBM for exactly this mixed-script use case.
// TTF files must be placed in android/app/src/main/res/font/ (4 files).
val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_light,    FontWeight.Light),
    Font(R.font.ibm_plex_sans_arabic_regular,  FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium,   FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
)

val Typography = Typography(
    displayLarge  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Light,    fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Light,    fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal,   fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge   = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall   = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall  = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)