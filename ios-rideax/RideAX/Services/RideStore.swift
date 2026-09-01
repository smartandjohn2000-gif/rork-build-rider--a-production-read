import Foundation
import Observation

extension Array where Element == GeoPoint {
    /// Total length of the polyline in metres.
    var lengthMeters: Double {
        guard count > 1 else { return 0 }
        return (1..<count).reduce(into: 0) { total, index in
            total += self[index - 1].distance(to: self[index])
        }
    }

    /// A point at `progress` (0...1) along the polyline, measured by distance.
    func point(atProgress progress: Double) -> GeoPoint? {
        guard let first, count > 1 else { return first }
        let clamped = Swift.min(Swift.max(progress, 0), 1)
        var segments: [Double] = []
        var total: Double = 0
        for index in 1..<count {
            let length = self[index - 1].distance(to: self[index])
            segments.append(length)
            total += length
        }
        guard total > 0 else { return first }

        var travelled = total * clamped
        for index in 0..<segments.count {
            if travelled <= segments[index] {
                let ratio = segments[index] == 0 ? 0 : travelled / segments[index]
                return self[index].interpolated(to: self[index + 1], progress: ratio)
            }
            travelled -= segments[index]
        }
        return last
    }

    /// Direction the polyline is travelling in at `progress`, in degrees.
    /// Sampled slightly ahead and behind so the value stays stable through
    /// dense clusters of route points.
    func heading(atProgress progress: Double) -> Double {
        guard count > 1 else { return 0 }
        let window = 0.012
        let behind = point(atProgress: Swift.max(progress - window, 0))
        let ahead = point(atProgress: Swift.min(progress + window, 1))
        guard let behind, let ahead, behind != ahead else { return 0 }
        return behind.bearing(to: ahead)
    }

    /// The polyline already covered, from the start up to `progress`.
    func travelled(toProgress progress: Double) -> [GeoPoint] {
        guard let current = point(atProgress: progress), count > 1 else { return [] }
        let clamped = Swift.min(Swift.max(progress, 0), 1)
        let total = lengthMeters
        guard total > 0 else { return [] }

        var remaining = total * clamped
        for index in 1..<count {
            let length = self[index - 1].distance(to: self[index])
            if remaining <= length {
                return Array(self[..<index]) + [current]
            }
            remaining -= length
        }
        return self
    }

    /// The remaining polyline from `progress` to the end, for drawing live routes.
    func remainder(fromProgress progress: Double) -> [GeoPoint] {
        guard let current = point(atProgress: progress), count > 1 else { return self }
        let clamped = Swift.min(Swift.max(progress, 0), 1)
        var segments: [Double] = []
        var total: Double = 0
        for index in 1..<count {
            let length = self[index - 1].distance(to: self[index])
            segments.append(length)
            total += length
        }
        guard total > 0 else { return self }

        var travelled = total * clamped
        for index in 0..<segments.count {
            if travelled <= segments[index] {
                return [current] + Array(self[(index + 1)...])
            }
            travelled -= segments[index]
        }
        return [current]
    }
}

/// Drives the rider-side ride lifecycle:
/// Requested → Matching → Accepted → Arriving → Arrived → Started → In progress → Completed → Paid.
@Observable
@MainActor
final class RideStore {
    // Booking inputs
    var pickup: Place = Place(id: "pickup", name: "Current location", subtitle: "Rue Oberkampf", point: LocationService.fallbackPoint, symbol: "location.fill")
    var destination: Place?
    var selectedClassID: String = RideClass.all[0].id

    // Derived route + pricing
    private(set) var routeEstimate: RouteEstimate?
    private(set) var quotes: [String: FareQuote] = [:]
    private(set) var isQuoting: Bool = false

    // Live fare engine
    /// True during a silent background reprice (no spinner, prices just move).
    private(set) var isRepricing: Bool = false
    private(set) var demand: DemandSnapshot = .normal
    private(set) var quoteUpdatedAt: Date?
    /// Signed change of the selected tier's total at the last reprice.
    private(set) var lastFareChange: Double = 0
    /// Metres the rider has moved since the fare was last routed.
    private(set) var pickupDriftMeters: Double = 0

