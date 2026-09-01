import SwiftUI

/// Rider trip history with receipts and ratings.
struct ActivityView: View {
    @Environment(TripStore.self) private var tripStore
    @State private var selectedTrip: TripRecord?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    summaryCard

                    SectionHeader(title: "Past trips")
                        .padding(.top, 4)

                    if tripStore.riderTrips.isEmpty {
                        EmptyStateView(
                            symbol: "car.rear.road.lane",
                            title: "No trips yet",
                            message: "Your rides will appear here with fares, receipts and ratings."
                        )
                    } else {
                        ForEach(tripStore.riderTrips) { trip in
                            Button {
                                Haptics.tap()
                                selectedTrip = trip
                            } label: {
                                tripRow(trip)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.bottom, 28)
            }
            .background(RideTheme.canvas)
            .navigationTitle("Activity")
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
            .sheet(item: $selectedTrip) { trip in
                TripDetailSheet(trip: trip)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(RideTheme.canvas)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var summaryCard: some View {
        let completed = tripStore.riderTrips.filter { !$0.wasCancelled }
        let spend = completed.reduce(0) { $0 + $1.fare }
        return HStack(spacing: 12) {
            StatTile(symbol: "car.fill", value: "\(completed.count)", caption: "trips taken", tint: RideTheme.accent)
            Divider().frame(height: 32).overlay(RideTheme.hairline)
            StatTile(
                symbol: "eurosign.circle.fill",
                value: spend.formatted(.currency(code: "EUR").precision(.fractionLength(2))),
                caption: "total spent",
                tint: RideTheme.accentWarm
            )
            Divider().frame(height: 32).overlay(RideTheme.hairline)
            StatTile(symbol: "star.fill", value: "4.9", caption: "your rating", tint: RideTheme.accentWarm)
        }
        .rideCard()
    }

    private func tripRow(_ trip: TripRecord) -> some View {
        HStack(spacing: 14) {
            Image(systemName: trip.wasCancelled ? "xmark.circle.fill" : "car.fill")
                .font(.system(size: 16))
                .foregroundStyle(trip.wasCancelled ? RideTheme.danger : RideTheme.accent)
                .frame(width: 42, height: 42)
                .background(RideTheme.surfaceRaised, in: .circle)

            VStack(alignment: .leading, spacing: 3) {
                Text("\(trip.pickupName) → \(trip.dropoffName)")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(RideTheme.textPrimary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(trip.date.formatted(.dateTime.weekday(.abbreviated).hour().minute()))
                    Text("·")
                    Text(trip.className)
                    if let rating = trip.rating {
                        Text("·")
                        HStack(spacing: 2) {
                            Image(systemName: "star.fill").font(.system(size: 8))
                            Text("\(rating)")
                        }
                        .foregroundStyle(RideTheme.accentWarm)
                    }
                }
                .font(.caption)
                .foregroundStyle(RideTheme.textSecondary)
            }

            Spacer(minLength: 0)

            Text(trip.wasCancelled ? "Cancelled" : trip.formattedFare)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(trip.wasCancelled ? RideTheme.textSecondary : RideTheme.textPrimary)
        }
        .rideCard(padding: 12)
    }
}

/// Receipt-style detail for a past trip.
struct TripDetailSheet: View {
    let trip: TripRecord
    @Environment(TripStore.self) private var tripStore
    @Environment(\.dismiss) private var dismiss
    @State private var rating: Int

    init(trip: TripRecord) {
        self.trip = trip
        _rating = State(initialValue: trip.rating ?? 0)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 6) {
                    Text(trip.wasCancelled ? "Cancelled trip" : trip.formattedFare)
                        .font(.system(size: 34, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(RideTheme.textPrimary)
                    Text(trip.date.formatted(date: .abbreviated, time: .shortened))
                        .font(.subheadline)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                .padding(.top, 22)

                RouteSummaryRow(pickupName: trip.pickupName, dropoffName: trip.dropoffName)
                    .rideCard()

                VStack(spacing: 12) {
                    detailRow("Ride class", trip.className)
                    detailRow("Driver", trip.counterpartName)
                    detailRow("Payment", "Visa •• 4242")
                    detailRow("Receipt", "rcpt_\(trip.id.suffix(6))")
                }
                .rideCard()

                if !trip.wasCancelled {
                    VStack(spacing: 12) {
                        Text("Rate \(trip.counterpartName)")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(RideTheme.textPrimary)
                        HStack(spacing: 12) {
                            ForEach(1...5, id: \.self) { star in
                                Button {
                                    rating = star
                                    tripStore.rate(tripID: trip.id, stars: star)
                                    Haptics.success()
                                } label: {
                                    Image(systemName: rating >= star ? "star.fill" : "star")
                                        .font(.system(size: 24))
                                        .foregroundStyle(rating >= star ? RideTheme.accentWarm : RideTheme.textSecondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("\(star) star\(star == 1 ? "" : "s")")
                            }
                        }
                    }
                    .rideCard()
                }

                Button("Close") { dismiss() }
                    .buttonStyle(RideSecondaryButtonStyle())
            }
            .padding(.horizontal, RideTheme.margin)
            .padding(.bottom, 28)
        }
        .background(RideTheme.canvas)
        .preferredColorScheme(.dark)
    }

    private func detailRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(RideTheme.textSecondary)
            Spacer()
            Text(value)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(RideTheme.textPrimary)
        }
    }
}
