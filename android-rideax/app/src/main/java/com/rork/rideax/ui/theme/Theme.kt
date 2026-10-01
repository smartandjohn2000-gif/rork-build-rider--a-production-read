package com.rork.rideax.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** RideAX design tokens — identical to the iOS app's RideTheme. */
object RideColors {
    val Canvas = Color(0xFF0D1830)
    val CanvasDeep = Color(0xFF070E1D)
    val Surface = Color(0xFF172440)
    val SurfaceRaised = Color(0xFF1E2C4C)
    val Hairline = Color(0x14FFFFFF)

    val TextPrimary = Color(0xFFEDF1F8)
    val TextSecondary = Color(0xFF93A2BC)

    val Accent = Color(0xFFF7911E)
    val AccentWarm = Color(0xFFFFB547)
    val Success = Color(0xFF2FBE6F)
    val Danger = Color(0xFFE5484D)

    /** Text on orange is navy, never white. */
    val OnAccent = Color(0xFF14203A)
}

object RideDimens {
    val CardRadius = 18.dp
    val ControlRadius = 14.dp
    val Margin = 16.dp
    val ButtonHeight = 54.dp
}

/** Tabular figures so fares, ETAs and countdowns never jitter as they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val RideScheme = darkColorScheme(
    primary = RideColors.Accent,
    onPrimary = RideColors.OnAccent,
    primaryContainer = RideColors.SurfaceRaised,
    onPrimaryContainer = RideColors.TextPrimary,
    secondary = RideColors.AccentWarm,
    onSecondary = RideColors.OnAccent,
    background = RideColors.Canvas,
    onBackground = RideColors.TextPrimary,
    surface = RideColors.Surface,
    onSurface = RideColors.TextPrimary,
    surfaceVariant = RideColors.SurfaceRaised,
    onSurfaceVariant = RideColors.TextSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = RideColors.CanvasDeep,
    surfaceContainerLow = RideColors.Canvas,
    surfaceContainer = RideColors.Surface,
    surfaceContainerHigh = RideColors.Surface,
    surfaceContainerHighest = RideColors.SurfaceRaised,
    error = RideColors.Danger,
    onError = Color.White,
    outline = RideColors.Hairline,
    outlineVariant = RideColors.Hairline,
    scrim = Color.Black,
)

private val Base = Typography()

private val RideTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Permanently dark: maps, routes and live vehicles stay the brightest things on screen. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RideScheme, typography = RideTypography) {
        CompositionLocalProvider(LocalContentColor provides RideColors.TextPrimary, content = content)
    }
}
