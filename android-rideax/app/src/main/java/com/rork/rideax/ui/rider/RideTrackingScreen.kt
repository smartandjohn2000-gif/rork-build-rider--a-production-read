package com.rork.rideax.ui.rider

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.Ride
import com.rork.rideax.model.RideStatus
import com.rork.rideax.model.formatMoney
import com.rork.rideax.model.splitAt
import com.rork.rideax.ui.components.Avatar
import com.rork.rideax.ui.components.CircleIconButton
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.PrimaryButton
import com.rork.rideax.ui.components.ProgressLine
import com.rork.rideax.ui.components.PulsingDot
import com.rork.rideax.ui.components.RatingLabel
import com.rork.rideax.ui.components.RouteSummaryRow
import com.rork.rideax.ui.components.SecondaryButton
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.map.CameraTarget
import com.rork.rideax.ui.map.MapPin
import com.rork.rideax.ui.map.MapScene
import com.rork.rideax.ui.map.PinKind
import com.rork.rideax.ui.map.RideMap
import com.rork.rideax.ui.map.VehicleMarker
import com.rork.rideax.ui.map.rememberVehicleState
import com.rork.rideax.ui.session.SessionUiState
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular

private const val DRIVER_PHONE = "+33612345678"

/**
 * Live ride: map with the driver easing along the road, status and driver
 * cards, safety and cancel actions, then payment and rating on completion.
 */
