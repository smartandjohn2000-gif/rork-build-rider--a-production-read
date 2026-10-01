package com.rork.rideax.ui.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rork.rideax.model.PaymentMethod
import com.rork.rideax.ui.components.Avatar
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.RatingLabel
import com.rork.rideax.ui.components.SectionHeader
import com.rork.rideax.ui.components.icon
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.components.rideSwitchColors
import com.rork.rideax.ui.session.SessionUiState
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Profile, payment methods, saved places, preferences and the driver switch. */
@Composable
fun AccountScreen(
    state: SessionUiState,
    onMakeDefault: (PaymentMethod) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onSwitchToDriver: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    val account = state.account

    Column(
        modifier
            .fillMaxSize()
            .background(RideColors.Canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = RideDimens.Margin)
            .padding(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineMedium, color = RideColors.TextPrimary)

        if (account != null) {
            Row(
                Modifier.fillMaxWidth().rideCard().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(account.avatarUrl, account.initials, size = 60.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account.fullName, style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                    if (account.email.isNotBlank()) Text(account.email, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                    RatingLabel(account.rating, detail = "Member since " + SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(account.memberSince)))
                }
            }
        }

        SectionHeader("Payment", Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxWidth().rideCard()) {
            state.paymentMethods.forEach { method ->
                Row(
                    Modifier.fillMaxWidth().pressable({ onMakeDefault(method) }).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(Icons.Rounded.CreditCard)
                    Column(Modifier.weight(1f)) {
                        Text(method.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
                        Text("Saved cards appear in Stripe's checkout", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                    }
                    Icon(
                        if (method.isDefault) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        if (method.isDefault) "Default" else "Make default",
                        tint = if (method.isDefault) RideColors.Accent else RideColors.TextSecondary,
                    )
                }
            }
        }

        SectionHeader("Saved places", Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxWidth().rideCard()) {
            state.savedPlaces.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider(color = RideColors.Hairline, modifier = Modifier.padding(start = 66.dp))
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(place.kind.icon())
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
                        Text(place.subtitle, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                    }
                }
            }
        }

        SectionHeader("Preferences", Modifier.padding(top = 6.dp))
        Row(
            Modifier.fillMaxWidth().rideCard().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Icons.Rounded.Notifications)
            Column(Modifier.weight(1f)) {
                Text("Push notifications", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
                Text("Driver updates, receipts and offers", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
            Switch(checked = state.notificationsEnabled, onCheckedChange = onNotifications, colors = rideSwitchColors())
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .rideCard(borderColor = RideColors.Accent.copy(alpha = 0.4f))
                .pressable(onSwitchToDriver)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(Icons.Rounded.DirectionsCar, tint = RideColors.OnAccent, background = RideColors.Accent, size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text("Drive with RideAX", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
                Text("Switch to driver mode and earn on your schedule.", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
            }
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = RideColors.TextSecondary)
        }

        SignOutRow { confirmSignOut = true }
    }

    if (confirmSignOut) SignOutDialog(onConfirm = { confirmSignOut = false; onSignOut() }, onDismiss = { confirmSignOut = false })
}

@Composable
fun SignOutRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(RideDimens.ControlRadius))
            .background(RideColors.Surface)
            .border(1.dp, RideColors.Hairline, RoundedCornerShape(RideDimens.ControlRadius))
            .pressable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = RideColors.Danger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Sign out", style = MaterialTheme.typography.titleMedium, color = RideColors.Danger)
    }
}

@Composable
fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RideColors.Surface,
        title = { Text("Sign out of RideAX?", color = RideColors.TextPrimary) },
        text = { Text("Your trips and receipts stay safe in your account.", color = RideColors.TextSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out", color = RideColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = RideColors.TextPrimary) } },
    )
}
