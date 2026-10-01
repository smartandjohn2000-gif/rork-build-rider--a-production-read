package com.rork.rideax.ui.rider

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.FareQuote
import com.rork.rideax.model.Place
import com.rork.rideax.model.RideClass
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.PrimaryButton
import com.rork.rideax.ui.components.RouteSummaryRow
import com.rork.rideax.ui.components.icon
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.map.CameraTarget
import com.rork.rideax.ui.map.MapPin
import com.rork.rideax.ui.map.MapScene
import com.rork.rideax.ui.map.PinKind
import com.rork.rideax.ui.map.RideMap
import com.rork.rideax.ui.session.SessionUiState
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import com.rork.rideax.ui.theme.tabular
import java.util.Calendar

/** Rider home: full-bleed map, search capsule on top, booking sheet below. */
@Composable
fun BookingScreen(
    state: RideUiState,
    session: SessionUiState,
    viewModel: RideViewModel,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showsSearch by rememberSaveable { mutableStateOf(false) }
    var fareExpanded by rememberSaveable { mutableStateOf(false) }
    val destination = state.destination

    val scene = remember(state.pickup, destination, state.routeEstimate, state.nearbyDrivers) {
        MapScene(
            pins = listOfNotNull(MapPin(state.pickup.point, PinKind.PICKUP), destination?.let { MapPin(it.point, PinKind.DROPOFF) }),
            route = state.routeEstimate?.polyline.orEmpty(),
            nearbyCars = if (destination == null) state.nearbyDrivers else state.nearbyDrivers.take(3),
        )
    }
    val pickupKey = if (destination == null) state.pickup.point else null
    val camera = remember(pickupKey, destination?.id, state.routeEstimate) {
        val points = if (destination == null) listOf(state.pickup.point)
        else listOf(state.pickup.point, destination.point) + state.routeEstimate?.polyline.orEmpty()
        CameraTarget(points, topInset = 0.2, bottomInset = if (destination == null) 0.36 else 0.6)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sheetMax = maxHeight * 0.66f
        RideMap(scene = scene, camera = camera, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchCapsule(
                title = destination?.name ?: "Where to?",
                hasDestination = destination != null,
                onClick = { showsSearch = true },
                onClear = { viewModel.setDestination(null) },
                modifier = Modifier.padding(horizontal = RideDimens.Margin),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = RideDimens.Margin),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!hasLocationPermission) {
                    Chip(Icons.Rounded.MyLocation, "Use my location", highlighted = true, onClick = onRequestLocation)
                }
                session.savedPlaces.forEach { place ->
                    Chip(place.kind.icon(), place.name, highlighted = destination?.id == place.id) { viewModel.setDestination(place) }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = sheetMax)
                .shadow(24.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(RideColors.Surface)
                .border(1.dp, RideColors.Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f)),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 38.dp, height = 4.dp).clip(CircleShape).background(RideColors.TextSecondary.copy(alpha = 0.35f)))
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RideDimens.Margin)
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.errorMessage?.let { ErrorBanner(it, onDismiss = viewModel::clearError) }
                if (destination == null) {
                    IdleSheet(session.account?.firstName, state.pickup, onSearch = { showsSearch = true }, onPick = viewModel::setDestination)
                } else {
                    BookingSheet(state, session, viewModel, fareExpanded, onToggleFare = { fareExpanded = !fareExpanded })
                }
            }
        }

        AnimatedVisibility(
            visible = showsSearch,
            enter = slideInVertically { it / 6 } + fadeIn(),
            exit = slideOutVertically { it / 6 } + fadeOut(),
        ) {
            BackHandler { showsSearch = false }
            DestinationSearchScreen(
                savedPlaces = session.savedPlaces,
                search = viewModel::searchPlaces,
                onSelect = { place ->
                    viewModel.setDestination(place)
                    showsSearch = false
                },
                onClose = { showsSearch = false },
            )
        }
    }
}