@Composable
fun RideTrackingScreen(state: RideUiState, session: SessionUiState, viewModel: RideViewModel) {
    val ride = state.ride ?: return
    val context = LocalContext.current
    var following by rememberSaveable { mutableStateOf(true) }
    var recenterNonce by remember { mutableIntStateOf(0) }
    var showsCancel by remember { mutableStateOf(false) }
    var showsSafety by remember { mutableStateOf(false) }
    var showsChat by remember { mutableStateOf(false) }

    val vehicle = rememberVehicleState(state.driverPing, state.driverRoute)
    val route = state.driverRoute.ifEmpty { ride.routeToDropoff }
    val (driven, remaining) = if (vehicle != null && state.driverRoute.size >= 2 && vehicle.progress > 0.002) {
        state.driverRoute.splitAt(vehicle.progress)
    } else {
        emptyList<GeoPoint>() to route
    }

    val caption = when (ride.status) {
        RideStatus.ACCEPTED, RideStatus.ARRIVING -> if (state.etaMinutes <= 1) "Arriving" else "${state.etaMinutes} min"
        RideStatus.ARRIVED -> "Waiting"
        RideStatus.STARTED, RideStatus.IN_PROGRESS ->
            if (vehicle != null && vehicle.isMoving && vehicle.speedKmh > 0) "${vehicle.speedKmh} km/h"
            else if (state.etaMinutes <= 1) "Almost there" else "${state.etaMinutes} min"
        else -> null
    }

    val scene = MapScene(
        pins = listOf(MapPin(ride.pickup.point, PinKind.PICKUP), MapPin(ride.dropoff.point, PinKind.DROPOFF)),
        drivenRoute = driven,
        route = remaining,
        vehicle = if (ride.status.isActive) vehicle?.let { VehicleMarker(it.point, it.heading, caption) } else null,
    )

    val camera = remember(state.driverPing?.sequence, ride.status, following, recenterNonce) {
        if (!following) return@remember null
        val driverPoint = state.driverPing?.point ?: ride.driverPoint
        val points = when {
            ride.status == RideStatus.REQUESTED || ride.status == RideStatus.MATCHING -> listOf(ride.pickup.point, ride.dropoff.point) + ride.routeToDropoff
            ride.status.isDriverApproaching -> listOf(driverPoint ?: ride.pickup.point, ride.pickup.point)
            else -> listOf(driverPoint ?: ride.pickup.point, ride.dropoff.point)
        }
        CameraTarget(points, topInset = 0.16, bottomInset = 0.52, nonce = recenterNonce)
    }

    BackHandler(enabled = ride.status.allowsCancellation) { showsCancel = true }

    Box(Modifier.fillMaxSize().background(RideColors.Canvas)) {
        RideMap(scene = scene, camera = camera, modifier = Modifier.fillMaxSize(), onUserGesture = { following = false })

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = RideDimens.Margin, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ride.status.allowsCancellation) {
                CircleIconButton(Icons.Rounded.Close, "Cancel ride", onClick = { showsCancel = true }, background = RideColors.Surface)
            }
            Spacer(Modifier.weight(1f))
            if (ride.status.isActive) {
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(RideColors.Surface.copy(alpha = 0.96f))
                        .border(1.dp, RideColors.Hairline, CircleShape)
                        .padding(start = 4.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot(RideColors.Success)
                    Text(ride.status.title, style = MaterialTheme.typography.labelLarge, color = RideColors.TextPrimary)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = RideDimens.Margin)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = !following && ride.status.isActive,
                enter = scaleIn(initialScale = 0.8f) + fadeIn(),
                exit = scaleOut(targetScale = 0.8f) + fadeOut(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Row(
                    Modifier
                        .height(38.dp)
                        .clip(CircleShape)
                        .background(RideColors.Accent)
                        .clickable(role = Role.Button) {
                            Haptics.tap()
                            following = true
                            recenterNonce++
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.MyLocation, null, tint = RideColors.OnAccent, modifier = Modifier.size(15.dp))
                    Text("Follow driver", style = MaterialTheme.typography.labelLarge, color = RideColors.OnAccent)
                }
            }

            if (ride.status.isActive) {
                StatusCard(ride, state)
                AnimatedVisibility(visible = ride.driver != null, enter = slideInVertically { it } + fadeIn()) {
                    ride.driver?.let { driver ->
                        Row(
                            Modifier.fillMaxWidth().rideCard(floating = true).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Avatar(driver.photoUrl, driver.name.take(1))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(driver.name, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                                RatingLabel(driver.rating, detail = "%,d trips".format(driver.tripCount))
                                Text("${driver.vehicle} · ${driver.plate}", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            CircleIconButton(Icons.Rounded.Call, "Call driver", onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$DRIVER_PHONE"))) }
                            })
                            CircleIconButton(Icons.AutoMirrored.Rounded.Chat, "Message driver", onClick = { showsChat = true })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().rideCard(floating = true).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RouteSummaryRow(ride.pickup.name, ride.dropoff.name, Modifier.weight(1f), compact = true)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ride.quote.formattedTotal, style = MaterialTheme.typography.titleLarge.tabular(), color = RideColors.TextPrimary)
                        Text(session.defaultPaymentMethod?.displayName ?: ride.rideClass.name, style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Safety", onClick = { showsSafety = true }, modifier = Modifier.weight(1f), tint = RideColors.Accent, bordered = true, icon = Icons.Rounded.Shield)
                    if (ride.status.allowsCancellation) {
                        SecondaryButton("Cancel ride", onClick = { showsCancel = true }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        AnimatedVisibility(visible = !ride.status.isActive, enter = fadeIn(), exit = fadeOut()) {
            CompletionOverlay(ride, state, session, viewModel)
        }
    }

    if (showsCancel) {
        AlertDialog(
            onDismissRequest = { showsCancel = false },
            containerColor = RideColors.Surface,
            title = { Text("Cancel this ride?", color = RideColors.TextPrimary) },
            text = { Text("Cancelling is free until your driver arrives.", color = RideColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showsCancel = false
                    viewModel.cancelRide()
                }) { Text("Cancel ride", color = RideColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showsCancel = false }) { Text("Keep ride", color = RideColors.TextPrimary) }
            },
        )
    }
    if (showsSafety) SafetySheet(ride) { showsSafety = false }
    if (showsChat) ride.driver?.let { DriverChatSheet(it.name) { showsChat = false } }
}

@Composable
private fun StatusCard(ride: Ride, state: RideUiState) {
    val name = ride.driver?.name?.substringBefore(" ") ?: "Your driver"
    val headline = when (ride.status) {
        RideStatus.REQUESTED -> "Sending your request…"
        RideStatus.MATCHING -> "Finding a driver nearby…"
        RideStatus.ACCEPTED -> "$name accepted your ride"
        RideStatus.ARRIVING -> if (state.etaMinutes <= 1) "$name is arriving now" else "$name is arriving in ${state.etaMinutes} min"
        RideStatus.ARRIVED -> "$name has arrived"
        RideStatus.STARTED -> "Starting your trip"
        RideStatus.IN_PROGRESS -> if (state.etaMinutes <= 1) "Arriving at your destination" else "${state.etaMinutes} min to ${ride.dropoff.name}"
        RideStatus.COMPLETED -> "You've arrived"
        RideStatus.PAID -> "Payment complete"
        RideStatus.CANCELLED -> "Ride cancelled"
    }
    val progress = when (ride.status) {
        RideStatus.REQUESTED -> 0.05
        RideStatus.MATCHING -> 0.15
        RideStatus.ACCEPTED -> 0.2
        RideStatus.ARRIVING, RideStatus.ARRIVED -> 0.2 + state.progress * 0.3
        RideStatus.STARTED -> 0.5
        RideStatus.IN_PROGRESS -> 0.5 + state.progress * 0.5
        else -> 1.0
    }
    Column(Modifier.fillMaxWidth().rideCard(floating = true).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ride.status == RideStatus.REQUESTED || ride.status == RideStatus.MATCHING) {
                CircularProgressIndicator(Modifier.size(16.dp), color = RideColors.Accent, strokeWidth = 2.dp)
            }
            Text(headline, style = MaterialTheme.typography.titleMedium.tabular(), color = RideColors.TextPrimary)
        }
        ProgressLine(progress.toFloat())
    }
}

@Composable
private fun CompletionOverlay(ride: Ride, state: RideUiState, session: SessionUiState, viewModel: RideViewModel) {
    val context = LocalContext.current
    val cancelled = ride.status == RideStatus.CANCELLED
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = RideDimens.Margin)
                .padding(bottom = 24.dp)
                .rideCard()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (cancelled) Icons.Rounded.Cancel else Icons.Rounded.CheckCircle,
                null,
                tint = if (cancelled) RideColors.Danger else RideColors.Success,
                modifier = Modifier.size(44.dp),
            )
            Text(if (cancelled) "Ride cancelled" else "You've arrived", style = MaterialTheme.typography.titleLarge, color = RideColors.TextPrimary)

            if (cancelled) {
                Text(
                    "No charge was made. You can book another ride whenever you're ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RideColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                RouteSummaryRow(ride.pickup.name, ride.dropoff.name, Modifier.fillMaxWidth().padding(vertical = 4.dp), compact = true)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(RideDimens.ControlRadius)).background(RideColors.SurfaceRaised).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val code = ride.quote.currencyCode
                    BreakdownRow("Base fare", formatMoney(ride.quote.base, code))
                    BreakdownRow("Distance", formatMoney(ride.quote.distanceCharge, code))
                    BreakdownRow("Time", formatMoney(ride.quote.timeCharge, code))
                    if (ride.quote.isSurging) BreakdownRow("Live demand", "+" + formatMoney(ride.quote.surgeAmount, code))
                    BreakdownRow("Booking fee", formatMoney(ride.quote.bookingFee, code))
                    HorizontalDivider(color = RideColors.Hairline)
                    Row {
                        Text("Total", style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text(ride.quote.formattedTotal, style = MaterialTheme.typography.titleSmall.tabular(), color = RideColors.Accent)
                    }
                    state.receipt?.let { receipt ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                if (receipt.isLiveCharge) Icons.Rounded.VerifiedUser else Icons.Rounded.HourglassTop,
                                null,
                                tint = if (receipt.isLiveCharge) RideColors.Success else RideColors.TextSecondary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                if (receipt.isLiveCharge) "Charged to ${receipt.paymentMethodLabel}" else "Recorded · card payment not enabled yet",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (receipt.isLiveCharge) RideColors.Success else RideColors.TextSecondary,
                            )
                        }
                        receipt.receiptUrl?.let { url ->
                            Text(
                                "View Stripe receipt",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = RideColors.Accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            state.errorMessage?.let { ErrorBanner(it, onDismiss = viewModel::clearError) }

            if (ride.status == RideStatus.COMPLETED) {
                PrimaryButton(
                    text = "Pay ${ride.quote.formattedTotal}",
                    onClick = { viewModel.payForRide(session.defaultPaymentMethod) },
                    loading = state.isPaying,
                    icon = Icons.Rounded.Lock,
                )
                Text(
                    "Card details are handled by Stripe. RideAX never stores them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = RideColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (ride.status == RideStatus.PAID) {
                Text(
                    "How was your ride with ${ride.driver?.name ?: "your driver"}?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RideColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                StarRating(ride.riderRatingForDriver ?: 0, onRate = viewModel::rateDriver)
            }

            if (cancelled || ride.riderRatingForDriver != null) {
                PrimaryButton("Done", onClick = viewModel::dismissCompletedRide)
            }
        }
    }
}

@Composable
private fun BreakdownRow(title: String, value: String) {
    Row {
        Text(title, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall.tabular(), color = RideColors.TextPrimary)
    }
}

@Composable
fun StarRating(rating: Int, onRate: (Int) -> Unit, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { star ->
            val filled = rating >= star
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onRate(star) }
                    .semantics { contentDescription = "$star star${if (star == 1) "" else "s"}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline, null, tint = if (filled) RideColors.AccentWarm else RideColors.TextSecondary, modifier = Modifier.size(size))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetySheet(ride: Ride, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RideColors.Canvas) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = RideDimens.Margin).padding(bottom = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Shield, null, tint = RideColors.Accent, modifier = Modifier.size(32.dp))
            Text("Safety toolkit", style = MaterialTheme.typography.titleLarge, color = RideColors.TextPrimary)
            Text("Your trip details are recorded and can be shared at any time.", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, textAlign = TextAlign.Center)
            SafetyRow(Icons.Rounded.Share, "Share trip status", "Send your trip details to a trusted contact") {
                val driver = ride.driver
                val text = buildString {
                    append("I'm on a RideAX trip from ${ride.pickup.name} to ${ride.dropoff.name}.")
                    if (driver != null) append(" Driver: ${driver.name}, ${driver.vehicle} (${driver.plate}).")
                }
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share trip")) }
            }
            SafetyRow(Icons.Rounded.Call, "Call emergency services", "Opens your dialler with 112", tint = RideColors.Danger) {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
            }
            SafetyRow(Icons.Rounded.Report, "Report an issue", "Tell our support team what happened") {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, "RideAX trip issue · ${ride.id}")
                runCatching { context.startActivity(intent) }
            }
            ride.driver?.let { Text("${it.vehicle} · ${it.plate}", style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary) }
            SecondaryButton("Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SafetyRow(icon: ImageVector, title: String, detail: String, tint: Color = RideColors.Accent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rideCard().pressable(onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, tint = tint, background = tint.copy(alpha = 0.14f), size = 42.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
        }
    }
}

private data class ChatMessage(val id: Int, val text: String, val fromRider: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverChatSheet(driverName: String, onDismiss: () -> Unit) {
    val messages = remember {
        mutableStateListOf(
            ChatMessage(1, "I'm two streets away, wearing a blue jacket.", false),
            ChatMessage(2, "Great — I'm outside the bakery on the corner.", true),
        )
    }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = RideColors.Canvas) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).imePadding().navigationBarsPadding()) {
            Text(driverName, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary, modifier = Modifier.padding(horizontal = RideDimens.Margin))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(RideDimens.Margin),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromRider) Arrangement.End else Arrangement.Start) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.fromRider) RideColors.OnAccent else RideColors.TextPrimary,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (message.fromRider) RideColors.Accent else RideColors.Surface)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.padding(horizontal = RideDimens.Margin, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(CircleShape).background(RideColors.Surface).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text("Message $driverName", color = RideColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = RideColors.TextPrimary),
                        cursorBrush = SolidColor(RideColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val canSend = draft.isNotBlank()
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (canSend) RideColors.Accent else RideColors.SurfaceRaised)
                        .clickable(enabled = canSend, role = Role.Button) {
                            messages.add(ChatMessage(messages.size + 1, draft.trim(), true))
                            draft = ""
                            Haptics.tap()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (canSend) RideColors.OnAccent else RideColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
