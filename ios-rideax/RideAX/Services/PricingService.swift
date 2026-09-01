import Foundation

/// Remotely configurable pricing. In production these values are fetched from
/// the backend and every quote is re-validated server-side before a charge is
/// created — the client copy exists only so the rider sees a fare instantly.
nonisolated struct PricingConfiguration: Codable, Hashable, Sendable {
    var currencyCode: String
    var baseFare: Double
    var perKilometre: Double
    var perMinute: Double
    var bookingFee: Double
    var minimumFare: Double
    var cancellationFee: Double
    var surgeMultiplier: Double

    static let `default` = PricingConfiguration(
        currencyCode: "EUR",
        baseFare: 2.60,
        perKilometre: 1.10,
        perMinute: 0.28,
        bookingFee: 1.20,
        minimumFare: 6.50,
        cancellationFee: 4.00,
        surgeMultiplier: 1.0
    )
}

/// Live market conditions applied on top of the base tariff. Recomputed on a
/// timer while the rider is choosing a trip, so the fare on screen tracks the
/// current time of day, traffic and how many cars are free nearby.
nonisolated struct DemandSnapshot: Hashable, Sendable {
    var multiplier: Double
    var label: String
    var reason: String

    var isSurging: Bool { multiplier > 1.01 }

    /// "1.3x" — only meaningful while surging.
    var formattedMultiplier: String {
        multiplier.formatted(.number.precision(.fractionLength(1))) + "×"
    }

    static let normal = DemandSnapshot(
        multiplier: 1.0,
        label: "Standard fare",
        reason: "Plenty of cars near you right now"
    )
}

/// Builds fare quotes from a route estimate and the active pricing config.
nonisolated struct PricingService: Sendable {
    var configuration: PricingConfiguration = .default

    // MARK: - Demand

    /// Derives the live surge multiplier from time of day, free cars nearby and
    /// how badly the route is congested (actual travel time vs. free-flow).
    /// A production build replaces this with the server's demand feed; the
    /// shape of the result stays identical.
    func demand(
        at date: Date = .now,
        freeDriversNearby: Int,
        congestionRatio: Double
    ) -> DemandSnapshot {
        var multiplier = 1.0
        var label = "Standard fare"
        var reason = "Plenty of cars near you right now"

        let hour = Calendar.current.component(.hour, from: date)
        switch hour {
        case 7...9:
            multiplier += 0.22
            label = "Morning rush"
            reason = "Commuter demand is high right now"
        case 12...13:
            multiplier += 0.08
            label = "Lunchtime demand"
            reason = "A little busier than usual"
        case 17...19:
            multiplier += 0.30
            label = "Evening rush"
            reason = "Lots of riders heading home"
        case 22...23, 0...2:
            multiplier += 0.18
            label = "Late night"
            reason = "Fewer drivers are on the road"
        default:
            break
        }

        switch freeDriversNearby {
        case ...1:
            multiplier += 0.25
            label = "Very few cars"
            reason = "Only a couple of drivers are free nearby"
        case 2...3:
            multiplier += 0.12
            if !label.isEmpty && multiplier <= 1.13 {
                label = "Cars are scarce"
                reason = "Demand is outpacing free drivers nearby"
            }
        case 7...:
            multiplier = max(1.0, multiplier - 0.06)
        default:
            break
        }

        // congestionRatio: 1.0 = free flowing, >1 = slower than the limit.
        if congestionRatio > 1.35 {
            multiplier += 0.15
            label = "Heavy traffic"
            reason = "Roads on your route are congested"
        } else if congestionRatio > 1.15 {
            multiplier += 0.07
        }

        let rounded = (min(max(multiplier, 1.0), 2.5) * 100).rounded() / 100
        return DemandSnapshot(
            multiplier: rounded,
            label: rounded > 1.01 ? label : "Standard fare",
            reason: rounded > 1.01 ? reason : "Plenty of cars near you right now"
        )
    }

    // MARK: - Quotes

    func quote(
        for rideClass: RideClass,
        distanceMeters: Double,
        durationSeconds: Double,
        surge: Double? = nil
    ) -> FareQuote {
        let kilometres = max(distanceMeters, 0) / 1000
        let minutes = max(durationSeconds, 0) / 60
        let surgeMultiplier = surge ?? configuration.surgeMultiplier

        let base = configuration.baseFare * rideClass.multiplier
        let distanceCharge = kilometres * configuration.perKilometre * rideClass.multiplier
        let timeCharge = minutes * configuration.perMinute * rideClass.multiplier
        let subtotal = (base + distanceCharge + timeCharge) * surgeMultiplier
        let total = max(subtotal + configuration.bookingFee, configuration.minimumFare * rideClass.multiplier)

        return FareQuote(
            rideClassID: rideClass.id,
            distanceMeters: distanceMeters,
            durationSeconds: durationSeconds,
            base: round2(base),
            distanceCharge: round2(distanceCharge),
            timeCharge: round2(timeCharge),
            bookingFee: configuration.bookingFee,
            surgeMultiplier: surgeMultiplier,
            total: round2(total),
            currencyCode: configuration.currencyCode
        )
    }

    /// Quotes every tier for the same route so the rider can compare.
    func quotes(
        distanceMeters: Double,
        durationSeconds: Double,
        surge: Double? = nil
    ) -> [String: FareQuote] {
        RideClass.all.reduce(into: [:]) { result, rideClass in
            result[rideClass.id] = quote(
                for: rideClass,
                distanceMeters: distanceMeters,
                durationSeconds: durationSeconds,
                surge: surge
            )
        }
    }

    /// Per-kilometre and per-minute rates for a tier, shown in the breakdown.
    func rates(for rideClass: RideClass) -> (perKilometre: Double, perMinute: Double) {
        (
            round2(configuration.perKilometre * rideClass.multiplier),
            round2(configuration.perMinute * rideClass.multiplier)
        )
    }

    private func round2(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }
}
