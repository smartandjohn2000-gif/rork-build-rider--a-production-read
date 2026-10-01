package com.rork.rideax.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What a place is, so the UI can pick a matching glyph. */
@Serializable
enum class PlaceKind { CURRENT, HOME, WORK, AIRPORT, TRAIN, MUSEUM, LANDMARK, THEATRE, PIN }

/** A named location the rider can pick from or search for. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val point: GeoPoint,
    val kind: PlaceKind = PlaceKind.PIN,
) {
    companion object {
        val defaultSaved: List<Place> = listOf(
            Place("home", "Home", "Rue Oberkampf", GeoPoint(48.8656, 2.3712), PlaceKind.HOME),
            Place("work", "Work", "La Défense", GeoPoint(48.8918, 2.2380), PlaceKind.WORK),
            Place("airport", "CDG Airport", "Roissy-en-France", GeoPoint(49.0097, 2.5479), PlaceKind.AIRPORT),
        )

        val popular: List<Place> = listOf(
            Place("gare-du-nord", "Gare du Nord", "18 Rue de Dunkerque", GeoPoint(48.8809, 2.3553), PlaceKind.TRAIN),
            Place("louvre", "Louvre Museum", "Rue de Rivoli", GeoPoint(48.8606, 2.3376), PlaceKind.MUSEUM),
            Place("montmartre", "Montmartre", "Sacré-Cœur, 18th arr.", GeoPoint(48.8867, 2.3431), PlaceKind.LANDMARK),
            Place("bastille", "Bastille", "Place de la Bastille", GeoPoint(48.8532, 2.3692), PlaceKind.THEATRE),
        )
    }
}

/**
 * The city RideAX currently operates in. A GPS fix outside it keeps the
 * pickup at the city centre, so fares are never quoted across continents.
 */
object ServiceArea {
    val center: GeoPoint = GeoPoint(48.8656, 2.3712)
    private const val RADIUS_METERS = 60_000.0

    fun contains(point: GeoPoint): Boolean = center.distanceTo(point) <= RADIUS_METERS
}

/** The full lifecycle of a ride, mirroring the server-side state machine. */
enum class RideStatus(val wire: String, val title: String) {
    REQUESTED("requested", "Requesting"),
    MATCHING("matching", "Finding your driver"),
    ACCEPTED("accepted", "Driver assigned"),
    ARRIVING("arriving", "Driver on the way"),
    ARRIVED("arrived", "Driver has arrived"),
    STARTED("started", "Trip starting"),
    IN_PROGRESS("inProgress", "On the trip"),
    COMPLETED("completed", "Trip completed"),
    PAID("paid", "Paid"),
    CANCELLED("cancelled", "Cancelled");

    /** True while the rider still has a live ride on screen. */
    val isActive: Boolean get() = this != COMPLETED && this != PAID && this != CANCELLED

    /** The rider may cancel free of charge up until the driver arrives. */
    val allowsCancellation: Boolean
        get() = this == REQUESTED || this == MATCHING || this == ACCEPTED || this == ARRIVING || this == ARRIVED

    val isDriverApproaching: Boolean get() = this == ACCEPTED || this == ARRIVING || this == ARRIVED
    val isOnTrip: Boolean get() = this == STARTED || this == IN_PROGRESS
}

enum class VehicleKind { CAR, COMFORT, VAN }

/** A bookable vehicle tier. Pricing multipliers are configuration, not code. */
data class RideClass(
    val id: String,
    val name: String,
    val tagline: String,
    val seats: Int,
    val multiplier: Double,
    val etaMinutes: Int,
    val kind: VehicleKind,
) {
    companion object {
        val all: List<RideClass> = listOf(
            RideClass("go", "RideAX Go", "Affordable everyday rides", 4, 1.0, 3, VehicleKind.CAR),
            RideClass("comfort", "Comfort", "Newer cars, extra legroom", 4, 1.35, 5, VehicleKind.COMFORT),
            RideClass("xl", "XL", "Up to 6 passengers", 6, 1.7, 7, VehicleKind.VAN),
        )
    }
}

/** The driver as the rider sees them. */
data class DriverProfile(
    val id: String,
    val name: String,
    val rating: Double,
    val tripCount: Int,
    val vehicle: String,
    val plate: String,
    val photoUrl: String?,
) {
    companion object {
        val samples: List<DriverProfile> = listOf(
            DriverProfile("d1", "Ahmed K.", 4.9, 1204, "Grey Toyota Camry", "742-KLD", "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&h=200&fit=crop"),
            DriverProfile("d2", "Léa M.", 4.95, 862, "Blue Peugeot 508", "318-QRP", "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=200&h=200&fit=crop"),
            DriverProfile("d3", "Marco R.", 4.87, 2310, "Black Škoda Octavia", "905-VTL", "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200&h=200&fit=crop"),
        )
    }
}

/**
 * A fare quote produced by the pricing engine. Keys match the iOS app so the
 * `fare_breakdown` column reads the same from either platform.
 */
