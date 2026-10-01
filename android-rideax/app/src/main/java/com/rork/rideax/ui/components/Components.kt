package com.rork.rideax.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AirportShuttle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Museum
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.PlaceKind
import com.rork.rideax.model.VehicleKind
import com.rork.rideax.model.formatRating
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens

/** Elevated dark card shared by both shells; `floating` adds separation over a map. */
fun Modifier.rideCard(
    floating: Boolean = false,
    radius: Dp = RideDimens.CardRadius,
    color: Color = RideColors.Surface,
    borderColor: Color = RideColors.Hairline,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val elevated = if (floating) shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.6f)) else this
    return elevated
        .clip(shape)
        .background(if (floating) color.copy(alpha = 0.97f) else color)
        .border(1.dp, borderColor, shape)
}

/** The one orange call to action per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "press")
    Box(
        modifier
            .fillMaxWidth()
            .height(RideDimens.ButtonHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(27.dp))
            .background(if (enabled) RideColors.Accent else RideColors.SurfaceRaised)
            .clickable(interaction, ripple(color = RideColors.OnAccent), enabled = enabled && !loading, role = Role.Button) {
                Haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = RideColors.OnAccent, strokeWidth = 2.5.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tint = if (enabled) RideColors.OnAccent else RideColors.TextSecondary
                icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
                Text(text, color = tint, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Quiet secondary action on dark surfaces. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = RideColors.TextPrimary,
    bordered: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "press")
    val shape = RoundedCornerShape(RideDimens.ControlRadius)
    Box(
        modifier
            .height(RideDimens.ButtonHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(RideColors.SurfaceRaised)
            .border(1.dp, if (bordered) tint.copy(alpha = 0.8f) else RideColors.Hairline, shape)
            .clickable(interaction, ripple(color = tint), role = Role.Button) {
                Haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
            Text(text, color = tint, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Round icon button, 44dp minimum touch target. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = RideColors.SurfaceRaised,
    tint: Color = RideColors.TextPrimary,
    size: Dp = 44.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, RideColors.Hairline, CircleShape)
            .clickable(role = Role.Button) {
                Haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Radar-style live pulse. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, dotSize: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(modifier.size(dotSize * 3)) {
        val r = dotSize.toPx() / 2
        drawCircle(color.copy(alpha = 0.85f * (1 - progress)), radius = r * (1 + 1.6f * progress), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawCircle(color, radius = r)
    }
}

/** Thin animated progress track used for ride status. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = RideColors.Accent) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(RideColors.SurfaceRaised)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(4.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.2.sp,
        color = RideColors.TextSecondary,
    )
}

@Composable
fun RatingLabel(rating: Double, modifier: Modifier = Modifier, detail: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = RideColors.AccentWarm, modifier = Modifier.size(14.dp))
        Text(formatRating(rating), style = MaterialTheme.typography.labelLarge, color = RideColors.TextPrimary)
        detail?.let { Text("· $it", style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary) }
    }
}

/** Pickup ring and dropoff square joined by a dotted line. */
@Composable
fun RouteSummaryRow(pickupName: String, dropoffName: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val gap = if (compact) 10.dp else 14.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(if (compact) 46.dp else 56.dp)) {
            val cx = size.width / 2
            drawCircle(RideColors.Accent, radius = 5.dp.toPx(), center = Offset(cx, 7.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
            drawLine(
                RideColors.TextSecondary.copy(alpha = 0.5f),
                Offset(cx, 15.dp.toPx()),
                Offset(cx, size.height - 15.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
            val s = 9.dp.toPx()
            drawRect(RideColors.TextPrimary, topLeft = Offset(cx - s / 2, size.height - 7.dp.toPx() - s / 2), size = androidx.compose.ui.geometry.Size(s, s))
        }
        Spacer(Modifier.width(gap))
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
            Text(pickupName, style = MaterialTheme.typography.bodyMedium, color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dropoffName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(RideColors.SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = RideColors.TextSecondary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = RideColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

/** Circular avatar: photo when available, initials otherwise. */
@Composable
fun Avatar(url: String?, initials: String, modifier: Modifier = Modifier, size: Dp = 54.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(RideColors.SurfaceRaised)
            .border(1.dp, RideColors.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.titleMedium, color = RideColors.TextSecondary)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        }
    }
}

/** Back-arrow header for pushed screens. */
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = RideColors.TextPrimary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = RideColors.TextPrimary, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Small tinted glyph tile used in rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = RideColors.Accent, background: Color = RideColors.SurfaceRaised, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun rideSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = RideColors.Success,
    checkedBorderColor = RideColors.Success,
    uncheckedThumbColor = RideColors.TextSecondary,
    uncheckedTrackColor = RideColors.SurfaceRaised,
    uncheckedBorderColor = RideColors.Hairline,
)

/** Subtle press feedback without a ripple, for whole-card buttons. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(dampingRatio = 0.75f, stiffness = 700f), label = "card-press")
    return this
        .scale(scale)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
            Haptics.tap()
            onClick()
        }
}

fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.CURRENT -> Icons.Rounded.MyLocation
    PlaceKind.HOME -> Icons.Rounded.Home
    PlaceKind.WORK -> Icons.Rounded.Work
    PlaceKind.AIRPORT -> Icons.Rounded.Flight
    PlaceKind.TRAIN -> Icons.Rounded.Train
    PlaceKind.MUSEUM -> Icons.Rounded.Museum
    PlaceKind.LANDMARK -> Icons.Rounded.Terrain
    PlaceKind.THEATRE -> Icons.Rounded.TheaterComedy
    PlaceKind.PIN -> Icons.Rounded.Place
}

fun VehicleKind.icon(): ImageVector = when (this) {
    VehicleKind.CAR -> Icons.Rounded.DirectionsCar
    VehicleKind.COMFORT -> Icons.Rounded.ElectricCar
    VehicleKind.VAN -> Icons.Rounded.AirportShuttle
}
