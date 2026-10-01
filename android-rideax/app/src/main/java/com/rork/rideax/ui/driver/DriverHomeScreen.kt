package com.rork.rideax.ui.driver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.model.DriverApprovalStatus
import com.rork.rideax.model.RideOffer
import com.rork.rideax.model.TripRecord
import com.rork.rideax.model.UserAccount
import com.rork.rideax.model.formatDistance
import com.rork.rideax.model.formatMoney
import com.rork.rideax.model.formatOneDecimal
import com.rork.rideax.model.headingAt
import com.rork.rideax.model.splitAt
import com.rork.rideax.ui.components.Avatar
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.PrimaryButton
import com.rork.rideax.ui.components.ProgressLine
import com.rork.rideax.ui.components.PulsingDot
import com.rork.rideax.ui.components.RatingLabel
import com.rork.rideax.ui.components.RouteSummaryRow
import com.rork.rideax.ui.components.SecondaryButton
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.components.rideSwitchColors
import com.rork.rideax.ui.map.CameraTarget
import com.rork.rideax.ui.map.MapPin
import com.rork.rideax.ui.map.MapScene
import com.rork.rideax.ui.map.PickupZone
import com.rork.rideax.ui.map.PinKind
import com.rork.rideax.ui.map.RideMap
import com.rork.rideax.ui.map.VehicleMarker
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular

/** Driver cockpit: online header, live map, offer / job / waiting card and stats strip. */
@Composable
fun DriverHomeScreen(
    state: DriverUiState,
    account: UserAccount?,
    todaysTrips: List<TripRecord>,
    averageRating: Double,
    viewModel: DriverViewModel,
    onOpenEarnings: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val offer = state.currentOffer
    val job = state.activeJob
    val (_, remaining) = if (state.routeToRider.size >= 2) state.routeToRider.splitAt(state.jobProgress) else emptyList<com.rork.rideax.model.GeoPoint>() to emptyList()
    val heading = if (state.routeToRider.size >= 2) state.routeToRider.headingAt(state.jobProgress) else 0.0

    val scene = MapScene(
        pins = buildList {
            offer?.let { add(MapPin(it.pickup.point, PinKind.PICKUP)) }
            job?.let {
                if (state.jobStage == DriverJobStage.HEADING_TO_PICKUP || state.jobStage == DriverJobStage.WAITING_FOR_RIDER) add(MapPin(it.pickup.point, PinKind.PICKUP))
                add(MapPin(it.dropoff.point, PinKind.DROPOFF))
            }
        },
        route = remaining,
        vehicle = VehicleMarker(state.driverPoint, heading, if (state.isOnline && job == null && offer == null) "Online" else null),
        zone = offer?.let { PickupZone(it.pickup.point, 260.0) },
    )
    val camera = remember(offer?.id, job?.id, state.jobStage, state.routeToRider.size) {
        val points = when {
            offer != null -> listOf(state.driverPoint, offer.pickup.point)
            job != null && state.jobStage == DriverJobStage.ON_TRIP -> listOf(state.driverPoint, job.dropoff.point) + state.routeToRider
            job != null && state.jobStage != DriverJobStage.COMPLETED -> listOf(state.driverPoint, job.pickup.point) + state.routeToRider
            else -> listOf(state.driverPoint)
        }
        CameraTarget(points, topInset = 0.22, bottomInset = 0.48)
    }

    Box(Modifier.fillMaxSize()) {
        RideMap(scene = scene, camera = camera, modifier = Modifier.fillMaxSize())

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = RideDimens.Margin, vertical = 12.dp)
                .rideCard(floating = true)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.clip(CircleShape).pressable(onOpenAccount)) {
                Avatar(account?.avatarUrl, account?.initials ?: "D", size = 44.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isOnline) PulsingDot(RideColors.Success, dotSize = 6.dp)
                    Text(if (state.isOnline) "You're online" else "You're offline", style = MaterialTheme.typography.titleMedium, color = if (state.isOnline) RideColors.Success else RideColors.TextPrimary)
                }
                Text(
                    "${state.onlineDurationText} · ${formatMoney(todaysTrips.sumOf { it.fare }, "EUR")}",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = RideColors.TextSecondary,
                )
            }
            Switch(
                checked = state.isOnline,
                onCheckedChange = { viewModel.setOnline(it) },
                enabled = state.canGoOnline || state.isOnline,
                colors = rideSwitchColors(),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = RideDimens.Margin)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val mode = when {
                !state.canGoOnline -> "approval"
                offer != null -> "offer"
                job != null -> "job"
                state.isOnline -> "waiting"
                else -> "offline"
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith fadeOut() },
                label = "driver-card",
            ) { current ->
                when (current) {
                    "approval" -> ApprovalCard(state.approvalStatus, onOpenDocuments)
                    "offer" -> offer?.let { OfferCard(it, state.secondsRemaining, viewModel::declineOffer, viewModel::acceptOffer) }
                    "job" -> job?.let { JobCard(it, state, viewModel) }
                    "waiting" -> MessageCard(Icons.Rounded.Sensors, "Looking for requests nearby", "Stay in a busy area to get matched faster.", searching = true)
                    else -> OfflineCard { viewModel.setOnline(true) }
                }
            }

            Row(
                Modifier.fillMaxWidth().rideCard(floating = true).pressable(onOpenEarnings).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StripStat("${todaysTrips.size}", "Trips today", Modifier.weight(1f))
                StripStat("${state.acceptanceRate}%", "Acceptance", Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Rounded.Star, null, tint = RideColors.AccentWarm, modifier = Modifier.size(14.dp))
                        Text(formatOneDecimal(averageRating), style = MaterialTheme.typography.titleMedium.tabular(), color = RideColors.TextPrimary)
                    }
                    Text("Rating", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
                }
                Icon(Icons.AutoMirrored.Rounded.TrendingUp, "Earnings", tint = RideColors.Accent)
                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = RideColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun StripStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium.tabular(), color = RideColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
    }
}

