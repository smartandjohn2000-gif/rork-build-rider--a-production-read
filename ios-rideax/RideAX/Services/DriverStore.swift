import Foundation
import Observation

/// The stage of the job a driver is currently working.
nonisolated enum DriverJobStage: String, Sendable {
    case idle
    case headingToPickup
    case waitingForRider
    case onTrip
    case completed

    var title: String {
        switch self {
        case .idle: "No active job"
        case .headingToPickup: "Head to pickup"
        case .waitingForRider: "Waiting for rider"
        case .onTrip: "Trip in progress"
        case .completed: "Trip complete"
        }
    }

    var actionTitle: String {
        switch self {
        case .idle: "Go online"
        case .headingToPickup: "I've arrived"
        case .waitingForRider: "Start trip"
        case .onTrip: "Complete trip"
        case .completed: "Finish"
        }
    }
}

nonisolated struct DriverSnapshot: Codable, Sendable {
    var approvalStatus: DriverApprovalStatus
    var documents: [DriverDocument]
    var vehicle: Vehicle
    var acceptedCount: Int
    var offeredCount: Int
    var onlineSeconds: Double
}

/// Owns the driver shell: availability, incoming offers, the active job and
/// the stats that feed the earnings screen.
@Observable
@MainActor
final class DriverStore {
    private(set) var isOnline: Bool = false
    private(set) var approvalStatus: DriverApprovalStatus = .approved
    private(set) var documents: [DriverDocument] = DriverDocument.required
    var vehicle: Vehicle = Vehicle(make: "Toyota", model: "Camry", year: 2022, color: "Grey", plate: "742-KLD")

    private(set) var currentOffer: RideOffer?
    private(set) var secondsRemaining: Int = 0
    private(set) var jobStage: DriverJobStage = .idle
    private(set) var activeJob: RideOffer?
    private(set) var driverPoint: GeoPoint = GeoPoint(latitude: 48.8720, longitude: 2.3625)
    private(set) var routeToRider: [GeoPoint] = []
    private(set) var jobProgress: Double = 0

    private(set) var acceptedCount: Int = 18
    private(set) var offeredCount: Int = 20
    private(set) var onlineSeconds: Double = 4 * 3600 + 12 * 60
    private(set) var lastCompletedFare: Double?

    private let routeService = RouteService()
    private var offerTask: Task<Void, Never>?
    private var onlineTimerTask: Task<Void, Never>?
    private var jobTask: Task<Void, Never>?

    var acceptanceRate: Int {
        guard offeredCount > 0 else { return 100 }
        return Int((Double(acceptedCount) / Double(offeredCount) * 100).rounded())
    }

    var canGoOnline: Bool { approvalStatus == .approved }

    var onlineDurationText: String {
        let hours = Int(onlineSeconds) / 3600
        let minutes = (Int(onlineSeconds) % 3600) / 60
        return "\(hours)h \(minutes)m today"
    }

    init() {
        if let snapshot = Persistence.load(DriverSnapshot.self, key: Persistence.Key.driverState) {
            approvalStatus = snapshot.approvalStatus
            documents = snapshot.documents
            vehicle = snapshot.vehicle
            acceptedCount = snapshot.acceptedCount
            offeredCount = snapshot.offeredCount
            onlineSeconds = snapshot.onlineSeconds
        }
    }

    // MARK: - Availability

    func setOnline(_ online: Bool) {
        guard canGoOnline || !online else { return }
        isOnline = online
        Haptics.tap()
        if online {
            startOnlineTimer()
            scheduleNextOffer(after: .seconds(2))
        } else {
            offerTask?.cancel()
            onlineTimerTask?.cancel()
            currentOffer = nil
        }
        persist()
    }