@Composable
private fun SearchCapsule(title: String, hasDestination: Boolean, onClick: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .rideCard(floating = true, radius = 28.dp)
            .pressable(onClick)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = RideColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (hasDestination) RideColors.TextPrimary else RideColors.TextPrimary.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasDestination) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Clear destination") { onClear() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Clear destination", tint = RideColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        } else {
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(RideColors.SurfaceRaised).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Rounded.Schedule, null, tint = RideColors.TextSecondary, modifier = Modifier.size(15.dp))
                Text("Now", style = MaterialTheme.typography.labelLarge, color = RideColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val border by animateColorAsState(if (highlighted) RideColors.Accent else RideColors.Hairline, label = "chip")
    Row(
        Modifier
            .height(38.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(RideColors.Surface.copy(alpha = 0.96f))
            .border(1.dp, border, CircleShape)
            .clickable(role = Role.Button) {
                Haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = RideColors.Accent, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = RideColors.TextPrimary)
    }
}

@Composable
private fun IdleSheet(firstName: String?, pickup: Place, onSearch: () -> Unit, onPick: (Place) -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (firstName != null) "$greeting, $firstName" else greeting, style = MaterialTheme.typography.labelLarge, color = RideColors.TextSecondary)
        Text("Where are you heading?", style = MaterialTheme.typography.headlineSmall, color = RideColors.TextPrimary)
    }
    Row(
        Modifier.fillMaxWidth().rideCard(color = RideColors.SurfaceRaised).pressable(onSearch).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(Icons.Rounded.MyLocation, background = RideColors.Accent.copy(alpha = 0.14f))
        Column(Modifier.weight(1f)) {
            Text("Pickup", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
            Text(pickup.name, style = MaterialTheme.typography.bodyMedium, color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Set destination", style = MaterialTheme.typography.labelLarge, color = RideColors.Accent)
    }
    Text("POPULAR NEARBY", style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    Place.popular.take(3).forEach { place ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable({ onPick(place) }).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(place.kind.icon(), size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary)
                Text(place.subtitle, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BookingSheet(state: RideUiState, session: SessionUiState, viewModel: RideViewModel, fareExpanded: Boolean, onToggleFare: () -> Unit) {
    val destination = state.destination ?: return
    RouteSummaryRow(state.pickup.name, destination.name, compact = true)

    LiveFareCard(
        quote = state.selectedQuote,
        demand = state.demand,
        estimate = state.routeEstimate,
        rates = viewModel.selectedRates(),
        isRefreshing = state.isQuoting || state.isRepricing,
        updatedAt = state.quoteUpdatedAt,
        fareChange = state.lastFareChange,
        isFareCurrent = state.isFareCurrent,
        driftMeters = state.pickupDriftMeters,
        isExpanded = fareExpanded,
        onToggle = onToggleFare,
        onRefresh = viewModel::repriceNow,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RideClass.all.forEach { rideClass ->
            RideClassRow(rideClass, state.quotes[rideClass.id], rideClass.id == state.selectedClassId) { viewModel.selectClass(rideClass.id) }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CreditCard, null, tint = RideColors.Accent, modifier = Modifier.size(20.dp))
        Text(session.defaultPaymentMethod?.displayName ?: "Add a payment method", style = MaterialTheme.typography.bodyMedium, color = RideColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Lock, null, tint = RideColors.TextSecondary, modifier = Modifier.size(13.dp))
        Text("Secured by Stripe", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
    }

    val quote = state.selectedQuote
    PrimaryButton(
        text = if (quote != null) "Request ${state.selectedClass.name} · ${quote.formattedTotal}" else "Request ${state.selectedClass.name}",
        onClick = { viewModel.requestRide(session.defaultPaymentMethod) },
        enabled = quote != null && !state.isQuoting,
        loading = state.isQuoting && quote == null,
    )
}

@Composable
private fun RideClassRow(rideClass: RideClass, quote: FareQuote?, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RideDimens.ControlRadius)
    val border by animateColorAsState(if (isSelected) RideColors.Accent else RideColors.Hairline, label = "class-border")
    val background by animateColorAsState(if (isSelected) RideColors.Accent.copy(alpha = 0.08f) else Color.Transparent, label = "class-bg")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(if (isSelected) 1.5.dp else 1.dp, border, shape)
            .semantics { selected = isSelected }
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(rideClass.kind.icon(), tint = if (isSelected) RideColors.Accent else RideColors.TextPrimary, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rideClass.name, style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary)
                Icon(Icons.Rounded.Person, null, tint = RideColors.TextSecondary, modifier = Modifier.size(13.dp))
                Text("${rideClass.seats}", style = MaterialTheme.typography.labelMedium, color = RideColors.TextSecondary)
            }
            Text("${rideClass.etaMinutes} min away · ${rideClass.tagline}", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            quote?.formattedTotal ?: "—",
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = if (isSelected) RideColors.TextPrimary else RideColors.TextPrimary.copy(alpha = 0.85f),
        )
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RideDimens.ControlRadius))
            .background(RideColors.Danger.copy(alpha = 0.14f))
            .border(1.dp, RideColors.Danger.copy(alpha = 0.4f), RoundedCornerShape(RideDimens.ControlRadius))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Warning, null, tint = RideColors.Danger, modifier = Modifier.size(18.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = RideColors.TextPrimary, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}
