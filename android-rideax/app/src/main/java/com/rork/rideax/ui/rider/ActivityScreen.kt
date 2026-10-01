package com.rork.rideax.ui.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.model.TripRecord
import com.rork.rideax.model.formatMoney
import com.rork.rideax.model.formatOneDecimal
import com.rork.rideax.ui.components.EmptyState
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.RouteSummaryRow
import com.rork.rideax.ui.components.SectionHeader
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular
import java.text.DateFormat
import java.util.Date

/** Rider trip history with a spend summary and per-trip receipts. */
@Composable
fun ActivityScreen(trips: List<TripRecord>, onRate: (String, Int) -> Unit, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<TripRecord?>(null) }
    val completed = trips.filter { !it.wasCancelled }
    val rated = completed.mapNotNull { it.rating }

    LazyColumn(
        modifier.fillMaxSize().background(RideColors.Canvas),
        contentPadding = PaddingValues(start = RideDimens.Margin, end = RideDimens.Margin, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, color = RideColors.TextPrimary, modifier = Modifier.statusBarsPadding().padding(top = 16.dp, bottom = 6.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().rideCard().padding(16.dp)) {
                Stat("Spent", formatMoney(completed.sumOf { it.fare }, "EUR"), Modifier.weight(1f), highlight = true)
                Stat("Trips", "${completed.size}", Modifier.weight(1f))
                Stat("Avg rating", if (rated.isEmpty()) "—" else formatOneDecimal(rated.average()), Modifier.weight(1f))
            }
        }
        item { SectionHeader("Past trips", Modifier.padding(top = 8.dp)) }
        if (trips.isEmpty()) {
            item { EmptyState(Icons.Rounded.DirectionsCar, "No trips yet", "Your rides will appear here once you've taken one.") }
        }
        items(trips, key = { it.id }) { trip -> TripRow(trip) { selected = trip } }
    }

    selected?.let { trip ->
        val current = trips.firstOrNull { it.id == trip.id } ?: trip
        ReceiptSheet(current, onRate = { onRate(current.id, it) }, onDismiss = { selected = null })
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.tabular(), color = if (highlight) RideColors.AccentWarm else RideColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary)
    }
}

@Composable
private fun TripRow(trip: TripRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rideCard().pressable(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(Icons.Rounded.DirectionsCar, tint = if (trip.wasCancelled) RideColors.TextSecondary else RideColors.Accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${trip.pickupName} → ${trip.dropoffName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.date)), style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                Text("· ${trip.className}", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                trip.rating?.let {
                    Text("·", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                    Icon(Icons.Rounded.Star, null, tint = RideColors.AccentWarm, modifier = Modifier.size(12.dp))
                    Text("$it", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
                }
            }
        }
        Text(
            if (trip.wasCancelled) "Cancelled" else trip.formattedFare,
            style = MaterialTheme.typography.titleSmall.tabular(),
            color = if (trip.wasCancelled) RideColors.Danger else RideColors.TextPrimary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptSheet(trip: TripRecord, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RideColors.Canvas) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = RideDimens.Margin).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (trip.wasCancelled) "Cancelled trip" else trip.formattedFare, style = MaterialTheme.typography.headlineMedium.tabular(), color = RideColors.TextPrimary)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(trip.date)) + " · " + trip.className,
                style = MaterialTheme.typography.bodySmall,
                color = RideColors.TextSecondary,
            )
            RouteSummaryRow(trip.pickupName, trip.dropoffName, Modifier.fillMaxWidth().clip(RoundedCornerShape(RideDimens.ControlRadius)).background(RideColors.Surface).padding(14.dp))
            if (!trip.wasCancelled) {
                Text("Rate ${trip.counterpartName}", style = MaterialTheme.typography.bodyMedium, color = RideColors.TextSecondary)
                StarRating(trip.rating ?: 0, onRate = onRate)
            }
        }
    }
}
