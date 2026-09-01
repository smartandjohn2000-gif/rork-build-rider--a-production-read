import SwiftUI
import Charts

/// Driver earnings: weekly total, daily bars, ratings and recent trips.
struct DriverEarningsView: View {
    @Environment(TripStore.self) private var tripStore
    @Environment(DriverStore.self) private var driverStore

    @State private var showsCashOutConfirmation: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                chartCard
                metricsRow

                SectionHeader(title: "Recent trips")
                    .padding(.top, 4)

                if tripStore.driverTrips.isEmpty {
                    EmptyStateView(
                        symbol: "eurosign.circle",
                        title: "No earnings yet",
                        message: "Complete your first trip and it will show up here."
                    )
                } else {
                    ForEach(tripStore.driverTrips.prefix(8)) { trip in
                        tripRow(trip)
                    }
                }

                cashOutCard
            }
            .padding(.horizontal, RideTheme.margin)
            .padding(.bottom, 28)
        }
        .background(RideTheme.canvas)
        .navigationTitle("Earnings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(RideTheme.canvas, for: .navigationBar)
        .preferredColorScheme(.dark)
        .alert("Cash out requested", isPresented: $showsCashOutConfirmation) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Your balance will land in your bank account within one business day.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tripStore.weeklyTotal.formatted(.currency(code: "EUR").precision(.fractionLength(2))))
                .font(.system(size: 40, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(RideTheme.accentWarm)

            HStack(spacing: 6) {
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(RideTheme.success)
                Text("This week · up 12% vs last week")
                    .font(.subheadline)
                    .foregroundStyle(RideTheme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .rideCard()
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Daily earnings")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(RideTheme.textPrimary)

            Chart(tripStore.weeklyEarnings()) { day in
                BarMark(
                    x: .value("Day", day.dayLabel),
                    y: .value("Earnings", day.amount)
                )
                .foregroundStyle(RideTheme.accent.gradient)
                .cornerRadius(6)
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine().foregroundStyle(RideTheme.hairline)
                    AxisValueLabel {
                        if let amount = value.as(Double.self) {
                            Text("€\(Int(amount))")
                                .font(.caption2)
                                .foregroundStyle(RideTheme.textSecondary)
                        }
                    }
                }
            }
            .chartXAxis {
                AxisMarks { value in
                    AxisValueLabel {
                        if let label = value.as(String.self) {
                            Text(label)
                                .font(.caption2)
                                .foregroundStyle(RideTheme.textSecondary)
                        }
                    }
                }
            }
            .frame(height: 170)
        }
        .rideCard()
    }

    private var metricsRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 5) {
                    Image(systemName: "star.fill")
                        .foregroundStyle(RideTheme.accentWarm)
                    Text(tripStore.driverAverageRating.formatted(.number.precision(.fractionLength(2))))
                        .font(.title3.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(RideTheme.textPrimary)
                }
                Text("Average rating")
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .rideCard(padding: 14)

            VStack(alignment: .leading, spacing: 6) {
                Text("\(tripStore.driverTrips.filter { !$0.wasCancelled }.count)")
                    .font(.title3.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.textPrimary)
                Text("Trips completed")
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .rideCard(padding: 14)
        }
    }

    private func tripRow(_ trip: TripRecord) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("\(trip.pickupName) → \(trip.dropoffName)")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(RideTheme.textPrimary)
                    .lineLimit(1)
                Text(trip.date.formatted(.dateTime.weekday(.abbreviated).hour().minute()))
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            Spacer(minLength: 0)
            Text(trip.formattedFare)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(RideTheme.textPrimary)
        }
        .rideCard(padding: 14)
    }

    private var cashOutCard: some View {
        VStack(spacing: 10) {
            Button("Cash out \(tripStore.weeklyTotal.formatted(.currency(code: "EUR").precision(.fractionLength(2))))") {
                Haptics.success()
                showsCashOutConfirmation = true
            }
            .buttonStyle(RidePrimaryButtonStyle())

            Text("Next automatic payout: Friday · paid to your linked bank account")
                .font(.caption)
                .foregroundStyle(RideTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 6)
    }
}
