import Foundation
import Supabase

/// Thin, typed wrapper over the Postgres tables.
///
/// Nothing here is optimistic: every call is `async throws` and the callers
/// decide whether a failure should surface to the rider or just be logged. The
/// app stays fully usable offline because the stores keep their local state and
/// treat the database as the durable copy, not as the source of truth for UI.
nonisolated struct BackendService: Sendable {
    private var client: SupabaseClient { Backend.client }

    // MARK: - Profiles

    /// Writes the signed-in person into `profiles`, which every other table
    /// references. Called on each launch so name/email changes stay in sync.
    @discardableResult
    func syncProfile(
        id: String,
        email: String?,
        fullName: String?,
        phone: String?,
        avatarURL: String?,
        role: AppRole
    ) async throws -> ProfileRow? {
        try await client
            .from("profiles")
            .upsert(
                ProfileUpsert(
                    id: id,
                    email: email,
                    fullName: fullName,
                    phone: phone,
                    avatarUrl: avatarURL,
                    role: role.rawValue,
                    updatedAt: Date()
                )
            )
            .execute()

        return try await fetchProfile(id: id)
    }

    func fetchProfile(id: String) async throws -> ProfileRow? {
        let rows: [ProfileRow] = try await client
            .from("profiles")
            .select("id,email,full_name,phone,avatar_url,role,rating,created_at")
            .eq("id", value: id)
            .limit(1)
            .execute()
            .value
        return rows.first
    }

    func updateRole(id: String, role: AppRole) async throws {
        nonisolated struct RoleUpdate: Encodable, Sendable {
            let role: String
            let updatedAt: Date

            enum CodingKeys: String, CodingKey {
                case role
                case updatedAt = "updated_at"
            }
        }

        try await client
            .from("profiles")
            .update(RoleUpdate(role: role.rawValue, updatedAt: Date()))
            .eq("id", value: id)
            .execute()
    }

    /// Creates the driver row on first use and leaves an existing one alone —
    /// re-running this must never reset an already approved driver.
    func ensureDriverProfile(id: String) async throws {
        try await client
            .from("driver_profiles")
            .upsert(
                DriverProfileUpsert(
                    id: id,
                    approvalStatus: DriverApprovalStatus.notSubmitted.rawValue,
                    isOnline: false,
                    vehicleMake: nil,
                    vehicleModel: nil,
                    vehicleYear: nil,
                    vehicleColor: nil,
                    vehiclePlate: nil,
                    updatedAt: Date()
                ),
                onConflict: "id",
                ignoreDuplicates: true
            )
            .execute()
    }

    /// Pushes the driver's live state: approval, availability and vehicle.
    func syncDriverProfile(
        id: String,
        approval: DriverApprovalStatus,
        isOnline: Bool,
        vehicle: Vehicle?
    ) async throws {
        try await client
            .from("driver_profiles")
            .upsert(
                DriverProfileUpsert(
                    id: id,
                    approvalStatus: approval.rawValue,
                    isOnline: isOnline,
                    vehicleMake: vehicle?.make,
                    vehicleModel: vehicle?.model,
                    vehicleYear: vehicle?.year,
                    vehicleColor: vehicle?.color,
                    vehiclePlate: vehicle?.plate,
                    updatedAt: Date()
                )
            )
            .execute()
    }

    // MARK: - Rides

    /// Persists a newly requested ride and returns its server id, which is what
    /// the Stripe functions later charge against.
    func createRide(riderID: String, ride: Ride, quote: FareQuote) async throws -> UUID {
        let row: RideRow = try await client
            .from("rides")
            .insert(
                RideInsert(
                    riderId: riderID,
                    status: ride.status.rawValue,
                    rideClass: ride.rideClass.id,
                    pickupName: ride.pickup.name,
                    pickupSubtitle: ride.pickup.subtitle,
                    pickupLat: ride.pickup.point.latitude,
                    pickupLng: ride.pickup.point.longitude,
                    dropoffName: ride.dropoff.name,
                    dropoffSubtitle: ride.dropoff.subtitle,
                    dropoffLat: ride.dropoff.point.latitude,
                    dropoffLng: ride.dropoff.point.longitude,
                    distanceKm: (quote.kilometres * 100).rounded() / 100,
                    durationMin: quote.minutes,
                    currency: quote.currencyCode,
                    fareTotal: quote.total,
                    surgeMultiplier: quote.surgeMultiplier,
                    fareBreakdown: quote
                )
            )
            .select()
            .single()
            .execute()
            .value
        return row.id
    }

    func updateRide(id: UUID, _ update: RideUpdate) async throws {
        try await client
            .from("rides")
            .update(update)
            .eq("id", value: id.uuidString)
            .execute()
    }

    func fetchRides(riderID: String, limit: Int = 40) async throws -> [RideRow] {
        try await client
            .from("rides")
            .select("id,rider_id,driver_id,status,ride_class,pickup_name,dropoff_name,distance_km,duration_min,currency,fare_total,surge_multiplier,driver_name,requested_at,completed_at,cancelled_at")
            .eq("rider_id", value: riderID)
            .order("requested_at", ascending: false)
            .limit(limit)
            .execute()
            .value
    }

    // MARK: - Payments

    func fetchPayment(rideID: UUID) async throws -> PaymentRow? {
        let rows: [PaymentRow] = try await client
            .from("payments")
            .select("id,ride_id,amount_cents,currency,status,payment_method_label,receipt_url,failure_message,created_at")
            .eq("ride_id", value: rideID.uuidString)
            .limit(1)
            .execute()
            .value
        return rows.first
    }

    /// Stripe confirms asynchronously through the webhook, so after the sheet
    /// closes we give the backend a moment to catch up before reading the
    /// authoritative payment record for the receipt.
    func awaitSettledPayment(rideID: UUID, attempts: Int = 5) async -> PaymentRow? {
        for attempt in 0..<attempts {
            if attempt > 0 { try? await Task.sleep(for: .milliseconds(700)) }
            guard let payment = try? await fetchPayment(rideID: rideID) else { continue }
            if payment.status == "succeeded" || payment.status == "failed" { return payment }
        }
        return try? await fetchPayment(rideID: rideID)
    }
}
