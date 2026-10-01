package com.rork.rideax.ui.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.Place
import com.rork.rideax.ui.components.EmptyState
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.SectionHeader
import com.rork.rideax.ui.components.icon
import com.rork.rideax.ui.components.pressable
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import kotlinx.coroutines.delay

/** Destination picker with live place search and saved shortcuts. */
@Composable
fun DestinationSearchScreen(
    savedPlaces: List<Place>,
    search: suspend (String) -> List<Place>,
    onSelect: (Place) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val trimmed = query.trim()

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(trimmed) {
        if (trimmed.length < 2) {
            results = emptyList(); isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(320)
        results = search(trimmed)
        isSearching = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RideColors.Canvas)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = RideDimens.Margin, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Where to?", style = MaterialTheme.typography.titleLarge, color = RideColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = RideColors.TextSecondary,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClose).padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }

        Row(
            Modifier
                .padding(horizontal = RideDimens.Margin, vertical = 8.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(RideDimens.ControlRadius))
                .background(RideColors.Surface)
                .border(1.dp, RideColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(RideDimens.ControlRadius))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = RideColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search street, landmark or place", style = MaterialTheme.typography.bodyLarge, color = RideColors.TextSecondary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = RideColors.TextPrimary),
                    cursorBrush = SolidColor(RideColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { query = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Clear search", tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = RideDimens.Margin, end = RideDimens.Margin, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                trimmed.length < 2 -> {
                    item { SectionHeader("Saved places", Modifier.padding(top = 4.dp)) }
                    items(savedPlaces, key = { "saved-" + it.id }) { PlaceRow(it, onSelect) }
                    item { SectionHeader("Popular in your city", Modifier.padding(top = 10.dp)) }
                    items(Place.popular, key = { "popular-" + it.id }) { PlaceRow(it, onSelect) }
                }
                isSearching && results.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = RideColors.Accent, strokeWidth = 2.5.dp)
                    }
                }
                results.isEmpty() -> item {
                    EmptyState(Icons.Rounded.Place, "No places found", "Try a different street, landmark or business name.")
                }
                else -> items(results, key = { it.id }) { PlaceRow(it, onSelect) }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onSelect: (Place) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rideCard()
            .pressable({
                Haptics.tap()
                onSelect(place)
            })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(place.kind.icon())
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RideColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place.subtitle, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.NorthEast, null, tint = RideColors.TextSecondary, modifier = Modifier.size(16.dp))
    }
}
