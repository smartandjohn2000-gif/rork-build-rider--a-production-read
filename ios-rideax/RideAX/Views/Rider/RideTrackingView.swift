import SwiftUI
import MapKit

/// Full-screen live ride: matching, driver approach, trip, payment and rating.
struct RideTrackingView: View {
    @Environment(SessionStore.self) private var session
    @Environment(RideStore.self) private var rideStore
    @Environment(TripStore.self) private var tripStore

    @State private var camera: MapCameraPosition = .automatic
    @State private var showsCancelDialog: Bool = false
    @State private var showsSafetySheet: Bool = false
    @State private var showsMessages: Bool = false
    @State private var isPaying: Bool = false

    /// Smooths the driver's sparse location pings into continuous motion.
    @State private var tracker: DriverTrackAnimator = DriverTrackAnimator()
    /// The map recentres on the driver until the rider pans away themselves.
    @State private var isFollowingDriver: Bool = true
    /// Last centre we set programmatically, used to tell our own camera moves
    /// apart from the rider dragging the map.
    @State private var appliedCenter: GeoPoint?

    var body: some View {
        ZStack(alignment: .top) {
            RideTheme.canvas.ignoresSafeArea()

            if let ride = rideStore.ride {
                mapLayer(ride)

                VStack(spacing: 10) {
                    topBar(ride)
                    if !isFollowingDriver, ride.status.isActive {
                        recenterButton
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.top, 8)

                VStack(spacing: 10) {
                    Spacer(minLength: 0)
                    statusCard(ride)
                    if let driver = ride.driver, ride.status.isActive {
                        driverCard(driver, ride: ride)
                    }
                    tripSummary(ride)
                    actionRow(ride)
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.bottom, 10)
            }

            if let ride = rideStore.ride, !ride.status.isActive {
                completionOverlay(ride)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showsSafetySheet) {
            SafetySheet(ride: rideStore.ride)
                .presentationDetents([.height(420)])
                .presentationBackground(RideTheme.canvas)
        }
        .sheet(isPresented: $showsMessages) {
            DriverChatSheet(driverName: rideStore.ride?.driver?.name ?? "Your driver")
                .presentationDetents([.medium, .large])
                .presentationBackground(RideTheme.canvas)
        }
        .confirmationDialog("Cancel this ride?", isPresented: $showsCancelDialog, titleVisibility: .visible) {
            Button("Cancel ride", role: .destructive) {
                rideStore.cancelRide()
            }
            Button("Keep my ride", role: .cancel) {}
        } message: {
            Text("Cancelling after your driver is on the way may incur a small fee.")
        }
        // Every driver location update feeds the interpolator, which then moves
        // the marker frame by frame between pings.
        .onChange(of: rideStore.driverPing) { _, ping in
            guard let ping else { return }
            tracker.ingest(ping, route: rideStore.driverRoute)
            updateCamera(duration: ping.expectedInterval * 1.15)
        }
        .onChange(of: rideStore.ride?.status) { _, status in
            guard let status else { return }
            if !status.isActive { tracker.reset() }
            updateCamera(duration: 0.8)
        }
        .onAppear {
            if let ping = rideStore.driverPing {
                tracker.ingest(ping, route: rideStore.driverRoute)
            }
            updateCamera(duration: 0.8)
        }
        .onDisappear { tracker.stopFrames() }
    }

    // MARK: - Map

    private func mapLayer(_ ride: Ride) -> some View {
        RideTrackingMap(
            ride: ride,
            route: mapRoute(for: ride),
            caption: markerCaption(for: ride),
            tracker: tracker,
            camera: $camera,
            onCameraSettled: cameraSettled
        )
    }

    /// The leg being driven right now. Before a driver is assigned we show the
    /// trip itself, so the rider can still see where they are heading.
    private func mapRoute(for ride: Ride) -> [GeoPoint] {
        rideStore.driverRoute.isEmpty ? ride.routeToDropoff : rideStore.driverRoute
    }

    /// Live label riding above the vehicle: countdown on approach, speed on trip.
    private func markerCaption(for ride: Ride) -> String? {
        switch ride.status {
        case .accepted, .arriving:
            return rideStore.etaMinutes <= 1 ? "Arriving" : "\(rideStore.etaMinutes) min"
        case .arrived:
            return "Waiting"
        case .started, .inProgress:
            if tracker.isMoving, tracker.speedKilometresPerHour > 0 {
                return "\(tracker.speedKilometresPerHour) km/h"
            }
            return rideStore.etaMinutes <= 1 ? "Almost there" : "\(rideStore.etaMinutes) min"
        default:
            return nil
        }
    }

    /// Keeps the driver and their next waypoint in frame. Animated across the
    /// ping window so the camera drifts with the car instead of hopping.
    private func updateCamera(duration: Double) {
        guard isFollowingDriver, let ride = rideStore.ride else { return }
        let driverPoint = tracker.point ?? ride.driverPoint
        var points: [GeoPoint] = []
        switch ride.status {
        case .requested, .matching:
            points = [ride.pickup.point, ride.dropoff.point]
        case .accepted, .arriving, .arrived:
            points = [driverPoint ?? ride.pickup.point, ride.pickup.point]
        default:
            points = [driverPoint ?? ride.pickup.point, ride.dropoff.point]
        }
        guard let region = MKCoordinateRegion.containing(points, paddingFactor: 2.0) else { return }
        appliedCenter = GeoPoint(region.center)
        withAnimation(.easeInOut(duration: max(duration, 0.3))) {
            camera = .region(region)
        }
    }

    /// Detects the rider panning the map, so we stop yanking it back to the car.
    private func cameraSettled(at center: GeoPoint) {
        guard let applied = appliedCenter else {
            appliedCenter = center
            return
        }
        if applied.distance(to: center) > 180 {
            withAnimation(.snappy(duration: 0.25)) { isFollowingDriver = false }
        }
    }

    private var recenterButton: some View {
        Button {
            Haptics.tap()
            isFollowingDriver = true
            updateCamera(duration: 0.6)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "location.fill")
                    .font(.system(size: 11, weight: .bold))
                Text("Follow driver")
                    .font(.caption.weight(.semibold))
            }
            .foregroundStyle(Color(hex: 0x14203A))
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(RideTheme.accent, in: .capsule)
            .shadow(color: .black.opacity(0.35), radius: 6, y: 3)
        }
        .buttonStyle(.plain)
        .transition(.scale(scale: 0.8).combined(with: .opacity))
    }

