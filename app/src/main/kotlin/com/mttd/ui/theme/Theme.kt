package com.mttd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 흑요석 바다와 푸른 보석, 금빛 전리품에서 가져온 앱 고정 팔레트. */
private val DeepJewelColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF03131F),
    primaryContainer = Color(0xFF0C3854),
    onPrimaryContainer = Color(0xFFBEEBFF),
    secondary = Color(0xFFFF9F43),
    onSecondary = Color(0xFF251500),
    secondaryContainer = Color(0xFF4B2900),
    onSecondaryContainer = Color(0xFFFFD9B0),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF00210D),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFE6F1FF),
    surface = Color(0xFF111E30),
    onSurface = Color(0xFFE6F1FF),
    surfaceVariant = Color(0xFF182941),
    onSurfaceVariant = Color(0xFFA8BCD6),
    surfaceContainerLowest = Color(0xFF07111F),
    surfaceContainerLow = Color(0xFF0C1929),
    surfaceContainer = Color(0xFF111E30),
    surfaceContainerHigh = Color(0xFF15243A),
    surfaceContainerHighest = Color(0xFF1B2D47),
    outline = Color(0xFF31506F),
    error = Color(0xFFFB7185),
)

private val PremiumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

// 작은 보조 문구도 휴대폰에서 편하게 읽히도록 기본 Material 크기보다 한 단계 올린다.
private val PremiumTypography = Typography(
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun mTTDTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DeepJewelColors,
        shapes = PremiumShapes,
        typography = PremiumTypography,
        content = content,
    )
}