    // Live ride
    private(set) var ride: Ride?
    private(set) var progress: Double = 0
    private(set) var etaMinutes: Int = 0
    private(set) var receipt: Receipt?
    private(set) var errorMessage: String?
    private(set) var nearbyDrivers: [GeoPoint] = []

    // Live driver telemetry
    /// Latest driver location update. The map eases the marker between these.
    private(set) var driverPing: DriverPing?
    /// Polyline of the leg the driver is currently driving (to pickup, then to
    /// dropoff), so the marker can be interpolated along the road instead of
    /// cutting straight lines across corners.
    private(set) var driverRoute: [GeoPoint] = []

    private let routeService = RouteService()
    private let paymentService = PaymentService()
    private let backend = BackendService()
    var pricing = PricingService()

    /// The signed-in rider. Set once the session is restored; without it the
    /// app still works, it just keeps everything on device.
    var riderID: String?
    /// Server-side id of the live ride, and the key Stripe charges against.
    private(set) var remoteRideID: UUID?
    private(set) var isPaying: Bool = false

    private var simulationTask: Task<Void, Never>?
    private var quoteTask: Task<Void, Never>?
    private var liveFareTask: Task<Void, Never>?
    private var quotedFrom: GeoPoint?

    /// A re-route is only worth the request once the rider has actually moved.
    private static let significantDriftMeters: Double = 60
    /// Re-ask the routing provider for traffic-aware timings every N ticks.
    private static let rerouteEveryTicks: Int = 8
    /// Cadence of driver location updates, matching a typical realtime feed.
    private static let telemetryInterval: Double = 1.5

    var selectedClass: RideClass {
        RideClass.all.first { $0.id == selectedClassID } ?? RideClass.all[0]
    }

    var selectedQuote: FareQuote? { quotes[selectedClassID] }

    /// Per-km / per-minute rates for the selected tier, shown in the breakdown.
    var selectedRates: (perKilometre: Double, perMinute: Double) {
        pricing.rates(for: selectedClass)
    }

    /// True while the rider is still standing near the point the fare was
    /// routed from, so the price on screen matches where they actually are.
    var isFareCurrent: Bool { pickupDriftMeters < Self.significantDriftMeters }

    var hasActiveRide: Bool { ride?.isActive ?? false }

    /// The polyline currently drawn on the rider map.
    var activeRoute: [GeoPoint] {
        guard let ride else { return routeEstimate?.polyline ?? [] }
        switch ride.status {
        case .requested, .matching:
            return ride.routeToDropoff
        case .accepted, .arriving, .arrived:
            return ride.routeToPickup.remainder(fromProgress: progress)
        default:
            return ride.routeToDropoff.remainder(fromProgress: progress)
        }
    }

    // MARK: - Booking

    func setPickup(_ place: Place) {
        pickup = place
        refreshQuote()
    }

    /// Force a fresh route + fare, used by the rider's manual refresh control.
    func repriceNow() {
        guard destination != nil else { return }
        refreshQuote(silently: routeEstimate != nil)
    }

    func setDestination(_ place: Place?) {
        destination = place
        lastFareChange = 0
        refreshQuote()
    }

    /// Called on every GPS update. The pickup pin follows the rider immediately,
    /// but a re-route (and therefore a new fare) only fires once they have moved
    /// far enough for the price to plausibly change.
    func syncPickupWithLocation(_ point: GeoPoint, name: String?) {
        guard ride == nil else { return }
        pickup = Place(
            id: "pickup",
            name: name ?? "Current location",
            subtitle: "Your pickup point",
            point: point,
            symbol: "location.fill"
        )
        seedNearbyDrivers(around: point)
        guard destination != nil else { return }

        if let quotedFrom {
            pickupDriftMeters = quotedFrom.distance(to: point)
            guard pickupDriftMeters >= Self.significantDriftMeters else { return }
        }
        refreshQuote(silently: routeEstimate != nil)
    }

