import Foundation
import CoreLocation

/// Codable coordinate wrapper so rides and places can be persisted.
nonisolated struct GeoPoint: Codable, Hashable, Sendable {
    var latitude: Double
    var longitude: Double

    init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    init(_ coordinate: CLLocationCoordinate2D) {
        self.latitude = coordinate.latitude
        self.longitude = coordinate.longitude
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Great-circle distance in metres.
    func distance(to other: GeoPoint) -> Double {
        CLLocation(latitude: latitude, longitude: longitude)
            .distance(from: CLLocation(latitude: other.latitude, longitude: other.longitude))
    }

    /// Linear interpolation used to animate a vehicle between two points.
    func interpolated(to other: GeoPoint, progress: Double) -> GeoPoint {
        let t = min(max(progress, 0), 1)
        return GeoPoint(
            latitude: latitude + (other.latitude - latitude) * t,
            longitude: longitude + (other.longitude - longitude) * t
        )
    }

    /// Initial bearing towards `other` in degrees, 0° = north, clockwise.
    /// Used to point the vehicle marker along its direction of travel.
    func bearing(to other: GeoPoint) -> Double {
        let lat1 = latitude * .pi / 180
        let lat2 = other.latitude * .pi / 180
        let deltaLon = (other.longitude - longitude) * .pi / 180
        let y = sin(deltaLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        let degrees = atan2(y, x) * 180 / .pi
        return degrees < 0 ? degrees + 360 : degrees
    }
}

/// A single driver location update — the same payload a realtime backend
/// (socket or channel subscription) pushes every couple of seconds.
///
/// Pings are deliberately sparse; the map never draws them raw, it eases the
/// marker from the previous ping to this one over `expectedInterval`.
nonisolated struct DriverPing: Hashable, Sendable {
    /// Monotonic counter so out-of-order or duplicate updates can be dropped.
    var sequence: Int
    var point: GeoPoint
    /// How far along the current leg's polyline the driver is, 0...1.
    var progress: Double
    /// Direction of travel in degrees, 0° = north.
    var heading: Double
    var speedMetersPerSecond: Double
    var timestamp: Date
    /// Cadence the next ping is expected at, which sets the easing window.
    var expectedInterval: Double

    /// Speed in km/h, shown next to the vehicle while it is moving.
    var speedKilometresPerHour: Int { Int((speedMetersPerSecond * 3.6).rounded()) }

    var isStationary: Bool { speedMetersPerSecond < 0.6 }
}

/// A named location the rider can pick from or search for.
nonisolated struct Place: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var name: String
    var subtitle: String
    var point: GeoPoint
    var symbol: String

    init(id: String = UUID().uuidString, name: String, subtitle: String, point: GeoPoint, symbol: String = "mappin.circle.fill") {
        self.id = id
        self.name = name
        self.subtitle = subtitle
        self.point = point
        self.symbol = symbol
    }
}

/// The full lifecycle of a ride, mirroring the server-side state machine.
nonisolated enum RideStatus: String, Codable, CaseIterable, Sendable {
    case requested
    case matching
    case accepted
    case arriving
    case arrived
    case started
    case inProgress
    case completed
    case paid
    case cancelled

    var title: String {
        switch self {
        case .requested: "Requesting"
        case .matching: "Finding your driver"
        case .accepted: "Driver assigned"
        case .arriving: "Driver on the way"
        case .arrived: "Driver has arrived"
        case .started: "Trip starting"
        case .inProgress: "On the trip"
        case .completed: "Trip completed"
        case .paid: "Paid"
        case .cancelled: "Cancelled"
        }
    }

    /// True while the rider still has a live ride on screen.
    var isActive: Bool {
        switch self {
        case .completed, .paid, .cancelled: false
        default: true
        }
    }

    /// The rider may cancel free of charge up until the driver arrives.
    var allowsCancellation: Bool {
        switch self {
        case .requested, .matching, .accepted, .arriving, .arrived: true
        default: false
        }
    }
}

/// A bookable vehicle tier. Pricing multipliers are configuration, not code.
nonisolated struct RideClass: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var name: String
    var tagline: String
    var seats: Int
    var multiplier: Double
    var etaMinutes: Int
    var symbol: String

    static let all: [RideClass] = [
        RideClass(id: "go", name: "RideAX Go", tagline: "Affordable everyday rides", seats: 4, multiplier: 1.0, etaMinutes: 3, symbol: "car.fill"),
        RideClass(id: "comfort", name: "Comfort", tagline: "Newer cars, extra legroom", seats: 4, multiplier: 1.35, etaMinutes: 5, symbol: "car.side.fill"),
        RideClass(id: "xl", name: "XL", tagline: "Up to 6 passengers", seats: 6, multiplier: 1.7, etaMinutes: 7, symbol: "bus.fill")
    ]
}

/// The driver as the rider sees them.
nonisolated struct DriverProfile: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var name: String
    var rating: Double
    var tripCount: Int
    var vehicle: String
    var plate: String
    var photoURL: URL?

    static let samples: [DriverProfile] = [
        DriverProfile(id: "d1", name: "Ahmed K.", rating: 4.9, tripCount: 1204, vehicle: "Grey Toyota Camry", plate: "742-KLD", photoURL: URL(string: "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&h=200&fit=crop")),
        DriverProfile(id: "d2", name: "Léa M.", rating: 4.95, tripCount: 862, vehicle: "Blue Peugeot 508", plate: "318-QRP", photoURL: URL(string: "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=200&h=200&fit=crop")),
        DriverProfile(id: "d3", name: "Marco R.", rating: 4.87, tripCount: 2310, vehicle: "Black Škoda Octavia", plate: "905-VTL", photoURL: URL(string: "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200&h=200&fit=crop"))
    ]
}

