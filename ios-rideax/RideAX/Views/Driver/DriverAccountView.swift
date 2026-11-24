import SwiftUI

/// Driver account sheet: profile, documents, earnings and role switch.
struct DriverAccountView: View {
    @Environment(SessionStore.self) private var session
    @Environment(DriverStore.self) private var driverStore
    @Environment(TripStore.self) private var tripStore
    @Environment(\.dismiss) private var dismiss

    @State private var showsSignOutDialog: Bool = false
    @State private var showsDeleteDialog: Bool = false
    @State private var deleteError: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    profileCard

                    HStack(spacing: 12) {
                        StatTile(symbol: "checkmark.circle", value: "\(driverStore.acceptanceRate)%", caption: "acceptance", tint: RideTheme.accent)
                        Divider().frame(height: 32).overlay(RideTheme.hairline)
                        StatTile(symbol: "car.fill", value: "\(tripStore.driverTrips.count)", caption: "trips", tint: RideTheme.accent)
                        Divider().frame(height: 32).overlay(RideTheme.hairline)
                        StatTile(
                            symbol: "eurosign.circle.fill",
                            value: tripStore.weeklyTotal.formatted(.currency(code: "EUR").precision(.fractionLength(0))),
                            caption: "this week",
                            tint: RideTheme.accentWarm
                        )
                    }
                    .rideCard()

                    VStack(spacing: 10) {
                        SectionHeader(title: "Driving")

                        NavigationLink {
                            DriverEarningsView()
                        } label: {
                            row(symbol: "chart.bar.fill", title: "Earnings & payouts", detail: "Weekly totals, trips and cash out")
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            DriverDocumentsView()
                        } label: {
                            row(symbol: "doc.text.fill", title: "Documents & vehicle", detail: driverStore.approvalStatus.title)
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            SupportView()
                        } label: {
                            row(symbol: "questionmark.circle.fill", title: "Help & support", detail: "Trip issues, payouts and safety")
                        }
                        .buttonStyle(.plain)
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            Image(systemName: "figure.wave")
                                .font(.title3)
                                .foregroundStyle(RideTheme.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Need a ride yourself?")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(RideTheme.textPrimary)
                                Text("Switch to rider mode any time — same account.")
                                    .font(.caption)
                                    .foregroundStyle(RideTheme.textSecondary)
                            }
                            Spacer(minLength: 0)
                        }

                        Button("Switch to rider mode") {
                            Haptics.tap()
                            driverStore.setOnline(false)
                            session.switchRole(to: .rider)
                            dismiss()
                        }
                        .buttonStyle(RidePrimaryButtonStyle())
                    }
                    .rideCard()

                    Button("Sign out") {
                        showsSignOutDialog = true
                    }
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.danger))

                    Button("Delete account") {
                        showsDeleteDialog = true
                    }
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.textSecondary))
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.bottom, 28)
            }
            .background(RideTheme.canvas)
            .navigationTitle("Driver account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(RideTheme.accent)
                }
            }
            .confirmationDialog("Sign out of RideAX?", isPresented: $showsSignOutDialog, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) {
                    driverStore.setOnline(false)
                    session.signOut()
                }
                Button("Stay signed in", role: .cancel) {}
            }
            .confirmationDialog(
                "Delete your account? This permanently removes your rides, receipts and driver profile. This cannot be undone.",
                isPresented: $showsDeleteDialog,
                titleVisibility: .visible
            ) {
                Button("Delete account", role: .destructive) {
                    driverStore.setOnline(false)
                    Task {
                        do {
                            try await session.deleteAccount()
                        } catch {
                            deleteError = true
                        }
                    }
                }
                Button("Keep my account", role: .cancel) {}
            }
            .alert("Account deletion failed", isPresented: $deleteError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Please try again in a moment.")
            }
        }
        .preferredColorScheme(.dark)
    }

    private var profileCard: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(RideTheme.accent.opacity(0.16))
                    .frame(width: 58, height: 58)
                Image(systemName: "steeringwheel")
                    .font(.title2)
                    .foregroundStyle(RideTheme.accent)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(session.account?.fullName ?? "RideAX driver")
                    .font(.headline)
                    .foregroundStyle(RideTheme.textPrimary)
                Text(driverStore.vehicle.displayName + " · " + driverStore.vehicle.plate)
                    .font(.footnote)
                    .foregroundStyle(RideTheme.textSecondary)
                RatingLabel(rating: tripStore.driverAverageRating, detail: "\(tripStore.driverTrips.count) trips")
            }

            Spacer(minLength: 0)
        }
        .rideCard()
    }

    private func row(symbol: String, title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(RideTheme.accent)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(RideTheme.textPrimary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(RideTheme.textSecondary)
        }
        .rideCard(padding: 14)
    }
}