    /// Recomputes the route and every tier's fare.
    /// - Parameter silently: keeps the existing prices on screen while the new
    ///   ones are calculated, so a background refresh never flashes a spinner.
    func refreshQuote(silently: Bool = false) {
        quoteTask?.cancel()
        guard let destination else {
            routeEstimate = nil
            quotes = [:]
            quotedFrom = nil
            quoteUpdatedAt = nil
            pickupDriftMeters = 0
            isQuoting = false
            isRepricing = false
            return
        }

        if silently {
            isRepricing = true
        } else {
            isQuoting = true
        }

        let origin = pickup.point
        quoteTask = Task { [weak self] in
            guard let self else { return }
            let estimate = await routeService.route(from: origin, to: destination.point)
            guard !Task.isCancelled else { return }
            self.routeEstimate = estimate
            self.quotedFrom = origin
            self.pickupDriftMeters = 0
            self.applyPricing(for: estimate)
            self.isQuoting = false
            self.isRepricing = false
        }
    }

    // MARK: - Live fare engine

    /// Starts the loop that keeps the fare honest while the rider is deciding:
    /// nearby cars drift, demand is re-evaluated, and the route is re-timed for
    /// traffic periodically. Safe to call repeatedly.
    func startLiveFareUpdates() {
        guard liveFareTask == nil else { return }
        liveFareTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(6))
                guard let self, !Task.isCancelled else { return }
                guard self.ride == nil else { continue }
                tick += 1
                self.driftNearbyDrivers()
                guard let estimate = self.routeEstimate, self.destination != nil else { continue }