@Serializable
data class FareQuote(
    @SerialName("rideClassID") val rideClassId: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val base: Double,
    val distanceCharge: Double,
    val timeCharge: Double,
    val bookingFee: Double,
    val surgeMultiplier: Double,
    val total: Double,
    val currencyCode: String,
) {
    val formattedTotal: String get() = formatMoney(total, currencyCode)
    val kilometres: Double get() = max(distanceMeters, 0.0) / 1000
    val minutes: Int get() = max(1, (durationSeconds / 60).roundToInt())
    val isSurging: Boolean get() = surgeMultiplier > 1.01

    /** Charges before the booking fee, after any surge is applied. */
    val surgedSubtotal: Double get() = round2((base + distanceCharge + timeCharge) * surgeMultiplier)

    /** The extra the rider pays because of live demand. */
    val surgeAmount: Double get() = max(0.0, surgedSubtotal - (base + distanceCharge + timeCharge))

    /** True when the tariff came out below the minimum and got lifted to it. */
    val hitMinimumFare: Boolean get() = total > surgedSubtotal + bookingFee + 0.005

    fun money(value: Double): String = formatMoney(value, currencyCode)

    /** Itemised fare, recomputed every time the route or demand changes. */
    fun lines(perKilometre: Double, perMinute: Double): List<FareLine> = buildList {
        add(FareLine("Base fare", null, money(base)))
        add(FareLine("Distance", "${formatOneDecimal(kilometres)} km × ${money(perKilometre)}", money(distanceCharge)))
        add(FareLine("Time", "$minutes min × ${money(perMinute)}", money(timeCharge)))
        if (isSurging) {
            add(FareLine("Live demand", "${formatOneDecimal(surgeMultiplier)}× multiplier", "+" + money(surgeAmount), true))
        }
        add(FareLine("Booking fee", null, money(bookingFee)))
        if (hitMinimumFare) {
            add(FareLine("Minimum fare applied", "Short trips are billed at the city minimum", money(total), true))
        }
    }
}

/** One itemised row in the live fare breakdown. */
data class FareLine(
    val label: String,
    val detail: String?,
    val amount: String,
    val isHighlighted: Boolean = false,
)

/**
 * A payment method shown at checkout. Card data never lives on device — real
 * cards are held by Stripe and offered inside its sheet.
 */
@Serializable
data class PaymentMethod(
    val id: String,
    val brand: String,
    val last4: String,
    val isDefault: Boolean,
    val supportsWallet: Boolean,
) {
    val displayName: String
        get() = when {
            last4.isEmpty() -> brand
            supportsWallet -> "$brand •• $last4 · Google Pay"
            else -> "$brand •• $last4"
        }

    companion object {
        val defaults: List<PaymentMethod> = listOf(
            PaymentMethod("pm_stripe", "Card via Stripe", "", isDefault = true, supportsWallet = false),
        )
    }
}

/** A live ride. */
data class Ride(
    val id: String,
    val status: RideStatus,
    val pickup: Place,
    val dropoff: Place,
    val rideClass: RideClass,
    val quote: FareQuote,
    val driver: DriverProfile? = null,
    val requestedAt: Long,
    val completedAt: Long? = null,
    val driverPoint: GeoPoint? = null,
    val routeToPickup: List<GeoPoint> = emptyList(),
    val routeToDropoff: List<GeoPoint> = emptyList(),
    val riderRatingForDriver: Int? = null,
    val paymentMethodId: String? = null,
    val cancellationReason: String? = null,
)

/** What the rider was actually charged, read back from the settled payment. */
data class Receipt(
    val id: String,
    val rideId: String,
    val total: Double,
    val currencyCode: String,
    val paymentMethodLabel: String,
    val paidAt: Long,
    val breakdown: FareQuote,
    /** Stripe's hosted receipt, available once the webhook confirmed the charge. */
    val receiptUrl: String? = null,
    /** False when settled locally because card payments aren't switched on yet. */
    val isLiveCharge: Boolean = false,
) {
    val formattedTotal: String get() = formatMoney(total, currencyCode)
}

/** A completed trip as shown in Activity and driver history. */
@Serializable
data class TripRecord(
    val id: String,
    val pickupName: String,
    val dropoffName: String,
    val date: Long,
    val fare: Double,
    val currencyCode: String,
    val className: String,
    val counterpartName: String,
    val rating: Int? = null,
    val wasCancelled: Boolean = false,
) {
    val formattedFare: String get() = formatMoney(fare, currencyCode)
}

/** An incoming job offered to an online driver. */
data class RideOffer(
    val id: String,
    val riderName: String,
    val riderRating: Double,
    val pickup: Place,
    val dropoff: Place,
    val pickupDistanceMeters: Double,
    val pickupEtaMinutes: Int,
    val tripDistanceMeters: Double,
    val fare: Double,
    val currencyCode: String,
    val className: String,
) {
    val formattedFare: String get() = formatMoney(fare, currencyCode)
}

/** Earnings for a single day, used by the driver earnings chart. */
data class DailyEarnings(val dayLabel: String, val amount: Double, val trips: Int)

internal fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0

internal fun nearlyZero(value: Double): Boolean = abs(value) < 0.005