    private func startOnlineTimer() {
        onlineTimerTask?.cancel()
        onlineTimerTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                guard let self, self.isOnline else { return }
                self.onlineSeconds += 60
            }
        }
    }

    // MARK: - Offers

    private func scheduleNextOffer(after delay: Duration) {
        offerTask?.cancel()
        offerTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, self.isOnline, self.jobStage == .idle, !Task.isCancelled else { return }
            self.presentOffer()
        }
    }

    private func presentOffer() {
        let offer = RideOffer.random(around: driverPoint)
        currentOffer = offer
        offeredCount += 1
        secondsRemaining = 24
        Haptics.warning()

        offerTask = Task { [weak self] in
            while true {
                try? await Task.sleep(for: .seconds(1))
                guard let self, !Task.isCancelled else { return }
                guard self.currentOffer?.id == offer.id else { return }
                self.secondsRemaining -= 1
                if self.secondsRemaining <= 0 {
                    self.currentOffer = nil
                    self.scheduleNextOffer(after: .seconds(6))
                    return
                }
            }
        }
    }

    func declineOffer() {
        offerTask?.cancel()
        currentOffer = nil
        Haptics.tap()
        persist()
        scheduleNextOffer(after: .seconds(5))
    }

    func acceptOffer() {
        guard let offer = currentOffer else { return }
        offerTask?.cancel()
        currentOffer = nil
        activeJob = offer
        acceptedCount += 1
        jobStage = .headingToPickup
        jobProgress = 0
        Haptics.success()
        persist()

        jobTask?.cancel()
        let origin = driverPoint
        jobTask = Task { [weak self] in
            guard let self else { return }
            let estimate = await self.routeService.route(from: origin, to: offer.pickup.point)
            guard !Task.isCancelled, self.activeJob?.id == offer.id else { return }
            self.routeToRider = estimate.polyline
            await self.driveTowardsPickup()
        }
    }

    private func driveTowardsPickup() async {
        let steps = 40
        for step in 0...steps {
            guard !Task.isCancelled, jobStage == .headingToPickup else { return }
            let value = Double(step) / Double(steps)
            jobProgress = value
            if let point = routeToRider.point(atProgress: value) {
                driverPoint = point
            }
            try? await Task.sleep(for: .milliseconds(220))
        }
    }

    /// Advances the driver job: heading → waiting → on trip → completed.
    func advanceJob(tripStore: TripStore) {
        guard let job = activeJob else { return }
        switch jobStage {
        case .idle:
            return
        case .headingToPickup:
            jobStage = .waitingForRider
            jobProgress = 1
            Haptics.tap()
        case .waitingForRider:
            jobStage = .onTrip
            jobProgress = 0
            Haptics.tap()
            jobTask?.cancel()
            jobTask = Task { [weak self] in
                guard let self else { return }
                let estimate = await self.routeService.route(from: job.pickup.point, to: job.dropoff.point)
                guard !Task.isCancelled else { return }
                self.routeToRider = estimate.polyline
                let steps = 40
                for step in 0...steps {
                    guard !Task.isCancelled, self.jobStage == .onTrip else { return }
                    let value = Double(step) / Double(steps)
                    self.jobProgress = value
                    if let point = self.routeToRider.point(atProgress: value) {
                        self.driverPoint = point
                    }
                    try? await Task.sleep(for: .milliseconds(220))
                }
            }
        case .onTrip:
            jobTask?.cancel()
            jobStage = .completed
            jobProgress = 1
            lastCompletedFare = job.fare
            driverPoint = job.dropoff.point
            tripStore.recordDriverTrip(
                TripRecord(
                    id: job.id,
                    pickupName: job.pickup.name,
                    dropoffName: job.dropoff.name,
                    date: Date(),
                    fare: job.fare,
                    currencyCode: job.currencyCode,
                    className: job.className,
                    counterpartName: job.riderName,
                    rating: 5,
                    wasCancelled: false
                )
            )
            Haptics.success()
            persist()
        case .completed:
            finishJob()
        }
    }

    func finishJob() {
        jobTask?.cancel()
        activeJob = nil
        jobStage = .idle
        jobProgress = 0
        routeToRider = []
        lastCompletedFare = nil
        if isOnline { scheduleNextOffer(after: .seconds(4)) }
    }

    func cancelJob() {
        jobTask?.cancel()
        activeJob = nil
        jobStage = .idle
        jobProgress = 0
        routeToRider = []
        Haptics.warning()
        if isOnline { scheduleNextOffer(after: .seconds(8)) }
    }

    // MARK: - Onboarding & documents

    func uploadDocument(id: String) {
        guard let index = documents.firstIndex(where: { $0.id == id }) else { return }
        documents[index].isUploaded = true
        Haptics.tap()
        persist()
    }

    func submitForApproval() {
        guard documents.allSatisfy(\.isUploaded) else { return }
        approvalStatus = .pending
        Haptics.success()
        persist()

        // Admin review is asynchronous; the backend pushes the result.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard let self, self.approvalStatus == .pending else { return }
            self.approvalStatus = .approved
            self.persist()
        }
    }

    func resetApproval() {
        approvalStatus = .notSubmitted
        documents = DriverDocument.required.map { document in
            var copy = document
            copy.isUploaded = false
            return copy
        }
        persist()
    }

    private func persist() {
        Persistence.save(
            DriverSnapshot(
                approvalStatus: approvalStatus,
                documents: documents,
                vehicle: vehicle,
                acceptedCount: acceptedCount,
                offeredCount: offeredCount,
                onlineSeconds: onlineSeconds
            ),
            key: Persistence.Key.driverState
        )
    }
}