                if tick % Self.rerouteEveryTicks == 0 {
                    self.refreshQuote(silently: true)
                } else {
                    self.applyPricing(for: estimate)
                }
            }
        }
    }

    func stopLiveFareUpdates() {
        liveFareTask?.cancel()
        liveFareTask = nil
    }

    /// Re-evaluates demand for the current conditions and reprices every tier,
    /// remembering how much the selected fare moved so the UI can flag it.
    private func applyPricing(for estimate: RouteEstimate) {
        let previousTotal = quotes[selectedClassID]?.total
        demand = pricing.demand(
            freeDriversNearby: nearbyDrivers.count,
            congestionRatio: Self.congestionRatio(for: estimate)
        )
        quotes = pricing.quotes(
            distanceMeters: estimate.distanceMeters,
            durationSeconds: estimate.durationSeconds,
            surge: demand.multiplier
        )
        quoteUpdatedAt = Date()

        if let previousTotal, let newTotal = quotes[selectedClassID]?.total {
            let delta = ((newTotal - previousTotal) * 100).rounded() / 100
            lastFareChange = abs(delta) >= 0.01 ? delta : 0
        } else {
            lastFareChange = 0
        }
    }

    /// Actual travel time vs. a free-flowing 50 km/h city road.
    private static func congestionRatio(for estimate: RouteEstimate) -> Double {
        guard estimate.distanceMeters > 0 else { return 1 }
        let freeFlowSeconds = estimate.distanceMeters / 13.9
        guard freeFlowSeconds > 0 else { return 1 }
        return min(max(estimate.durationSeconds / freeFlowSeconds, 0.5), 3)
    }

    private func seedNearbyDrivers(around point: GeoPoint) {
        guard nearbyDrivers.isEmpty else { return }
        let offsets: [(Double, Double)] = [
            (0.006, 0.004), (-0.005, 0.008), (0.009, -0.006),
            (-0.008, -0.005), (0.003, 0.011), (-0.011, 0.002)
        ]
        nearbyDrivers = offsets.map {
            GeoPoint(latitude: point.latitude + $0.0, longitude: point.longitude + $0.1)
        }
    }

    /// Nudges the idle cars around the map so supply — and therefore the live
    /// fare — is never frozen while the rider is looking at it.
    private func driftNearbyDrivers() {
        guard !nearbyDrivers.isEmpty else { return }
        nearbyDrivers = nearbyDrivers.map { point in
            GeoPoint(
                latitude: point.latitude + Double.random(in: -0.0016...0.0016),
                longitude: point.longitude + Double.random(in: -0.0016...0.0016)
            )
        }
    }

    // MARK: - Ride lifecycle

    func requestRide(paymentMethod: PaymentMethod?) {
        guard let destination, let estimate = routeEstimate, let quote = selectedQuote else {
            errorMessage = "Choose a destination to request a ride."
            return
        }
        guard paymentMethod != nil else {
            errorMessage = PaymentError.noPaymentMethod.errorDescription
            return
        }

        errorMessage = nil
        receipt = nil
        progress = 0

        let newRide = Ride(
            id: "ride_" + UUID().uuidString.prefix(8),
            status: .requested,
            pickup: pickup,
            dropoff: destination,
            rideClass: selectedClass,
            quote: quote,
            driver: nil,
            requestedAt: Date(),
            completedAt: nil,
            driverPoint: nil,
            routeToPickup: [],
            routeToDropoff: estimate.polyline,
            riderRatingForDriver: nil,
            paymentMethodID: paymentMethod?.id,
            cancellationReason: nil
        )
        ride = newRide
        etaMinutes = selectedClass.etaMinutes
        remoteRideID = nil
        Haptics.tap()
        persistNewRide(newRide, quote: quote)
        startSimulation()
    }

    // MARK: - Backend sync

    /// Writes the ride to Postgres. The row is what the payment functions read
    /// to decide the amount, so the fare can never be set by the client at
    /// checkout time.
    private func persistNewRide(_ ride: Ride, quote: FareQuote) {
        guard Backend.isConfigured, let riderID else { return }
        Task { [backend] in
            do {
                let id = try await backend.createRide(riderID: riderID, ride: ride, quote: quote)
                self.remoteRideID = id
            } catch {
                print("RideAX: could not persist ride — \(error.localizedDescription)")
            }
        }
    }

    /// Fire-and-forget status mirroring. A failed sync never interrupts the
    /// trip on screen; the local state machine stays authoritative for the UI.
    private func publish(_ update: RideUpdate) {
        guard Backend.isConfigured, let remoteRideID else { return }
        Task { [backend] in
            do {
                try await backend.updateRide(id: remoteRideID, update)
            } catch {
                print("RideAX: ride sync failed — \(error.localizedDescription)")
            }
        }
    }

    func cancelRide(reason: String = "Changed my mind") {
        guard var current = ride, current.status.allowsCancellation else { return }
        simulationTask?.cancel()
        current.status = .cancelled
        current.cancellationReason = reason
        ride = current
        driverPing = nil
        driverRoute = []
        publish(RideUpdate(status: RideStatus.cancelled.rawValue, cancelledAt: Date()))
        Haptics.warning()
    }

    func dismissCompletedRide() {
        simulationTask?.cancel()
        ride = nil
        remoteRideID = nil
        receipt = nil
        progress = 0
        driverPing = nil
        driverRoute = []
        destination = nil
        routeEstimate = nil
        quotes = [:]
        quotedFrom = nil
        quoteUpdatedAt = nil
        lastFareChange = 0
        pickupDriftMeters = 0
    }

    func rateDriver(stars: Int, tripStore: TripStore) {
        guard var current = ride else { return }
        current.riderRatingForDriver = stars
        ride = current
        tripStore.rate(tripID: current.id, stars: stars)
        Haptics.success()
    }

    private func startSimulation() {
        simulationTask?.cancel()
        simulationTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await self.runLifecycle()
            } catch is CancellationError {
                return
            } catch {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    /// Advances the ride through every state, mirroring the events a realtime
    /// backend would push (driver assigned, location updates, trip started…).
    private func runLifecycle() async throws {
        try await Task.sleep(for: .milliseconds(800))
        try updateStatus(.matching)

        try await Task.sleep(for: .seconds(2))
        guard var current = ride else { return }

        let driver = DriverProfile.samples.randomElement() ?? DriverProfile.samples[0]
        let driverStart = GeoPoint(
            latitude: current.pickup.point.latitude - 0.012,
            longitude: current.pickup.point.longitude + 0.014
        )
        let pickupRoute = await routeService.route(from: driverStart, to: current.pickup.point)

        guard var refreshed = ride, refreshed.status == .matching else { return }
        refreshed.driver = driver
        refreshed.routeToPickup = pickupRoute.polyline
        refreshed.driverPoint = driverStart
        refreshed.status = .accepted
        ride = refreshed
        progress = 0
        etaMinutes = pickupRoute.minutes
        publish(
            RideUpdate(
                status: RideStatus.accepted.rawValue,
                driverName: driver.name,
                driverPlate: driver.plate,
                driverVehicle: driver.vehicle,
                acceptedAt: Date()
            )
        )
        beginLeg(route: pickupRoute.polyline)
        Haptics.success()
        current = refreshed

        try await Task.sleep(for: .milliseconds(900))
        try updateStatus(.arriving)
        try await animate(route: refreshed.routeToPickup, duration: 9, totalMinutes: pickupRoute.minutes)

        try updateStatus(.arrived)
        etaMinutes = 0
        Haptics.tap()
        try await Task.sleep(for: .seconds(3))

        try updateStatus(.started)
        let tripMinutes = routeEstimate.map(\.minutes) ?? 12
        etaMinutes = tripMinutes
        beginLeg(route: refreshed.routeToDropoff)
        try await Task.sleep(for: .milliseconds(700))

        try updateStatus(.inProgress)
        try await animate(route: refreshed.routeToDropoff, duration: 12, totalMinutes: tripMinutes)

        try updateStatus(.completed)
        etaMinutes = 0
        Haptics.success()
    }

    private func updateStatus(_ status: RideStatus) throws {
        try Task.checkCancellation()
        guard var current = ride, current.status != .cancelled else { throw CancellationError() }
        current.status = status
        if status == .completed { current.completedAt = Date() }
        ride = current

        switch status {
        case .started:
            publish(RideUpdate(status: status.rawValue, startedAt: Date()))
        case .completed:
            publish(RideUpdate(status: status.rawValue, completedAt: Date()))
        case .paid:
            break // Only Stripe's webhook may mark a ride paid server-side.
        default:
            publish(RideUpdate(status: status.rawValue))
        }
    }

    /// Publishes the driver's starting position for a new leg and resets the
    /// interpolator's reference, so the marker snaps to the new road instead of
    /// flying across the map when the route switches from pickup to dropoff.
    private func beginLeg(route: [GeoPoint]) {
        driverRoute = route
        progress = 0
        guard let start = route.first else {
            driverPing = nil
            return
        }
        driverPing = DriverPing(
            sequence: 0,
            point: start,
            progress: 0,
            heading: route.heading(atProgress: 0),
            speedMetersPerSecond: 0,
            timestamp: Date(),
            expectedInterval: Self.telemetryInterval
        )
    }

    /// Streams the driver along a leg the way a realtime backend would: one
    /// location ping roughly every `telemetryInterval` seconds, with the pace
    /// varying slightly for traffic. Nothing here runs at frame rate — the map
    /// smooths between pings, exactly as it would with a real driver feed.
    private func animate(route: [GeoPoint], duration: Double, totalMinutes: Int) async throws {
        guard route.count > 1 else { return }
        driverRoute = route
        let totalMeters = route.lengthMeters
        let pings = max(4, Int((duration / Self.telemetryInterval).rounded()))
        let interval = duration / Double(pings)
        var previous: Double = 0
        var sequence = driverPing?.sequence ?? 0

        for step in 1...pings {
            try Task.checkCancellation()
            guard ride?.status != .cancelled else { throw CancellationError() }
            try await Task.sleep(for: .seconds(interval))
            try Task.checkCancellation()
            guard ride?.status != .cancelled else { throw CancellationError() }

            let even = Double(step) / Double(pings)
            // Traffic jitter: the car covers a little more or less ground
            // between pings, but always moves forward and always lands exactly
            // on the end of the leg.
            let jitter = step == pings ? 0 : Double.random(in: -0.3...0.3) / Double(pings)
            let value = min(max(even + jitter, previous + 0.004), 1)
            sequence += 1

            guard let point = route.point(atProgress: value) else { continue }
            progress = value
            if var current = ride {
                current.driverPoint = point
                ride = current
            }
            etaMinutes = max(0, Int((Double(totalMinutes) * (1 - value)).rounded(.up)))
            driverPing = DriverPing(
                sequence: sequence,
                point: point,
                progress: value,
                heading: route.heading(atProgress: value),
                speedMetersPerSecond: interval > 0 ? totalMeters * (value - previous) / interval : 0,
                timestamp: Date(),
                expectedInterval: interval
            )
            previous = value
        }
    }

    // MARK: - Payment

    /// Charges the completed trip through Stripe.
    ///
    /// The amount is never sent from here: the backend prices the PaymentIntent
    /// from the ride row, Stripe's sheet collects the card, and Stripe's webhook
    /// is what marks the ride paid. We then read back the settled payment row
    /// for the receipt, so what the rider sees matches what was actually taken.
    func payForRide(method: PaymentMethod?, tripStore: TripStore) async {
        guard let current = ride, current.status == .completed, !isPaying else { return }
        isPaying = true
        defer { isPaying = false }

        guard Backend.isConfigured, let remoteRideID else {
            await settleWithoutStripe(current, method: method, tripStore: tripStore)
            return
        }

        do {
            switch try await StripeService.shared.payForRide(remoteRideID: remoteRideID) {
            case .cancelled:
                return
            case .failed(let message):
                errorMessage = message
                return
            case .paid:
                let settled = await backend.awaitSettledPayment(rideID: remoteRideID)
                finish(
                    current,
                    tripStore: tripStore,
                    receipt: Receipt(
                        id: settled?.id.uuidString ?? "rcpt_" + UUID().uuidString.prefix(8),
                        rideID: current.id,
                        total: settled?.amount ?? current.quote.total,
                        currencyCode: (settled?.currency ?? current.quote.currencyCode).uppercased(),
                        paymentMethodLabel: settled?.paymentMethodLabel ?? method?.displayName ?? "Card",
                        paidAt: settled?.createdAt ?? Date(),
                        breakdown: current.quote,
                        receiptURL: settled?.receiptUrl.flatMap { URL(string: $0) },
                        isLiveCharge: true
                    )
                )
            }
        } catch StripeError.alreadyPaid {
            // The webhook beat us to it — treat the trip as settled.
            let settled = try? await backend.fetchPayment(rideID: remoteRideID)
            finish(
                current,
                tripStore: tripStore,
                receipt: Receipt(
                    id: settled?.id.uuidString ?? current.id,
                    rideID: current.id,
                    total: settled?.amount ?? current.quote.total,
                    currencyCode: (settled?.currency ?? current.quote.currencyCode).uppercased(),
                    paymentMethodLabel: settled?.paymentMethodLabel ?? "Card",
                    paidAt: settled?.createdAt ?? Date(),
                    breakdown: current.quote,
                    receiptURL: settled?.receiptUrl.flatMap { URL(string: $0) },
                    isLiveCharge: true
                )
            )
        } catch StripeError.notConfigured {
            await settleWithoutStripe(current, method: method, tripStore: tripStore)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "Payment failed. Please try again."
            Haptics.warning()
        }
    }

    /// Closes out the trip when card payments aren't switched on for this
    /// account. The receipt is flagged so the rider is never told a charge
    /// happened when it didn't.
    private func settleWithoutStripe(_ ride: Ride, method: PaymentMethod?, tripStore: TripStore) async {
        do {
            let receipt = try await paymentService.settleLocally(ride: ride, method: method)
            finish(ride, tripStore: tripStore, receipt: receipt)
        } catch {
            errorMessage = (error as? PaymentError)?.errorDescription ?? "Payment failed. Please try again."
            Haptics.warning()
        }
    }

    private func finish(_ ride: Ride, tripStore: TripStore, receipt: Receipt) {
        self.receipt = receipt
        try? updateStatus(.paid)
        tripStore.recordRiderTrip(
            TripRecord(
                id: ride.id,
                pickupName: ride.pickup.name,
                dropoffName: ride.dropoff.name,
                date: Date(),
                fare: receipt.total,
                currencyCode: receipt.currencyCode,
                className: ride.rideClass.name,
                counterpartName: ride.driver?.name ?? "RideAX driver",
                rating: nil,
                wasCancelled: false
            )
        )
        Haptics.success()
    }

    func clearError() {
        errorMessage = nil
    }
}