/// A fare quote produced by the pricing engine and validated before charge.
nonisolated struct FareQuote: Codable, Hashable, Sendable {
    var rideClassID: String
    var distanceMeters: Double
    var durationSeconds: Double
    var base: Double
    var distanceCharge: Double
    var timeCharge: Double
    var bookingFee: Double
    var surgeMultiplier: Double
    var total: Double
    var currencyCode: String

    var formattedTotal: String {
        total.formatted(.currency(code: currencyCode).precision(.fractionLength(2)))
    }

    var kilometres: Double { max(distanceMeters, 0) / 1000 }

    var minutes: Int { max(1, Int((durationSeconds / 60).rounded())) }

    var isSurging: Bool { surgeMultiplier > 1.01 }

    /// Charges before the booking fee, after any surge is applied.
    var surgedSubtotal: Double {
        ((base + distanceCharge + timeCharge) * surgeMultiplier * 100).rounded() / 100
    }

    /// The extra the rider pays because of live demand.
    var surgeAmount: Double {
        max(0, surgedSubtotal - (base + distanceCharge + timeCharge))
    }

    /// True when the tariff came out below the minimum and got lifted to it.
    var hitMinimumFare: Bool {
        total > surgedSubtotal + bookingFee + 0.005
    }

    func money(_ value: Double) -> String {
        value.formatted(.currency(code: currencyCode).precision(.fractionLength(2)))
    }

    /// Itemised fare, recomputed every time the route or demand changes.
    func lines(perKilometre: Double, perMinute: Double) -> [FareLine] {
        var lines: [FareLine] = [
            FareLine(label: "Base fare", detail: nil, amount: money(base)),
            FareLine(
                label: "Distance",
                detail: "\(kilometres.formatted(.number.precision(.fractionLength(1)))) km × \(money(perKilometre))",
                amount: money(distanceCharge)
            ),
            FareLine(
                label: "Time",
                detail: "\(minutes) min × \(money(perMinute))",
                amount: money(timeCharge)
            )
        ]
        if isSurging {
            lines.append(
                FareLine(
                    label: "Live demand",
                    detail: surgeMultiplier.formatted(.number.precision(.fractionLength(1))) + "× multiplier",
                    amount: "+" + money(surgeAmount),
                    isHighlighted: true
                )
            )
        }
        lines.append(FareLine(label: "Booking fee", detail: nil, amount: money(bookingFee)))
        if hitMinimumFare {
            lines.append(
                FareLine(
                    label: "Minimum fare applied",
                    detail: "Short trips are billed at the city minimum",
                    amount: money(total),
                    isHighlighted: true
                )
            )
        }
        return lines
    }
}

/// One itemised row in the live fare breakdown.
nonisolated struct FareLine: Identifiable, Hashable, Sendable {
    var id: String { label }
    var label: String
    var detail: String?
    var amount: String
    var isHighlighted: Bool = false
}

/// A payment method shown at checkout. Card data never lives on device —
/// only the network + last four returned by the payment provider.
nonisolated struct PaymentMethod: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var brand: String
    var last4: String
    var isDefault: Bool
    var supportsWallet: Bool

    var displayName: String {
        guard !last4.isEmpty else { return brand }
        return supportsWallet ? "\(brand) •• \(last4) · Apple Pay" : "\(brand) •• \(last4)"
    }
}

/// A live or historical ride.
nonisolated struct Ride: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var status: RideStatus
    var pickup: Place
    var dropoff: Place
    var rideClass: RideClass
    var quote: FareQuote
    var driver: DriverProfile?
    var requestedAt: Date
    var completedAt: Date?
    var driverPoint: GeoPoint?
    var routeToPickup: [GeoPoint]
    var routeToDropoff: [GeoPoint]
    var riderRatingForDriver: Int?
    var paymentMethodID: String?
    var cancellationReason: String?

    var isActive: Bool { status.isActive }
}

/// A completed trip as shown in Activity and driver history.
nonisolated struct TripRecord: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var pickupName: String
    var dropoffName: String
    var date: Date
    var fare: Double
    var currencyCode: String
    var className: String
    var counterpartName: String
    var rating: Int?
    var wasCancelled: Bool

    var formattedFare: String {
        fare.formatted(.currency(code: currencyCode).precision(.fractionLength(2)))
    }
}

/// An incoming job offered to an online driver.
nonisolated struct RideOffer: Identifiable, Hashable, Sendable {
    var id: String
    var riderName: String
    var riderRating: Double
    var pickup: Place
    var dropoff: Place
    var pickupDistanceMeters: Double
    var pickupETAMinutes: Int
    var tripDistanceMeters: Double
    var fare: Double
    var currencyCode: String
    var className: String
    var expiresAt: Date

    var formattedFare: String {
        fare.formatted(.currency(code: currencyCode).precision(.fractionLength(2)))
    }
}

/// Earnings for a single day, used by the driver earnings chart.
nonisolated struct DailyEarnings: Identifiable, Codable, Hashable, Sendable {
    var id: String { dayLabel }
    var dayLabel: String
    var amount: Double
    var trips: Int
}
