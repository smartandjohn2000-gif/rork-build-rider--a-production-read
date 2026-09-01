import Foundation

// Row shapes for the Postgres tables. Kept deliberately separate from the app's
// domain models so a schema change never ripples straight into the UI.

nonisolated struct ProfileRow: Codable, Sendable, Identifiable {
    let id: String
    var email: String?
    var fullName: String?
    var phone: String?
    var avatarUrl: String?
    var role: String
    var rating: Double
    var createdAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case fullName = "full_name"
        case phone
        case avatarUrl = "avatar_url"
        case role
        case rating
        case createdAt = "created_at"
    }
}

nonisolated struct ProfileUpsert: Encodable, Sendable {
    let id: String
    let email: String?
    let fullName: String?
    let phone: String?
    let avatarUrl: String?
    let role: String
    let updatedAt: Date

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case fullName = "full_name"
        case phone
        case avatarUrl = "avatar_url"
        case role
        case updatedAt = "updated_at"
    }
}

nonisolated struct DriverProfileUpsert: Encodable, Sendable {
    let id: String
    let approvalStatus: String
    let isOnline: Bool
    let vehicleMake: String?
    let vehicleModel: String?
    let vehicleYear: Int?
    let vehicleColor: String?
    let vehiclePlate: String?
    let updatedAt: Date

    enum CodingKeys: String, CodingKey {
        case id
        case approvalStatus = "approval_status"
        case isOnline = "is_online"
        case vehicleMake = "vehicle_make"
        case vehicleModel = "vehicle_model"
        case vehicleYear = "vehicle_year"
        case vehicleColor = "vehicle_color"
        case vehiclePlate = "vehicle_plate"
        case updatedAt = "updated_at"
    }
}

nonisolated struct RideRow: Codable, Sendable, Identifiable {
    let id: UUID
    var riderId: String
    var driverId: String?
    var status: String
    var rideClass: String
    var pickupName: String
    var dropoffName: String
    var distanceKm: Double
    var durationMin: Int
    var currency: String
    var fareTotal: Double
    var surgeMultiplier: Double
    var driverName: String?
    var requestedAt: Date
    var completedAt: Date?
    var cancelledAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case riderId = "rider_id"
        case driverId = "driver_id"
        case status
        case rideClass = "ride_class"
        case pickupName = "pickup_name"
        case dropoffName = "dropoff_name"
        case distanceKm = "distance_km"
        case durationMin = "duration_min"
        case currency
        case fareTotal = "fare_total"
        case surgeMultiplier = "surge_multiplier"
        case driverName = "driver_name"
        case requestedAt = "requested_at"
        case completedAt = "completed_at"
        case cancelledAt = "cancelled_at"
    }
}

nonisolated struct RideInsert: Encodable, Sendable {
    let riderId: String
    let status: String
    let rideClass: String
    let pickupName: String
    let pickupSubtitle: String?
    let pickupLat: Double
    let pickupLng: Double
    let dropoffName: String
    let dropoffSubtitle: String?
    let dropoffLat: Double
    let dropoffLng: Double
    let distanceKm: Double
    let durationMin: Int
    let currency: String
    let fareTotal: Double
    let surgeMultiplier: Double
    let fareBreakdown: FareQuote

    enum CodingKeys: String, CodingKey {
        case riderId = "rider_id"
        case status
        case rideClass = "ride_class"
        case pickupName = "pickup_name"
        case pickupSubtitle = "pickup_subtitle"
        case pickupLat = "pickup_lat"
        case pickupLng = "pickup_lng"
        case dropoffName = "dropoff_name"
        case dropoffSubtitle = "dropoff_subtitle"
        case dropoffLat = "dropoff_lat"
        case dropoffLng = "dropoff_lng"
        case distanceKm = "distance_km"
        case durationMin = "duration_min"
        case currency
        case fareTotal = "fare_total"
        case surgeMultiplier = "surge_multiplier"
        case fareBreakdown = "fare_breakdown"
    }
}

/// Optional fields are omitted from the encoded payload, so one type serves
/// every partial update along the ride's lifecycle.
nonisolated struct RideUpdate: Encodable, Sendable {
    var status: String?
    var driverName: String?
    var driverPlate: String?
    var driverVehicle: String?
    var fareTotal: Double?
    var acceptedAt: Date?
    var startedAt: Date?
    var completedAt: Date?
    var cancelledAt: Date?
    var updatedAt: Date = Date()

    enum CodingKeys: String, CodingKey {
        case status
        case driverName = "driver_name"
        case driverPlate = "driver_plate"
        case driverVehicle = "driver_vehicle"
        case fareTotal = "fare_total"
        case acceptedAt = "accepted_at"
        case startedAt = "started_at"
        case completedAt = "completed_at"
        case cancelledAt = "cancelled_at"
        case updatedAt = "updated_at"
    }
}

nonisolated struct PaymentRow: Codable, Sendable, Identifiable {
    let id: UUID
    var rideId: UUID
    var amountCents: Int
    var currency: String
    var status: String
    var paymentMethodLabel: String?
    var receiptUrl: String?
    var failureMessage: String?
    var createdAt: Date

    var amount: Double { Double(amountCents) / 100 }

    enum CodingKeys: String, CodingKey {
        case id
        case rideId = "ride_id"
        case amountCents = "amount_cents"
        case currency
        case status
        case paymentMethodLabel = "payment_method_label"
        case receiptUrl = "receipt_url"
        case failureMessage = "failure_message"
        case createdAt = "created_at"
    }
}
