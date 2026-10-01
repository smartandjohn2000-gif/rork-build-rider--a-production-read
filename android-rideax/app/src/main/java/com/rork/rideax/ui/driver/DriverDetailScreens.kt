package com.rork.rideax.ui.driver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.model.DailyEarnings
import com.rork.rideax.model.DocumentKind
import com.rork.rideax.model.DriverApprovalStatus
import com.rork.rideax.model.DriverDocument
import com.rork.rideax.model.TripRecord
import com.rork.rideax.model.UserAccount
import com.rork.rideax.model.formatMoney
import com.rork.rideax.model.formatMoneyWhole
import com.rork.rideax.model.formatOneDecimal
import com.rork.rideax.ui.components.Avatar
import com.rork.rideax.ui.components.DetailTopBar
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.PrimaryButton
import com.rork.rideax.ui.components.SectionHeader
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.rider.SignOutDialog
import com.rork.rideax.ui.rider.SignOutRow
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(RideColors.Canvas)) {
        DetailTopBar(title, onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = RideDimens.Margin)
                .padding(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Weekly earnings: amber total, animated daily bars, metrics and recent trips. */
@Composable
fun DriverEarningsScreen(week: List<DailyEarnings>, trips: List<TripRecord>, averageRating: Double, onBack: () -> Unit) {
    var showsCashOut by remember { mutableStateOf(false) }
    val total = week.sumOf { it.amount }
    val todayIndex = remember { (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700)) }

    DetailScaffold("Earnings", onBack) {
        Column(Modifier.fillMaxWidth().rideCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("THIS WEEK", style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary)
            Text(formatMoney(total, "EUR"), style = MaterialTheme.typography.displaySmall.tabular(), color = RideColors.AccentWarm)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, tint = RideColors.Success, modifier = Modifier.size(15.dp))
                Text("${week.sumOf { it.trips }} trips · ${week.count { it.trips > 0 }} days driven", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
        }

        Column(Modifier.fillMaxWidth().rideCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Daily earnings", style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary)
            val max = (week.maxOfOrNull { it.amount } ?: 0.0).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                week.forEachIndexed { index, day ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        if (day.amount > 0) Text(formatMoneyWhole(day.amount, "EUR"), style = MaterialTheme.typography.labelSmall.tabular(), color = RideColors.TextSecondary, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        val fraction = (day.amount / max).toFloat() * grow.value
                        val isToday = index == todayIndex
                        Canvas(Modifier.fillMaxWidth().weight(1f)) {
                            val h = (size.height * fraction).coerceAtLeast(4.dp.toPx())
                            drawRoundRect(
                                brush = if (isToday) Brush.verticalGradient(listOf(RideColors.AccentWarm, RideColors.Accent)) else Brush.verticalGradient(listOf(RideColors.SurfaceRaised, RideColors.SurfaceRaised)),
                                topLeft = Offset(0f, size.height - h),
                                size = Size(size.width, h),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(day.dayLabel, style = MaterialTheme.typography.labelSmall, color = if (isToday) RideColors.Accent else RideColors.TextSecondary)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(formatOneDecimal(averageRating), "Average rating", Modifier.weight(1f))
            Metric("${trips.count { !it.wasCancelled }}", "Trips completed", Modifier.weight(1f))
        }

        SectionHeader("Recent trips", Modifier.padding(top = 6.dp))
        trips.take(8).forEach { trip ->
            Row(Modifier.fillMaxWidth().rideCard().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${trip.pickupName} → ${trip.dropoffName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.date)) + " · " + trip.counterpartName,
                        style = MaterialTheme.typography.bodySmall,
                        color = RideColors.TextSecondary,
                    )
                }
                Text(trip.formattedFare, style = MaterialTheme.typography.titleSmall.tabular(), color = RideColors.TextPrimary)
            }
        }

        PrimaryButton("Cash out ${formatMoney(total, "EUR")}", onClick = { showsCashOut = true }, icon = Icons.Rounded.AccountBalance, modifier = Modifier.padding(top = 6.dp))
        Text("Next automatic payout: Friday · paid to your linked bank account", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
    }

    if (showsCashOut) {
        AlertDialog(
            onDismissRequest = { showsCashOut = false },
            containerColor = RideColors.Surface,
            title = { Text("Cash out requested", color = RideColors.TextPrimary) },
            text = { Text("Your balance will land in your bank account within one business day.", color = RideColors.TextSecondary) },
            confirmButton = { TextButton(onClick = { showsCashOut = false }) { Text("OK", color = RideColors.Accent) } },
        )
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.rideCard().padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall.tabular(), color = RideColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary)
    }
}

/** Approval status, vehicle record and document checklist. */
@Composable
fun DriverDocumentsScreen(state: DriverUiState, viewModel: DriverViewModel, onBack: () -> Unit) {
    val status = state.approvalStatus
    val tint = when (status) {
        DriverApprovalStatus.APPROVED -> RideColors.Success
        DriverApprovalStatus.PENDING -> RideColors.AccentWarm
        DriverApprovalStatus.REJECTED -> RideColors.Danger
        DriverApprovalStatus.NOT_SUBMITTED -> RideColors.Accent
    }
    val statusIcon = when (status) {
        DriverApprovalStatus.APPROVED -> Icons.Rounded.VerifiedUser
        DriverApprovalStatus.PENDING -> Icons.Rounded.HourglassTop
        DriverApprovalStatus.REJECTED -> Icons.Rounded.Warning
        DriverApprovalStatus.NOT_SUBMITTED -> Icons.Rounded.Description
    }

    DetailScaffold("Profile & documents", onBack) {
        Row(
            Modifier.fillMaxWidth().rideCard(borderColor = tint.copy(alpha = 0.4f)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(statusIcon, tint = tint, background = tint.copy(alpha = 0.14f), size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text(status.title, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text(status.detail, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
        }

        SectionHeader("Vehicle", Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().rideCard().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Rounded.DirectionsCar)
            Column(Modifier.weight(1f)) {
                Text(state.vehicle.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
                Text("${state.vehicle.year} · ${state.vehicle.plate}", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
        }

        SectionHeader("Documents", Modifier.padding(top = 6.dp))
        state.documents.forEach { DocumentRow(it, enabled = status != DriverApprovalStatus.PENDING) { viewModel.uploadDocument(it.id) } }

        Text(
            "Documents are stored encrypted and reviewed by the RideAX compliance team. You'll be notified as soon as a decision is made.",
            style = MaterialTheme.typography.bodySmall,
            color = RideColors.TextSecondary,
        )

        when (status) {
            DriverApprovalStatus.NOT_SUBMITTED, DriverApprovalStatus.REJECTED ->
                PrimaryButton("Submit for approval", onClick = viewModel::submitForApproval, enabled = state.documents.all { it.isUploaded })
            DriverApprovalStatus.PENDING -> PrimaryButton("Under review", onClick = {}, enabled = false)
            DriverApprovalStatus.APPROVED -> TextButton(onClick = viewModel::resetApproval, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Update documents", color = RideColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DocumentRow(document: DriverDocument, enabled: Boolean, onUpload: () -> Unit) {
    val icon: ImageVector = when (document.kind) {
        DocumentKind.LICENCE -> Icons.Rounded.Badge
        DocumentKind.INSURANCE -> Icons.Rounded.Shield
        DocumentKind.REGISTRATION -> Icons.Rounded.DirectionsCar
        DocumentKind.PHOTO -> Icons.Rounded.CameraAlt
    }
    Row(Modifier.fillMaxWidth().rideCard().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, tint = if (document.isUploaded) RideColors.Success else RideColors.Accent)
        Column(Modifier.weight(1f)) {
            Text(document.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
            Text(document.detail, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
        }
        if (document.isUploaded) {
            Icon(Icons.Rounded.CheckCircle, "Uploaded", tint = RideColors.Success)
        } else {
            Row(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, RideColors.Accent, CircleShape)
                    .pressable(onUpload, enabled = enabled)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Upload, null, tint = RideColors.Accent, modifier = Modifier.size(15.dp))
                Text("Upload", style = MaterialTheme.typography.labelLarge, color = RideColors.Accent)
            }
        }
    }
}

/** Driver profile with shortcuts, the switch back to riding, and sign out. */
@Composable
fun DriverAccountScreen(
    account: UserAccount?,
    state: DriverUiState,
    onOpenDocuments: () -> Unit,
    onOpenEarnings: () -> Unit,
    onSwitchToRider: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    DetailScaffold("Driver account", onBack) {
        Row(Modifier.fillMaxWidth().rideCard().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(account?.avatarUrl, account?.initials ?: "D", size = 60.dp)
            Column(Modifier.weight(1f)) {
                Text(account?.fullName ?: "Driver", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text("${state.vehicle.displayName} · ${state.vehicle.plate}", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                Text(state.approvalStatus.title, style = MaterialTheme.typography.labelMedium, color = if (state.approvalStatus == DriverApprovalStatus.APPROVED) RideColors.Success else RideColors.AccentWarm)
            }
        }
        Column(Modifier.fillMaxWidth().rideCard()) {
            MenuRow(Icons.Rounded.Description, "Profile & documents", onOpenDocuments)
            HorizontalDivider(color = RideColors.Hairline, modifier = Modifier.padding(start = 66.dp))
            MenuRow(Icons.AutoMirrored.Rounded.TrendingUp, "Earnings", onOpenEarnings)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).rideCard(borderColor = RideColors.Accent.copy(alpha = 0.4f)).pressable(onSwitchToRider).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(Icons.Rounded.Person, tint = RideColors.OnAccent, background = RideColors.Accent, size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text("Need a ride yourself?", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text("Switch to rider mode any time — same account.", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = RideColors.TextSecondary)
        }
        SignOutRow { confirmSignOut = true }
    }
    if (confirmSignOut) SignOutDialog(onConfirm = { confirmSignOut = false; onSignOut() }, onDismiss = { confirmSignOut = false })
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = RideColors.TextSecondary)
    }
}