@Composable
private fun OfferCard(offer: RideOffer, secondsRemaining: Int, onDecline: () -> Unit, onAccept: () -> Unit) {
    val fraction by animateFloatAsState(secondsRemaining / DriverViewModel.OFFER_SECONDS.toFloat(), tween(900), label = "countdown")
    Column(
        Modifier.fillMaxWidth().rideCard(floating = true, borderColor = RideColors.Accent.copy(alpha = 0.5f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    drawArc(RideColors.SurfaceRaised, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    drawArc(
                        if (secondsRemaining <= 6) RideColors.Danger else RideColors.Accent,
                        -90f, 360f * fraction, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Text("0:%02d".format(secondsRemaining.coerceAtLeast(0)), style = MaterialTheme.typography.labelLarge.tabular(), color = RideColors.TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text("New request", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text(
                    "Pickup ${formatDistance(offer.pickupDistanceMeters)} · ${offer.pickupEtaMinutes} min away",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = RideColors.TextSecondary,
                )
            }
            Text(offer.formattedFare, style = MaterialTheme.typography.headlineSmall.tabular(), color = RideColors.AccentWarm)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RatingLabel(offer.riderRating)
            Text("${offer.riderName} · ${offer.className} · ${formatDistance(offer.tripDistanceMeters)} trip", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RouteSummaryRow(offer.pickup.name, offer.dropoff.name, compact = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Decline", onClick = onDecline, modifier = Modifier.weight(1f))
            PrimaryButton("Accept", onClick = onAccept, modifier = Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun JobCard(job: RideOffer, state: DriverUiState, viewModel: DriverViewModel) {
    val stage = state.jobStage
    Column(
        Modifier.fillMaxWidth().rideCard(floating = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stage.title, style = MaterialTheme.typography.titleMedium, color = if (stage == DriverJobStage.COMPLETED) RideColors.Success else RideColors.TextPrimary)
                val where = when (stage) {
                    DriverJobStage.HEADING_TO_PICKUP, DriverJobStage.WAITING_FOR_RIDER -> "Pick up ${job.riderName} at ${job.pickup.name}"
                    DriverJobStage.ON_TRIP -> "Drop off at ${job.dropoff.name}"
                    else -> "${job.riderName} · ${job.pickup.name} → ${job.dropoff.name}"
                }
                Text(where, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 2)
            }
            Text(
                if (stage == DriverJobStage.COMPLETED) "+" + job.formattedFare else job.formattedFare,
                style = MaterialTheme.typography.titleLarge.tabular(),
                color = RideColors.AccentWarm,
            )
        }
        if (stage != DriverJobStage.COMPLETED) ProgressLine(state.jobProgress.toFloat())
        PrimaryButton(stage.actionTitle, onClick = viewModel::advanceJob)
        if (stage == DriverJobStage.HEADING_TO_PICKUP || stage == DriverJobStage.WAITING_FOR_RIDER) {
            TextButton(onClick = viewModel::cancelJob, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Cancel job", color = RideColors.Danger)
            }
        }
    }
}

@Composable
private fun MessageCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, searching: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().rideCard(floating = true).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (searching) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(40.dp), color = RideColors.Success, strokeWidth = 2.dp)
                Icon(icon, null, tint = RideColors.Success, modifier = Modifier.size(20.dp))
            }
        } else {
            IconTile(icon, size = 44.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
            Text(message, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
        }
    }
}

@Composable
private fun OfflineCard(onGoOnline: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().rideCard(floating = true).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(Icons.Rounded.PowerSettingsNew, tint = RideColors.TextSecondary, size = 48.dp)
        Text("You're offline", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
        Text("Go online to start receiving ride requests.", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, textAlign = TextAlign.Center)
        PrimaryButton("Go online", onClick = onGoOnline)
    }
}

@Composable
private fun ApprovalCard(status: DriverApprovalStatus, onOpen: () -> Unit) {
    val tint = when (status) {
        DriverApprovalStatus.PENDING -> RideColors.AccentWarm
        DriverApprovalStatus.REJECTED -> RideColors.Danger
        else -> RideColors.Accent
    }
    Column(
        Modifier.fillMaxWidth().rideCard(floating = true, borderColor = tint.copy(alpha = 0.4f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(Icons.Rounded.Description, tint = tint, background = tint.copy(alpha = 0.14f), size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(status.title, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text(status.detail, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
        }
        if (status != DriverApprovalStatus.PENDING) {
            PrimaryButton("Complete my profile", onClick = onOpen)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = RideColors.AccentWarm, strokeWidth = 2.dp)
                Text("Review in progress", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = RideColors.AccentWarm)
            }
        }
    }
}
