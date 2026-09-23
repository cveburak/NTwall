package com.wall.guard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val GuardBlack = Color(0xFF0A0A0B)
val GuardBackground = Color(0xFF101012)
val GuardSurface = Color(0xFF161618)
val GuardSurfaceVariant = Color(0xFF1D1D20)
val GuardElevated = Color(0xFF222226)
val GuardHairline = Color(0xFF28282D)
val GuardOutline = Color(0xFF35353B)
val GuardTextPrimary = Color(0xFFEAEDF0)
val GuardTextSecondary = Color(0xFF9BA4AE)
val GuardTextTertiary = Color(0xFF626B75)
val GuardAccentGreen = Color(0xFF3ECF8E)
val GuardDanger = Color(0xFFE5534B)
val GuardAmber = Color(0xFFE2AE3F)
val GuardOnAccent = Color(0xFF00190E)

private val NTWallColors = darkColorScheme(
    primary = GuardAccentGreen,
    onPrimary = GuardOnAccent,
    primaryContainer = Color(0xFF0F2E21),
    onPrimaryContainer = Color(0xFFA7F1CC),
    secondary = Color(0xFF8A949E),
    onSecondary = Color(0xFF101418),
    secondaryContainer = Color(0xFF26262B),
    onSecondaryContainer = Color(0xFFC9D1D9),
    tertiary = GuardAmber,
    onTertiary = Color(0xFF211904),
    background = GuardBackground,
    onBackground = GuardTextPrimary,
    surface = GuardSurface,
    onSurface = GuardTextPrimary,
    surfaceVariant = GuardSurfaceVariant,
    onSurfaceVariant = GuardTextSecondary,
    error = GuardDanger,
    onError = Color(0xFF3A0B0A),
    errorContainer = Color(0xFF3A1817),
    onErrorContainer = Color(0xFFF7C4C0),
    outline = GuardOutline,
    outlineVariant = GuardHairline,
    surfaceContainerLowest = GuardBlack,
    surfaceContainerLow = GuardSurface,
    surfaceContainer = GuardSurfaceVariant,
    surfaceContainerHigh = GuardElevated,
    surfaceContainerHighest = GuardElevated,
    scrim = Color(0xFF000000)
)

private val NTWallTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.3.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp
    )
)

val GuardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp
)

@Composable
fun NTWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NTWallColors,
        typography = NTWallTypography,
        shapes = GuardShapes,
        content = content
    )
}