    // MARK: - Chrome

    private func topBar(_ ride: Ride) -> some View {
        HStack {
            if ride.status.allowsCancellation {
                CircleIconButton(symbol: "xmark", label: "Close ride", background: RideTheme.surface.opacity(0.95)) {
                    showsCancelDialog = true
                }
            }
            Spacer()
            if ride.status.isActive {
                HStack(spacing: 6) {
                    PulsingDot(color: RideTheme.success)
                    Text(ride.status.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                }
                .padding(.horizontal, 12)
                .frame(height: 34)
                .background(RideTheme.surface.opacity(0.95), in: .capsule)
                .overlay(Capsule().strokeBorder(RideTheme.hairline, lineWidth: 1))
            }
        }
    }

    // MARK: - Cards

    private func statusCard(_ ride: Ride) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                if ride.status == .requested || ride.status == .matching {
                    ProgressView()
                        .controlSize(.small)
                        .tint(RideTheme.accent)
                }
                Text(headline(for: ride))
                    .font(.headline)
                    .foregroundStyle(RideTheme.textPrimary)
                Spacer(minLength: 0)
            }

            ProgressLine(progress: progressValue(for: ride))
        }
        .rideFloatingCard(padding: 14)
    }

    private func headline(for ride: Ride) -> String {
        let name = ride.driver?.name.components(separatedBy: " ").first ?? "Your driver"
        switch ride.status {
        case .requested: return "Sending your request…"
        case .matching: return "Finding a driver nearby…"
        case .accepted: return "\(name) accepted your ride"
        case .arriving: return rideStore.etaMinutes <= 1 ? "\(name) is arriving now" : "\(name) is arriving in \(rideStore.etaMinutes) min"
        case .arrived: return "\(name) has arrived"
        case .started: return "Starting your trip"
        case .inProgress: return rideStore.etaMinutes <= 1 ? "Arriving at your destination" : "\(rideStore.etaMinutes) min to \(ride.dropoff.name)"
        case .completed: return "You've arrived"
        case .paid: return "Payment complete"
        case .cancelled: return "Ride cancelled"
        }
    }

    private func progressValue(for ride: Ride) -> Double {
        switch ride.status {
        case .requested: 0.05
        case .matching: 0.15
        case .accepted: 0.2
        case .arriving, .arrived: 0.2 + rideStore.progress * 0.3
        case .started: 0.5
        case .inProgress: 0.5 + rideStore.progress * 0.5
        default: 1
        }
    }

    private func driverCard(_ driver: DriverProfile, ride: Ride) -> some View {
        HStack(spacing: 14) {
            avatar(driver)

            VStack(alignment: .leading, spacing: 3) {
                Text(driver.name)
                    .font(.headline)
                    .foregroundStyle(RideTheme.textPrimary)
                RatingLabel(rating: driver.rating, detail: "\(driver.tripCount.formatted()) trips")
                Text("\(driver.vehicle) · \(driver.plate)")
                    .font(.footnote)
                    .foregroundStyle(RideTheme.textSecondary)
            }

            Spacer(minLength: 0)

            HStack(spacing: 8) {
                CircleIconButton(symbol: "phone.fill", label: "Call driver") {
                    if let url = URL(string: "tel://+33612345678"), UIApplication.shared.canOpenURL(url) {
                        UIApplication.shared.open(url)
                    }
                }
                CircleIconButton(symbol: "bubble.left.fill", label: "Message driver") {
                    showsMessages = true
                }
            }
        }
        .rideFloatingCard(padding: 14)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func avatar(_ driver: DriverProfile) -> some View {
        Group {
            if let url = driver.photoURL {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        Image(systemName: "person.fill")
                            .font(.title3)
                            .foregroundStyle(RideTheme.textSecondary)
                    }
                }
            } else {
                Image(systemName: "person.fill")
                    .font(.title3)
                    .foregroundStyle(RideTheme.textSecondary)
            }
        }
        .frame(width: 54, height: 54)
        .background(RideTheme.surfaceRaised)
        .clipShape(.circle)
        .overlay(Circle().strokeBorder(RideTheme.hairline, lineWidth: 1))
    }

    private func tripSummary(_ ride: Ride) -> some View {
        HStack(alignment: .center, spacing: 14) {
            RouteSummaryRow(pickupName: ride.pickup.name, dropoffName: ride.dropoff.name, compact: true)

            VStack(alignment: .trailing, spacing: 4) {
                Text(ride.quote.formattedTotal)
                    .font(.title3.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.textPrimary)
                Text(session.defaultPaymentMethod?.displayName ?? ride.rideClass.name)
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
                    .lineLimit(1)
            }
        }
        .rideFloatingCard(padding: 14)
    }

    @ViewBuilder
    private func actionRow(_ ride: Ride) -> some View {
        if ride.status.isActive {
            HStack(spacing: 10) {
                Button {
                    Haptics.tap()
                    showsSafetySheet = true
                } label: {
                    Label("Safety", systemImage: "shield.lefthalf.filled")
                }
                .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.accent, bordered: true))

                if ride.status.allowsCancellation {
                    Button("Cancel ride") {
                        Haptics.tap()
                        showsCancelDialog = true
                    }
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.textPrimary))
                }
            }
        }
    }

    // MARK: - Completion, payment and rating

    private func completionOverlay(_ ride: Ride) -> some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()

            VStack(spacing: 16) {
                Spacer()

                VStack(spacing: 14) {
                    Image(systemName: ride.status == .cancelled ? "xmark.circle.fill" : "checkmark.seal.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(ride.status == .cancelled ? RideTheme.danger : RideTheme.success)

                    Text(ride.status == .cancelled ? "Ride cancelled" : "You've arrived")
                        .font(.title3.bold())
                        .foregroundStyle(RideTheme.textPrimary)

                    if ride.status == .cancelled {
                        Text("No charge was made. You can book another ride whenever you're ready.")
                            .font(.subheadline)
                            .foregroundStyle(RideTheme.textSecondary)
                            .multilineTextAlignment(.center)
                    } else {
                        RouteSummaryRow(pickupName: ride.pickup.name, dropoffName: ride.dropoff.name, compact: true)
                            .padding(.vertical, 4)

                        fareBreakdown(ride)
                    }

                    if ride.status == .completed {
                        Button {
                            Task {
                                isPaying = true
                                await rideStore.payForRide(method: session.defaultPaymentMethod, tripStore: tripStore)
                                isPaying = false
                            }
                        } label: {
                            if isPaying {
                                ProgressView().tint(Color(hex: 0x14203A))
                            } else {
                                Text("Pay \(ride.quote.formattedTotal)")
                            }
                        }
                        .buttonStyle(RidePrimaryButtonStyle())
                        .disabled(isPaying)
                    }

                    if ride.status == .paid {
                        ratingSection(ride)
                    }

                    if ride.status == .cancelled || ride.riderRatingForDriver != nil {
                        Button("Done") {
                            rideStore.dismissCompletedRide()
                        }
                        .buttonStyle(RidePrimaryButtonStyle())
                    }
                }
                .rideCard(padding: 18)
            }
            .padding(.horizontal, RideTheme.margin)
            .padding(.bottom, 24)
        }
        .transition(.opacity)
    }

    private func fareBreakdown(_ ride: Ride) -> some View {
        VStack(spacing: 8) {
            breakdownRow("Base fare", ride.quote.base, code: ride.quote.currencyCode)
            breakdownRow("Distance", ride.quote.distanceCharge, code: ride.quote.currencyCode)
            breakdownRow("Time", ride.quote.timeCharge, code: ride.quote.currencyCode)
            breakdownRow("Booking fee", ride.quote.bookingFee, code: ride.quote.currencyCode)
            Divider().overlay(RideTheme.hairline)
            HStack {
                Text("Total")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideTheme.textPrimary)
                Spacer()
                Text(ride.quote.formattedTotal)
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.accent)
            }

            if let receipt = rideStore.receipt {
                Text("Paid with \(receipt.paymentMethodLabel) · receipt \(receipt.id)")
                    .font(.caption2)
                    .foregroundStyle(RideTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .background(RideTheme.surfaceRaised, in: .rect(cornerRadius: RideTheme.controlRadius))
    }

    private func breakdownRow(_ title: String, _ value: Double, code: String) -> some View {
        HStack {
            Text(title)
                .font(.footnote)
                .foregroundStyle(RideTheme.textSecondary)
            Spacer()
            Text(value.formatted(.currency(code: code).precision(.fractionLength(2))))
                .font(.footnote)
                .monospacedDigit()
                .foregroundStyle(RideTheme.textPrimary)
        }
    }

    private func ratingSection(_ ride: Ride) -> some View {
        VStack(spacing: 10) {
            Text("How was your ride with \(ride.driver?.name ?? "your driver")?")
                .font(.subheadline)
                .foregroundStyle(RideTheme.textSecondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 10) {
                ForEach(1...5, id: \.self) { star in
                    Button {
                        rideStore.rateDriver(stars: star, tripStore: tripStore)
                    } label: {
                        Image(systemName: (ride.riderRatingForDriver ?? 0) >= star ? "star.fill" : "star")
                            .font(.system(size: 26))
                            .foregroundStyle((ride.riderRatingForDriver ?? 0) >= star ? RideTheme.accentWarm : RideTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(star) star\(star == 1 ? "" : "s")")
                }
            }
        }
    }
}

/// The live ride map.
///
/// Kept as its own view on purpose: the driver marker is redrawn at frame rate
/// while it interpolates between location pings, and this boundary means only
/// the map re-renders — the status, driver and fare cards stacked on top of it
/// stay untouched until their own data actually changes.
private struct RideTrackingMap: View {
    let ride: Ride
    let route: [GeoPoint]
    let caption: String?
    let tracker: DriverTrackAnimator
    @Binding var camera: MapCameraPosition
    let onCameraSettled: (GeoPoint) -> Void

    private var driverPoint: GeoPoint? { tracker.point ?? ride.driverPoint }

    /// Road already covered on this leg, drawn muted behind the live route.
    private var travelledPath: [GeoPoint] {
        guard tracker.progress > 0.002 else { return [] }
        return route.travelled(toProgress: tracker.progress)
    }

    /// Road still to drive, which visibly shortens as the car advances.
    private var remainingPath: [GeoPoint] {
        guard driverPoint != nil else { return route }
        return route.remainder(fromProgress: tracker.progress)
    }

    var body: some View {
        Map(position: $camera) {
            if travelledPath.count > 1 {
                MapPolyline(coordinates: travelledPath.map(\.coordinate))
                    .stroke(
                        RideTheme.textSecondary.opacity(0.35),
                        style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                    )
            }

            if remainingPath.count > 1 {
                MapPolyline(coordinates: remainingPath.map(\.coordinate))
                    .stroke(
                        RideTheme.accent,
                        style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round)
                    )
            }

            Annotation("", coordinate: ride.pickup.point.coordinate) {
                PickupPin()
            }

            Annotation("", coordinate: ride.dropoff.point.coordinate) {
                DropoffMarker()
            }

            if let driverPoint {
                Annotation("", coordinate: driverPoint.coordinate, anchor: .center) {
                    CarMarker(isActive: true, heading: tracker.heading, isMoving: tracker.isMoving)
                        .overlay(alignment: .top) {
                            if let caption {
                                DriverMarkerCaption(text: caption)
                                    .offset(y: -8)
                            }
                        }
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControlVisibility(.hidden)
        .onMapCameraChange(frequency: .onEnd) { context in
            onCameraSettled(GeoPoint(context.region.center))
        }
        .ignoresSafeArea()
    }
}

/// Safety options presented during a live trip.
struct SafetySheet: View {
    let ride: Ride?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                VStack(spacing: 6) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 30))
                        .foregroundStyle(RideTheme.accent)
                    Text("Safety toolkit")
                        .font(.title3.bold())
                        .foregroundStyle(RideTheme.textPrimary)
                    Text("Your trip details are recorded and can be shared at any time.")
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 20)

                safetyRow("Share trip status", detail: "Send a live link to a trusted contact", symbol: "square.and.arrow.up.fill")
                safetyRow("Call emergency services", detail: "Your location and driver details are shown", symbol: "phone.arrow.up.right.fill", tint: RideTheme.danger)
                safetyRow("Report an issue", detail: "Tell our support team what happened", symbol: "exclamationmark.bubble.fill")

                if let ride, let driver = ride.driver {
                    Text("\(driver.vehicle) · \(driver.plate)")
                        .font(.caption)
                        .foregroundStyle(RideTheme.textSecondary)
                        .padding(.top, 4)
                }

                Button("Close") { dismiss() }
                    .buttonStyle(RideSecondaryButtonStyle())
                    .padding(.top, 6)
            }
            .padding(RideTheme.margin)
        }
        .background(RideTheme.canvas)
        .preferredColorScheme(.dark)
    }

    private func safetyRow(_ title: String, detail: String, symbol: String, tint: Color = RideTheme.accent) -> some View {
        Button {
            Haptics.tap()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 16))
                    .foregroundStyle(tint)
                    .frame(width: 42, height: 42)
                    .background(tint.opacity(0.14), in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .rideCard(padding: 12)
        }
        .buttonStyle(.plain)
    }
}

