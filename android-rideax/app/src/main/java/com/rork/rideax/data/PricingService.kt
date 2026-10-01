package com.rork.rideax.data

import com.rork.rideax.model.FareQuote
import com.rork.rideax.model.RideClass
import com.rork.rideax.model.formatOneDecimal
import com.rork.rideax.model.round2
import java.util.Calendar
import kotlin.math.max

/**
 * Remotely configurable pricing. Every quote is re-validated server-side
 * before a charge is created — this copy exists so the rider sees a fare
 * instantly. Values match the iOS app exactly.
 */
data class PricingConfiguration(
    val currencyCode: String = "EUR",
    val baseFare: Double = 2.60,
    val perKilometre: Double = 1.10,
    val perMinute: Double = 0.28,
    val bookingFee: Double = 1.20,
    val minimumFare: Double = 6.50,
    val cancellationFee: Double = 4.00,
    val surgeMultiplier: Double = 1.0,
)

/** Live market conditions applied on top of the base tariff. */
data class DemandSnapshot(val multiplier: Double, val label: String, val reason: String) {
    val isSurging: Boolean get() = multiplier > 1.01
    val formattedMultiplier: String get() = formatOneDecimal(multiplier) + "×"

    companion object {
        val Normal = DemandSnapshot(1.0, "Standard fare", "Plenty of cars near you right now")
    }
}

/** Builds fare quotes from a route estimate and the active pricing config. */
class PricingService(val configuration: PricingConfiguration = PricingConfiguration()) {

    /**
     * Derives the live surge multiplier from time of day, free cars nearby and
     * how congested the route is (actual travel time vs. free-flow).
     */
    fun demand(
        nowMillis: Long = System.currentTimeMillis(),
        freeDriversNearby: Int,
        congestionRatio: Double,
    ): DemandSnapshot {
        var multiplier = 1.0
        var label = "Standard fare"
        var reason = "Plenty of cars near you right now"

        val hour = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 7..9 -> {
                multiplier += 0.22; label = "Morning rush"; reason = "Commuter demand is high right now"
            }
            in 12..13 -> {
                multiplier += 0.08; label = "Lunchtime demand"; reason = "A little busier than usual"
            }
            in 17..19 -> {
                multiplier += 0.30; label = "Evening rush"; reason = "Lots of riders heading home"
            }
            22, 23, 0, 1, 2 -> {
                multiplier += 0.18; label = "Late night"; reason = "Fewer drivers are on the road"
            }
        }

        when {
            freeDriversNearby <= 1 -> {
                multiplier += 0.25; label = "Very few cars"; reason = "Only a couple of drivers are free nearby"
            }
            freeDriversNearby in 2..3 -> {
                multiplier += 0.12
                if (label.isNotEmpty() && multiplier <= 1.13) {
                    label = "Cars are scarce"; reason = "Demand is outpacing free drivers nearby"
                }
            }
            freeDriversNearby >= 7 -> multiplier = max(1.0, multiplier - 0.06)
        }

        if (congestionRatio > 1.35) {
            multiplier += 0.15; label = "Heavy traffic"; reason = "Roads on your route are congested"
        } else if (congestionRatio > 1.15) {
            multiplier += 0.07
        }

        val rounded = round2(multiplier.coerceIn(1.0, 2.5))
        return if (rounded > 1.01) DemandSnapshot(rounded, label, reason) else DemandSnapshot(rounded, DemandSnapshot.Normal.label, DemandSnapshot.Normal.reason)
    }

    fun quote(rideClass: RideClass, distanceMeters: Double, durationSeconds: Double, surge: Double? = null): FareQuote {
        val kilometres = max(distanceMeters, 0.0) / 1000
        val minutes = max(durationSeconds, 0.0) / 60
        val surgeMultiplier = surge ?: configuration.surgeMultiplier

        val base = configuration.baseFare * rideClass.multiplier
        val distanceCharge = kilometres * configuration.perKilometre * rideClass.multiplier
        val timeCharge = minutes * configuration.perMinute * rideClass.multiplier
        val subtotal = (base + distanceCharge + timeCharge) * surgeMultiplier
        val total = max(subtotal + configuration.bookingFee, configuration.minimumFare * rideClass.multiplier)

        return FareQuote(
            rideClassId = rideClass.id,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            base = round2(base),
            distanceCharge = round2(distanceCharge),
            timeCharge = round2(timeCharge),
            bookingFee = configuration.bookingFee,
            surgeMultiplier = surgeMultiplier,
            total = round2(total),
            currencyCode = configuration.currencyCode,
        )
    }

    /** Quotes every tier for the same route so the rider can compare. */
    fun quotes(distanceMeters: Double, durationSeconds: Double, surge: Double? = null): Map<String, FareQuote> =
        RideClass.all.associate { it.id to quote(it, distanceMeters, durationSeconds, surge) }

    /** Per-kilometre and per-minute rates for a tier, shown in the breakdown. */
    fun rates(rideClass: RideClass): Pair<Double, Double> =
        round2(configuration.perKilometre * rideClass.multiplier) to round2(configuration.perMinute * rideClass.multiplier)
}
