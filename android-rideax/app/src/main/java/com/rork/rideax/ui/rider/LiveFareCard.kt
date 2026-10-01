package com.rork.rideax.ui.rider

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.rideax.data.DemandSnapshot
import com.rork.rideax.data.Haptics
import com.rork.rideax.data.RouteEstimate
import com.rork.rideax.model.FareQuote
import com.rork.rideax.model.formatDistance
import com.rork.rideax.model.formatMoney
import com.rork.rideax.ui.components.PulsingDot
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The live fare readout: current total for the selected tier, how it moved at
 * the last reprice, what demand is doing, and an itemised breakdown.
 */
@Composable
fun LiveFareCard(
    quote: FareQuote?,
    demand: DemandSnapshot,
    estimate: RouteEstimate?,
    rates: Pair<Double, Double>,
    isRefreshing: Boolean,
    updatedAt: Long?,
    fareChange: Double,
    isFareCurrent: Boolean,
    driftMeters: Double,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(RideDimens.ControlRadius)
    val borderColor by animateColorAsState(if (demand.isSurging) RideColors.Accent.copy(alpha = 0.45f) else RideColors.Hairline, label = "surge-border")
    val chevron by animateFloatAsState(if (isExpanded) 180f else 0f, spring(dampingRatio = 0.82f), label = "chevron")

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RideColors.SurfaceRaised.copy(alpha = 0.7f))
            .border(1.dp, borderColor, shape)
            .clickable(enabled = quote != null, role = Role.Button, onClickLabel = if (isExpanded) "Hide fare breakdown" else "Show fare breakdown") {
                Haptics.tap()
                onToggle()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                contentDescription = quote?.let { "Live fare ${it.formattedTotal}" + if (demand.isSurging) ", ${demand.label}" else "" } ?: "Calculating your fare"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveFareIndicator(updatedAt, isRefreshing)
            Spacer(Modifier.weight(1f))
            estimate?.let {
                Text(
                    "${formatDistance(it.distanceMeters)} · ${it.minutes} min",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = RideColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (quote != null) {
                val goingUp = fareChange >= 0
                AnimatedContent(
                    targetState = quote.formattedTotal,
                    transitionSpec = {
                        (slideInVertically { if (goingUp) it else -it } + fadeIn()) togetherWith
                            (slideOutVertically { if (goingUp) -it else it } + fadeOut()) using SizeTransform(clip = false)
                    },
                    label = "fare",
                ) { total ->
                    Text(total, fontSize = 32.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineLarge.tabular(), color = RideColors.TextPrimary)
                }
            } else {
                Text("Calculating…", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = RideColors.TextSecondary)
            }

            AnimatedVisibility(visible = fareChange != 0.0 && quote != null, enter = scaleIn(initialScale = 0.7f) + fadeIn(), exit = scaleOut(targetScale = 0.7f) + fadeOut()) {
                FareDeltaChip(fareChange, quote?.currencyCode ?: "EUR")
            }
            Spacer(Modifier.weight(1f))
            if (quote != null) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = RideColors.TextSecondary, modifier = Modifier.rotate(chevron))
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(targetState = demand.isSurging, label = "surge") { surging ->
                if (surging) {
                    SurgeBadge(demand)
                } else {
                    Text("Standard fare · no surge", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!isFareCurrent) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            Haptics.tap()
                            onRefresh()
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = RideColors.Accent, modifier = Modifier.size(13.dp))
                    Text(
                        "Moved ${driftMeters.toInt()} m · update",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        fontWeight = FontWeight.SemiBold,
                        color = RideColors.Accent,
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded && quote != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (quote != null) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(RideColors.Hairline))
                    FareBreakdownList(quote, rates.first, rates.second, if (demand.isSurging) demand.reason else null)
                }
            }
        }
    }
}

/** Itemised charges, recomputed from the current quote on every reprice. */
@Composable
fun FareBreakdownList(quote: FareQuote, perKilometre: Double, perMinute: Double, demandReason: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        quote.lines(perKilometre, perMinute).forEach { line ->
            val color = if (line.isHighlighted) RideColors.Accent else RideColors.TextPrimary
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(line.label, style = MaterialTheme.typography.bodySmall, color = color)
                    line.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall.tabular(), color = RideColors.TextSecondary) }
                }
                Text(line.amount, style = MaterialTheme.typography.bodySmall.tabular(), fontWeight = FontWeight.Medium, color = color)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RideColors.Hairline))
        Row {
            Text("Total", style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(quote.formattedTotal, style = MaterialTheme.typography.titleSmall.tabular(), fontWeight = FontWeight.Bold, color = RideColors.TextPrimary)
        }
        Text(
            demandReason ?: "Your final fare can change if the route or waiting time does. Tolls are billed at cost.",
            style = MaterialTheme.typography.labelSmall,
            color = RideColors.TextSecondary,
        )
    }
}

/** Solid orange pill shown whenever a surge multiplier applies. */
@Composable
fun SurgeBadge(demand: DemandSnapshot) {
    Row(
        Modifier.height(22.dp).clip(CircleShape).background(RideColors.Accent).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.Bolt, null, tint = RideColors.OnAccent, modifier = Modifier.size(12.dp))
        Text("${demand.formattedMultiplier} ${demand.label}", style = MaterialTheme.typography.labelSmall.tabular(), fontWeight = FontWeight.Bold, color = RideColors.OnAccent)
    }
}

/** How far the fare moved at the last reprice: warm amber up, neutral down. */
@Composable
fun FareDeltaChip(delta: Double, currencyCode: String) {
    val up = delta > 0
    val tint = if (up) RideColors.AccentWarm else RideColors.TextSecondary
    Row(
        Modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(RideColors.Surface)
            .border(1.dp, RideColors.Hairline, CircleShape)
            .padding(horizontal = 7.dp)
            .semantics { contentDescription = if (up) "Fare went up" else "Fare came down" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = tint, modifier = Modifier.size(10.dp))
        Text(formatMoney(abs(delta), currencyCode), style = MaterialTheme.typography.labelSmall.tabular(), fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/** Pulsing dot plus a ticking "just now / 12s ago" caption. */
@Composable
fun LiveFareIndicator(updatedAt: Long?, isRefreshing: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val caption = when {
        isRefreshing -> "Updating fare…"
        updatedAt == null -> "Live fare"
        else -> {
            val seconds = ((now - updatedAt) / 1000).coerceAtLeast(0)
            when {
                seconds < 3 -> "Live fare · just now"
                seconds < 60 -> "Live fare · ${seconds}s ago"
                else -> "Live fare · ${seconds / 60}m ago"
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PulsingDot(if (isRefreshing) RideColors.AccentWarm else RideColors.Accent, dotSize = 6.dp)
        Text(caption, style = MaterialTheme.typography.labelSmall.tabular(), fontWeight = FontWeight.SemiBold, color = RideColors.TextSecondary)
    }
}