/// Lightweight in-trip messaging with the driver.
struct DriverChatSheet: View {
    let driverName: String
    @Environment(\.dismiss) private var dismiss

    @State private var messages: [ChatMessage] = ChatMessage.samples
    @State private var draft: String = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(messages) { message in
                            HStack {
                                if message.isFromRider { Spacer(minLength: 40) }
                                Text(message.text)
                                    .font(.subheadline)
                                    .foregroundStyle(message.isFromRider ? Color(hex: 0x14203A) : RideTheme.textPrimary)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 10)
                                    .background(
                                        message.isFromRider ? RideTheme.accent : RideTheme.surface,
                                        in: .rect(cornerRadius: 16)
                                    )
                                if !message.isFromRider { Spacer(minLength: 40) }
                            }
                        }
                    }
                    .padding(RideTheme.margin)
                }

                HStack(spacing: 10) {
                    TextField("", text: $draft, prompt: Text("Message \(driverName)").foregroundStyle(RideTheme.textSecondary))
                        .foregroundStyle(RideTheme.textPrimary)
                        .padding(.horizontal, 14)
                        .frame(height: 46)
                        .background(RideTheme.surface, in: .capsule)

                    Button {
                        send()
                    } label: {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(Color(hex: 0x14203A))
                            .frame(width: 46, height: 46)
                            .background(RideTheme.accent, in: .circle)
                    }
                    .buttonStyle(.plain)
                    .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.bottom, 10)
            }
            .background(RideTheme.canvas)
            .navigationTitle(driverName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(RideTheme.accent)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        messages.append(ChatMessage(id: UUID().uuidString, text: text, isFromRider: true))
        draft = ""
        Haptics.tap()
    }
}

nonisolated struct ChatMessage: Identifiable, Hashable, Sendable {
    var id: String
    var text: String
    var isFromRider: Bool

    static let samples: [ChatMessage] = [
        ChatMessage(id: "m1", text: "I'm two streets away, wearing a blue jacket.", isFromRider: false),
        ChatMessage(id: "m2", text: "Great — I'm outside the bakery on the corner.", isFromRider: true)
    ]
}