extension RideOffer {
    /// Builds a plausible nearby job. In production these arrive over the
    /// realtime channel from the dispatch service.
    static func random(around point: GeoPoint) -> RideOffer {
        let pickups: [(String, String, Double, Double)] = [
            ("Rue Oberkampf", "11th arrondissement", 48.8656, 2.3712),
            ("Place de la République", "3rd arrondissement", 48.8674, 2.3636),
            ("Canal Saint-Martin", "10th arrondissement", 48.8710, 2.3660),
            ("Bastille", "12th arrondissement", 48.8532, 2.3692)
        ]
        let dropoffs: [(String, String, Double, Double)] = [
            ("Gare du Nord", "18 Rue de Dunkerque", 48.8809, 2.3553),
            ("Montmartre", "Sacré-Cœur", 48.8867, 2.3431),
            ("Louvre Museum", "1st arrondissement", 48.8606, 2.3376),
            ("La Défense", "Puteaux", 48.8918, 2.2380)
        ]
        let riders = [("Sofia", 4.8), ("Chloé", 4.9), ("Yanis", 4.7), ("Nora", 5.0)]

        let pickupData = pickups.randomElement() ?? pickups[0]
        let dropoffData = dropoffs.randomElement() ?? dropoffs[0]
        let rider = riders.randomElement() ?? riders[0]

        let pickup = Place(
            id: pickupData.0,
            name: pickupData.0,
            subtitle: pickupData.1,
            point: GeoPoint(latitude: pickupData.2, longitude: pickupData.3),
            symbol: "circle.fill"
        )
        let dropoff = Place(
            id: dropoffData.0,
            name: dropoffData.0,
            subtitle: dropoffData.1,
            point: GeoPoint(latitude: dropoffData.2, longitude: dropoffData.3),
            symbol: "square.fill"
        )

        let pickupDistance = point.distance(to: pickup.point)
        let tripDistance = pickup.point.distance(to: dropoff.point) * 1.3
        let pricing = PricingService()
        let quote = pricing.quote(
            for: RideClass.all[0],
            distanceMeters: tripDistance,
            durationSeconds: tripDistance / 7.5
        )

        return RideOffer(
            id: "offer_" + UUID().uuidString.prefix(6),
            riderName: rider.0,
            riderRating: rider.1,
            pickup: pickup,
            dropoff: dropoff,
            pickupDistanceMeters: max(pickupDistance, 400),
            pickupETAMinutes: max(2, Int((pickupDistance / 250).rounded())),
            tripDistanceMeters: tripDistance,
            fare: quote.total,
            currencyCode: quote.currencyCode,
            className: RideClass.all[0].name,
            expiresAt: Date(timeIntervalSinceNow: 24)
        )
    }
}
