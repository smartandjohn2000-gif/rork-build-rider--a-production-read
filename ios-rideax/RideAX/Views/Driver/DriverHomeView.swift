import SwiftUI
import MapKit

/// Driver cockpit: availability, live map, incoming offers and the active job.
struct DriverHomeView: View {
    @Environment(SessionStore.self) private var session
    @Environment(DriverStore.self) private var driverStore
    @Environment(TripStore.self) private var tripStore

    @State private var camera: MapCameraPosition = .region(
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 48.8720, longitude: 2.3625),
            span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        )
    )
    @State private var showsEarnings: Bool = false
    @State private var showsAccount: Bool = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                mapLayer

                VStack(spacing: 10) {
                    statusHeader

                    Spacer(minLength: 0)

                    if driverStore.approvalStatus != .approved {
                        DriverApprovalCard()
                    } else if let offer = driverStore.currentOffer {
                        OfferCard(offer: offer, secondsRemaining: driverStore.secondsRemaining) {
                            driverStore.declineOffer()
                        } onAccept: {
                            driverStore.acceptOffer()
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else if driverStore.activeJob != nil {
                        activeJobCard
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else if driverStore.isOnline {
                        waitingCard
                    } else {
                        offlineCard
                    }

                    statsStrip
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.top, 6)
                .padding(.bottom, 8)
                .animation(.spring(response: 0.38, dampingFraction: 0.85), value: driverStore.currentOffer)
                .animation(.spring(response: 0.38, dampingFraction: 0.85), value: driverStore.jobStage)
            }
            .background(RideTheme.canvas)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showsEarnings) {
                DriverEarningsView()
            }
            .sheet(isPresented: $showsAccount) {
                DriverAccountView()
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: driverStore.driverPoint) { _, point in
            withAnimation(.easeInOut(duration: 0.6)) {
                camera = .region(
                    MKCoordinateRegion(
                        center: point.coordinate,
                        span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
                    )
                )
            }
        }
    }

    // MARK: - Map

    private var mapLayer: some View {
        Map(position: $camera) {
            if driverStore.routeToRider.count > 1 {
                MapPolyline(coordinates: driverStore.routeToRider.remainder(fromProgress: driverStore.jobProgress).map(\.coordinate))
                    .stroke(RideTheme.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            }

            if let offer = driverStore.currentOffer {
                MapCircle(center: offer.pickup.point.coordinate, radius: 420)
                    .foregroundStyle(RideTheme.accent.opacity(0.22))
                    .stroke(RideTheme.accent, lineWidth: 1.5)
                Annotation("", coordinate: offer.pickup.point.coordinate) {
                    PickupPin()
                }
            }

            if let job = driverStore.activeJob {
                Annotation("", coordinate: job.pickup.point.coordinate) {
                    PickupPin()
                }
                if driverStore.jobStage == .onTrip {
                    Annotation("", coordinate: job.dropoff.point.coordinate) {
                        DropoffMarker()
                    }
                }
            }

            Annotation("", coordinate: driverStore.driverPoint.coordinate) {
                DriverSelfMarker()
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControlVisibility(.hidden)
        .ignoresSafeArea()
    }

    // MARK: - Header

    private var statusHeader: some View {
        HStack(spacing: 12) {
            Button {
                Haptics.tap()
                showsAccount = true
            } label: {
                Image(systemName: "steeringwheel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(RideTheme.accent)
                    .frame(width: 42, height: 42)
                    .background(RideTheme.surfaceRaised, in: .circle)
                    .overlay(Circle().strokeBorder(RideTheme.hairline, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Driver account")

            VStack(alignment: .leading, spacing: 3) {
                Text(driverStore.isOnline ? "You're online" : "You're offline")
                    .font(.headline)
                    .foregroundStyle(driverStore.isOnline ? RideTheme.success : RideTheme.textPrimary)
                Text("\(driverStore.onlineDurationText) · \(tripStore.todaysEarnings.formatted(.currency(code: "EUR").precision(.fractionLength(2))))")
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.textSecondary)
            }

            Spacer(minLength: 0)

            Toggle("", isOn: Binding(
                get: { driverStore.isOnline },
                set: { driverStore.setOnline($0) }
            ))
            .labelsHidden()
            .tint(RideTheme.success)
            .disabled(!driverStore.canGoOnline)
            .accessibilityLabel(driverStore.isOnline ? "Go offline" : "Go online")
        }
        .rideFloatingCard(padding: 14)
    }

    // MARK: - Cards

    private var waitingCard: some View {
        HStack(spacing: 12) {
            ProgressView()
                .tint(RideTheme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text("Looking for requests nearby")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideTheme.textPrimary)
                Text("Stay in a busy area to get matched faster.")
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .rideFloatingCard(padding: 14)
    }

    private var offlineCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "moon.zzz.fill")
                    .font(.title3)
                    .foregroundStyle(RideTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("You're offline")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                    Text("Go online to start receiving ride requests.")
                        .font(.caption)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }

            Button("Go online") {
                driverStore.setOnline(true)
            }
            .buttonStyle(RidePrimaryButtonStyle(isEnabled: driverStore.canGoOnline))
            .disabled(!driverStore.canGoOnline)
        }
        .rideFloatingCard(padding: 14)
    }

    private var activeJobCard: some View {
        VStack(spacing: 12) {
            if let job = driverStore.activeJob {
                HStack {
                    Text(driverStore.jobStage.title)
                        .font(.headline)
                        .foregroundStyle(RideTheme.textPrimary)
                    Spacer()
                    Text(job.formattedFare)
                        .font(.headline)
                        .monospacedDigit()
                        .foregroundStyle(RideTheme.accent)
                }

                RouteSummaryRow(pickupName: job.pickup.name, dropoffName: job.dropoff.name, compact: true)

                HStack(spacing: 10) {
                    Image(systemName: "person.circle.fill")
                        .font(.title3)
                        .foregroundStyle(RideTheme.textSecondary)
                    Text(job.riderName)
                        .font(.subheadline)
                        .foregroundStyle(RideTheme.textPrimary)
                    RatingLabel(rating: job.riderRating)
                    Spacer()
                    CircleIconButton(symbol: "phone.fill", label: "Call rider") {}
                }

                if driverStore.jobStage == .headingToPickup || driverStore.jobStage == .onTrip {
                    ProgressLine(progress: driverStore.jobProgress)
                }

                HStack(spacing: 10) {
                    if driverStore.jobStage != .completed {
                        Button("Cancel") {
                            driverStore.cancelJob()
                        }
                        .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.danger))
                        .frame(width: 110)
                    }

                    Button(driverStore.jobStage.actionTitle) {
                        driverStore.advanceJob(tripStore: tripStore)
                    }
                    .buttonStyle(RidePrimaryButtonStyle())
                }
            }
        }
        .rideFloatingCard(padding: 14)
    }

    private var statsStrip: some View {
        Button {
            Haptics.tap()
            showsEarnings = true
        } label: {
            HStack(spacing: 12) {
                StatTile(symbol: "checkmark.circle", value: "\(driverStore.acceptanceRate)%", caption: "acceptance")
                StatTile(symbol: "car.fill", value: "\(tripStore.todaysTripCount)", caption: "trips today")
                StatTile(
                    symbol: "creditcard.fill",
                    value: tripStore.weeklyTotal.formatted(.currency(code: "EUR").precision(.fractionLength(2))),
                    caption: "this week",
                    tint: RideTheme.accentWarm
                )
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(RideTheme.textSecondary)
            }
            .rideFloatingCard(padding: 14)
        }
        .buttonStyle(.plain)
    }
}

/// The incoming request card with its countdown.
private struct OfferCard: View {
    let offer: RideOffer
    let secondsRemaining: Int
    let onDecline: () -> Void
    let onAccept: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(RideTheme.textSecondary.opacity(0.25), lineWidth: 4)
                    Circle()
                        .trim(from: 0, to: max(0, Double(secondsRemaining) / 24))
                        .stroke(RideTheme.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 1), value: secondsRemaining)
                    Text("0:\(String(format: "%02d", max(0, secondsRemaining)))")
                        .font(.caption.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(RideTheme.textPrimary)
                }
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: 3) {
                    Text("New request")
                        .font(.headline)
                        .foregroundStyle(RideTheme.textPrimary)
                    Text("Pickup \(distanceText) · \(offer.pickupETAMinutes) min away")
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                }

                Spacer(minLength: 0)
            }

            Divider().overlay(RideTheme.hairline)

            RouteSummaryRow(pickupName: offer.pickup.name, dropoffName: offer.dropoff.name, compact: true)

            HStack(spacing: 10) {
                Text(offer.formattedFare)
                    .font(.title3.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.textPrimary)
                Text("· \(offer.className)")
                    .font(.subheadline)
                    .foregroundStyle(RideTheme.textSecondary)
                Spacer()
                HStack(spacing: 6) {
                    Image(systemName: "person.circle.fill")
                        .foregroundStyle(RideTheme.textSecondary)
                    Text(offer.riderName)
                        .font(.subheadline)
                        .foregroundStyle(RideTheme.textPrimary)
                    RatingLabel(rating: offer.riderRating)
                }
            }

            HStack(spacing: 10) {
                Button("Decline", action: onDecline)
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.accent))
                Button("Accept ride", action: onAccept)
                    .buttonStyle(RidePrimaryButtonStyle())
            }
        }
        .rideFloatingCard(padding: 16)
    }

    private var distanceText: String {
        Measurement(value: offer.pickupDistanceMeters, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .road))
    }
}

/// Shown while the driver is not yet approved by the admin team.
private struct DriverApprovalCard: View {
    @Environment(DriverStore.self) private var driverStore

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: driverStore.approvalStatus == .pending ? "clock.badge.checkmark.fill" : "doc.badge.plus")
                    .font(.title3)
                    .foregroundStyle(RideTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(driverStore.approvalStatus.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                    Text(driverStore.approvalStatus.detail)
                        .font(.caption)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }

            NavigationLink {
                DriverDocumentsView()
            } label: {
                Text(driverStore.approvalStatus == .pending ? "View submission" : "Complete your profile")
                    .font(.headline)
                    .foregroundStyle(Color(hex: 0x14203A))
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(RideTheme.accent, in: .rect(cornerRadius: 27))
            }
            .buttonStyle(.plain)
        }
        .rideFloatingCard(padding: 14)
    }
}
