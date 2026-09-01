import SwiftUI
import MapKit

/// Rider home: a live map with the pickup pin, nearby cars and a booking sheet.
struct BookingHomeView: View {
    @Environment(SessionStore.self) private var session
    @Environment(RideStore.self) private var rideStore
    @Environment(TripStore.self) private var tripStore
    @Environment(LocationService.self) private var location

    @State private var camera: MapCameraPosition = .region(
        MKCoordinateRegion(
            center: LocationService.fallbackPoint.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.03)
        )
    )
    @State private var showsSearch: Bool = false
    @State private var showsPaymentPicker: Bool = false
    @State private var showsFareBreakdown: Bool = false

    var body: some View {
        ZStack(alignment: .top) {
            mapLayer

            VStack(spacing: 10) {
                searchField
                savedPlacesRow
            }
            .padding(.horizontal, RideTheme.margin)
            .padding(.top, 6)

            VStack {
                Spacer(minLength: 0)
                bookingSheet
                    .padding(.horizontal, RideTheme.margin)
                    .padding(.bottom, 8)
            }
        }
        .background(RideTheme.canvas)
        .sheet(isPresented: $showsSearch) {
            DestinationSearchView { place in
                rideStore.setDestination(place)
            }
        }
        .sheet(isPresented: $showsPaymentPicker) {
            PaymentMethodSheet()
                .presentationDetents([.height(320)])
                .presentationBackground(RideTheme.canvas)
        }
        .fullScreenCover(isPresented: .constant(rideStore.ride != nil)) {
            RideTrackingView()
        }
        .task {
            location.requestPermission()
            location.startUpdating()
            rideStore.startLiveFareUpdates()
        }
        .onDisappear {
            rideStore.stopLiveFareUpdates()
        }
        .onChange(of: location.currentPoint) { _, newValue in
            guard location.hasRealFix else { return }
            Task {
                let name = await RouteService().placeName(for: newValue)
                rideStore.syncPickupWithLocation(newValue, name: name)
            }
        }
        .onChange(of: rideStore.routeEstimate) { _, estimate in
            guard let estimate, let region = MKCoordinateRegion.containing(estimate.polyline) else { return }
            withAnimation(.easeInOut(duration: 0.6)) {
                camera = .region(region)
            }
        }
        .onAppear {
            rideStore.syncPickupWithLocation(location.currentPoint, name: nil)
        }
    }

    // MARK: - Map

    private var mapLayer: some View {
        Map(position: $camera) {
            ForEach(Array(rideStore.nearbyDrivers.enumerated()), id: \.offset) { index, point in
                Annotation("", coordinate: point.coordinate) {
                    CarMarker(heading: Double(index * 47 % 360))
                }
            }

            if rideStore.activeRoute.count > 1 {
                MapPolyline(coordinates: rideStore.activeRoute.map(\.coordinate))
                    .stroke(RideTheme.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            }

            Annotation("", coordinate: rideStore.pickup.point.coordinate) {
                PickupPin()
            }

            if let destination = rideStore.destination {
                Annotation("", coordinate: destination.point.coordinate) {
                    DropoffMarker()
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControlVisibility(.hidden)
        .ignoresSafeArea(edges: .top)
    }

    // MARK: - Top chrome

    private var searchField: some View {
        Button {
            Haptics.tap()
            showsSearch = true
        } label: {
            HStack(spacing: 12) {
                BrandMark(size: 22)
                    .frame(width: 30, height: 30)

                Circle()
                    .fill(RideTheme.textPrimary)
                    .frame(width: 8, height: 8)

                Text(rideStore.destination?.name ?? "Where to?")
                    .font(.body.weight(.medium))
                    .foregroundStyle(rideStore.destination == nil ? RideTheme.textSecondary : RideTheme.textPrimary)
                    .lineLimit(1)

                Spacer(minLength: 0)

                Image(systemName: "clock")
                    .font(.system(size: 17))
                    .foregroundStyle(RideTheme.textSecondary)
            }
            .padding(.horizontal, 14)
            .frame(height: 56)
            .background(RideTheme.surface.opacity(0.96), in: .capsule)
            .overlay(Capsule().strokeBorder(RideTheme.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.4), radius: 14, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Choose destination")
    }

    private var savedPlacesRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(session.savedPlaces) { place in
                    Button {
                        Haptics.tap()
                        rideStore.setDestination(place)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: place.symbol)
                                .font(.footnote)
                                .foregroundStyle(RideTheme.accent)
                            Text(place.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(RideTheme.textPrimary)
                            Text("· \(place.subtitle)")
                                .font(.subheadline)
                                .foregroundStyle(RideTheme.textSecondary)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 14)
                        .frame(height: 42)
                        .background(RideTheme.surface.opacity(0.94), in: .capsule)
                        .overlay(Capsule().strokeBorder(RideTheme.hairline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 2)
        }
        .scrollClipDisabled()
    }

    // MARK: - Booking sheet

    private var bookingSheet: some View {
        VStack(spacing: 12) {
            Capsule()
                .fill(RideTheme.textSecondary.opacity(0.4))
                .frame(width: 38, height: 4)
                .padding(.bottom, 2)

            if rideStore.destination == nil {
                emptyDestinationState
            } else {
                LiveFareCard(
                    quote: rideStore.selectedQuote,
                    demand: rideStore.demand,
                    estimate: rideStore.routeEstimate,
                    rates: rideStore.selectedRates,
                    isQuoting: rideStore.isQuoting,
                    isRepricing: rideStore.isRepricing,
                    updatedAt: rideStore.quoteUpdatedAt,
                    fareChange: rideStore.lastFareChange,
                    isFareCurrent: rideStore.isFareCurrent,
                    driftMeters: rideStore.pickupDriftMeters,
                    isExpanded: $showsFareBreakdown,
                    onRefresh: { rideStore.repriceNow() }
                )

                rideClassList

                Button {
                    Haptics.tap()
                    showsPaymentPicker = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "creditcard.fill")
                            .foregroundStyle(RideTheme.accent)
                        Text(session.defaultPaymentMethod?.displayName ?? "Add payment method")
                            .font(.subheadline)
                            .foregroundStyle(RideTheme.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(RideTheme.textSecondary)
                    }
                    .padding(.horizontal, 14)
                    .frame(height: 48)
                    .background(RideTheme.surfaceRaised, in: .rect(cornerRadius: RideTheme.controlRadius))
                }
                .buttonStyle(.plain)

                Button {
                    rideStore.requestRide(paymentMethod: session.defaultPaymentMethod)
                } label: {
                    HStack(spacing: 8) {
                        Text("Request \(rideStore.selectedClass.name)")
                        if let quote = rideStore.selectedQuote {
                            Text("·").opacity(0.5)
                            Text(quote.formattedTotal)
                                .monospacedDigit()
                                .contentTransition(.numericText(value: quote.total))
                        }
                    }
                }
                .buttonStyle(RidePrimaryButtonStyle(isEnabled: rideStore.selectedQuote != nil))
                .disabled(rideStore.selectedQuote == nil)
                .animation(.snappy(duration: 0.35), value: rideStore.selectedQuote?.total)
            }
        }
        .rideFloatingCard(padding: 14)
        .animation(.spring(response: 0.36, dampingFraction: 0.85), value: rideStore.destination)
        .onChange(of: rideStore.lastFareChange) { _, change in
            guard change != 0 else { return }
            Haptics.tap()
        }
        .alert("Something went wrong", isPresented: .constant(rideStore.errorMessage != nil)) {
            Button("OK") { rideStore.clearError() }
        } message: {
            Text(rideStore.errorMessage ?? "")
        }
    }

    private var emptyDestinationState: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "location.magnifyingglass")
                    .font(.title3)
                    .foregroundStyle(RideTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Where are you heading?")
                        .font(.headline)
                        .foregroundStyle(RideTheme.textPrimary)
                    Text(location.isDenied
                         ? "Location is off — set your pickup manually."
                         : "Pickup: \(rideStore.pickup.name)")
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }

            Button("Choose destination") {
                Haptics.tap()
                showsSearch = true
            }
            .buttonStyle(RidePrimaryButtonStyle())
        }
    }

    private var rideClassList: some View {
        VStack(spacing: 8) {
            ForEach(RideClass.all) { rideClass in
                let quote = rideStore.quotes[rideClass.id]
                Button {
                    Haptics.tap()
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        rideStore.selectedClassID = rideClass.id
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: rideClass.symbol)
                            .font(.system(size: 22))
                            .foregroundStyle(RideTheme.textPrimary)
                            .frame(width: 46, height: 34)

                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(rideClass.name)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(RideTheme.textPrimary)
                                Image(systemName: "person.fill")
                                    .font(.system(size: 9))
                                    .foregroundStyle(RideTheme.textSecondary)
                                Text("\(rideClass.seats)")
                                    .font(.caption2)
                                    .foregroundStyle(RideTheme.textSecondary)
                            }
                            Text("\(rideClass.etaMinutes) min away")
                                .font(.footnote)
                                .foregroundStyle(rideStore.selectedClassID == rideClass.id ? RideTheme.accent : RideTheme.textSecondary)
                        }

                        Spacer(minLength: 0)

                        if let quote {
                            VStack(alignment: .trailing, spacing: 1) {
                                Text(quote.formattedTotal)
                                    .font(.subheadline.weight(.semibold))
                                    .monospacedDigit()
                                    .contentTransition(.numericText(value: quote.total))
                                    .foregroundStyle(RideTheme.textPrimary)
                                if quote.isSurging {
                                    Text(quote.surgeMultiplier.formatted(.number.precision(.fractionLength(1))) + "×")
                                        .font(.caption2.weight(.semibold))
                                        .monospacedDigit()
                                        .foregroundStyle(RideTheme.accent)
                                }
                            }
                            .animation(.snappy(duration: 0.35), value: quote.total)
                        } else if rideStore.isQuoting {
                            ProgressView().controlSize(.small).tint(RideTheme.textSecondary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(
                        RideTheme.surfaceRaised.opacity(rideStore.selectedClassID == rideClass.id ? 1 : 0.55),
                        in: .rect(cornerRadius: RideTheme.controlRadius)
                    )
                    .overlay {
                        RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                            .strokeBorder(
                                rideStore.selectedClassID == rideClass.id ? RideTheme.accent : Color.clear,
                                lineWidth: 1.5
                            )
                    }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(rideStore.selectedClassID == rideClass.id ? [.isSelected, .isButton] : .isButton)
            }

        }
    }
}

/// Choose which card pays for the trip.
struct PaymentMethodSheet: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(session.paymentMethods) { method in
                        Button {
                            session.makeDefault(method)
                            Haptics.tap()
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "creditcard.fill")
                                    .foregroundStyle(RideTheme.accent)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(method.displayName)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(RideTheme.textPrimary)
                                    Text(method.supportsWallet ? "Card and wallet" : "Card")
                                        .font(.caption)
                                        .foregroundStyle(RideTheme.textSecondary)
                                }
                                Spacer()
                                if method.isDefault {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(RideTheme.accent)
                                }
                            }
                            .rideCard(padding: 14)
                        }
                        .buttonStyle(.plain)
                    }

                    Text("Payments are processed by our PCI-compliant provider. RideAX never stores your full card number.")
                        .font(.caption2)
                        .foregroundStyle(RideTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 6)
                }
                .padding(RideTheme.margin)
            }
            .background(RideTheme.canvas)
            .navigationTitle("Payment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
    }
}
