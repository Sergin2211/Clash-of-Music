package com.clashofmusic.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Hallmark · native app · brand charcoal/rose · restrained · full redesign
// One native font family, explicit rhythm, no font download or decorative motion.
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp
)

val ClashTypography = Typography(
    displayLarge = type(40, 48, FontWeight.Bold),
    displayMedium = type(36, 44, FontWeight.Bold),
    displaySmall = type(32, 40, FontWeight.Bold),
    headlineLarge = type(32, 40, FontWeight.Bold),
    headlineMedium = type(28, 36, FontWeight.Bold),
    headlineSmall = type(24, 32, FontWeight.SemiBold),
    titleLarge = type(22, 28, FontWeight.SemiBold),
    titleMedium = type(18, 24, FontWeight.SemiBold),
    titleSmall = type(16, 22, FontWeight.SemiBold),
    bodyLarge = type(16, 24),
    bodyMedium = type(15, 22),
    bodySmall = type(13, 20),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(12, 18)
)

val ClashShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
