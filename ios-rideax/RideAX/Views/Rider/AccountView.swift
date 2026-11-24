import SwiftUI

/// Rider account: profile, payment, saved places, notifications and role switch.
struct AccountView: View {
    @Environment(SessionStore.self) private var session
    @Environment(TripStore.self) private var tripStore

    @State private var showsSignOutDialog: Bool = false
    @State private var showsDeleteDialog: Bool = false
    @State private var isDeletingAccount: Bool = false
    @State private var deleteError: String?

    var body: some View {
        @Bindable var session = session

        return NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    profileCard

                    VStack(spacing: 10) {
                        SectionHeader(title: "Payment")
                        ForEach(session.paymentMethods) { method in
                            Button {
                                session.makeDefault(method)
                                Haptics.tap()
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "creditcard.fill")
                                        .foregroundStyle(RideTheme.accent)
                                    Text(method.displayName)
                                        .font(.subheadline)
                                        .foregroundStyle(RideTheme.textPrimary)
                                    Spacer()
                                    if method.isDefault {
                                        Text("Default")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(RideTheme.accent)
                                    }
                                }
                                .rideCard(padding: 14)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    VStack(spacing: 10) {
                        SectionHeader(title: "Saved places")
                        ForEach(session.savedPlaces) { place in
                            HStack(spacing: 12) {
                                Image(systemName: place.symbol)
                                    .foregroundStyle(RideTheme.accent)
                                    .frame(width: 26)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(place.name)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(RideTheme.textPrimary)
                                    Text(place.subtitle)
                                        .font(.caption)
                                        .foregroundStyle(RideTheme.textSecondary)
                                }
                                Spacer()
                            }
                            .rideCard(padding: 14)
                        }
                    }

                    VStack(spacing: 10) {
                        SectionHeader(title: "Preferences")

                        Toggle(isOn: $session.notificationsEnabled) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Push notifications")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(RideTheme.textPrimary)
                                Text("Driver updates, receipts and offers")
                                    .font(.caption)
                                    .foregroundStyle(RideTheme.textSecondary)
                            }
                        }
                        .tint(RideTheme.accent)
                        .rideCard(padding: 14)

                        NavigationLink {
                            SupportView()
                        } label: {
                            navigationRow(symbol: "questionmark.circle.fill", title: "Help & support", detail: "Trip issues, refunds and safety")
                        }
                        .buttonStyle(.plain)
                    }

                    roleSwitchCard

                    Button("Sign out") {
                        showsSignOutDialog = true
                    }
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.danger))
                    .padding(.top, 4)

                    Button {
                        showsDeleteDialog = true
                    } label: {
                        if isDeletingAccount {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Delete account")
                        }
                    }
                    .buttonStyle(RideSecondaryButtonStyle(tint: RideTheme.textSecondary))
                    .disabled(isDeletingAccount)
                }
                .padding(.horizontal, RideTheme.margin)
                .padding(.bottom, 28)
            }
            .background(RideTheme.canvas)
            .navigationTitle("Account")
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
            .confirmationDialog("Sign out of RideAX?", isPresented: $showsSignOutDialog, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { session.signOut() }
                Button("Stay signed in", role: .cancel) {}
            }
            .confirmationDialog(
                "Delete your account? This permanently removes your rides, receipts and driver profile. This cannot be undone.",
                isPresented: $showsDeleteDialog,
                titleVisibility: .visible
            ) {
                Button("Delete account", role: .destructive) {
                    isDeletingAccount = true
                    deleteError = nil
                    Task {
                        defer { isDeletingAccount = false }
                        do {
                            try await session.deleteAccount()
                        } catch {
                            deleteError = "Account deletion failed. Please try again."
                        }
                    }
                }
                Button("Keep my account", role: .cancel) {}
            }
            .alert("Account deletion failed", isPresented: .init(get: { deleteError != nil }, set: { if !$0 { deleteError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(deleteError ?? "")
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
                Text(initials)
                    .font(.title3.bold())
                    .foregroundStyle(RideTheme.accent)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(session.account?.fullName ?? "Guest rider")
                    .font(.headline)
                    .foregroundStyle(RideTheme.textPrimary)
                Text(session.account?.email ?? "Not signed in")
                    .font(.footnote)
                    .foregroundStyle(RideTheme.textSecondary)
                RatingLabel(rating: session.account?.rating ?? 5.0, detail: "\(tripStore.riderTrips.count) trips")
            }

            Spacer(minLength: 0)
        }
        .rideCard()
    }

    private var roleSwitchCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "steeringwheel")
                    .font(.title3)
                    .foregroundStyle(RideTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Drive with RideAX")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                    Text("Switch to driver mode and earn on your schedule.")
                        .font(.caption)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }

            Button("Switch to driver mode") {
                Haptics.tap()
                session.switchRole(to: .driver)
            }
            .buttonStyle(RidePrimaryButtonStyle())
        }
        .rideCard()
    }

    private func navigationRow(symbol: String, title: String, detail: String) -> some View {
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

    private var initials: String {
        let name = session.account?.fullName ?? "RideAX"
        let parts = name.split(separator: " ").prefix(2)
        return parts.map { String($0.prefix(1)).uppercased() }.joined()
    }
}

/// Support topics available to both riders and drivers.
struct SupportView: View {
    private let topics: [(String, String, String)] = [
        ("exclamationmark.bubble.fill", "Report a trip issue", "Wrong route, driver behaviour or lost item"),
        ("arrow.uturn.backward.circle.fill", "Request a refund", "We review refund requests within 48 hours"),
        ("creditcard.fill", "Payment problem", "Failed charges, duplicate payments, receipts"),
        ("shield.lefthalf.filled", "Safety concern", "Report an incident to our safety team"),
        ("doc.text.fill", "Legal & privacy", "Terms of service and privacy policy")
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                ForEach(topics, id: \.1) { topic in
                    Button {
                        Haptics.tap()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: topic.0)
                                .foregroundStyle(RideTheme.accent)
                                .frame(width: 26)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(topic.1)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(RideTheme.textPrimary)
                                Text(topic.2)
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
                    .buttonStyle(.plain)
                }
            }
            .padding(RideTheme.margin)
        }
        .background(RideTheme.canvas)
        .navigationTitle("Help & support")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(RideTheme.canvas, for: .navigationBar)
    }
}
