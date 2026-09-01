import Foundation
import Observation

nonisolated struct SessionSnapshot: Codable, Sendable {
    var hasCompletedOnboarding: Bool
    var account: UserAccount?
    var role: AppRole
    var savedPlaces: [Place]
    var paymentMethods: [PaymentMethod]
    var notificationsEnabled: Bool
}

/// Owns the signed-in identity, the active role and account preferences.
///
/// Identity comes from Rork Auth (Apple / Google). The resulting JWT is what
/// Postgres row-level security reads, so the profile row here and the rides and
/// payments rows elsewhere all hang off the same user id. Preferences stay on
/// device; anything that must survive a reinstall lives in Postgres.
@Observable
@MainActor
final class SessionStore {
    private(set) var hasCompletedOnboarding: Bool = false
    private(set) var account: UserAccount?
    var role: AppRole = .rider
    private(set) var savedPlaces: [Place] = Place.defaultSaved
    private(set) var paymentMethods: [PaymentMethod] = PaymentMethod.defaults
    var notificationsEnabled: Bool = true

    /// True while a stored session is being restored on launch.
    private(set) var isRestoring: Bool = true
    private(set) var isAuthenticating: Bool = false
    private(set) var authError: String?

    private let backend = BackendService()
    private let auth = AuthManager.shared

    var isSignedIn: Bool { account != nil }

    var defaultPaymentMethod: PaymentMethod? {
        paymentMethods.first(where: \.isDefault) ?? paymentMethods.first
    }

    init() {
        restore()
    }

    // MARK: - Session

    /// Restores tokens on launch and re-syncs the profile row.
    func bootstrap() async {
        await auth.restore()
        if let user = auth.user {
            await adopt(user)
        } else {
            // Tokens are gone (expired refresh, reinstall, sign-out elsewhere).
            account = nil
            persist()
        }
        isRestoring = false
    }

    func signIn(with provider: AuthProvider) async {
        isAuthenticating = true
        authError = nil
        defer { isAuthenticating = false }

        await auth.signIn(provider: provider)

        guard let user = auth.user else {
            authError = auth.errorMessage
            return
        }
        await adopt(user)
    }

    func signOut() {
        auth.signOut()
        account = nil
        hasCompletedOnboarding = false
        authError = nil
        persist()
    }

    func clearAuthError() {
        authError = nil
        auth.clearError()
    }

    /// Mirrors the authenticated user into the local account, then reconciles
    /// it with the `profiles` row. The local copy lands first so the UI never
    /// waits on the network to show who is signed in.
    private func adopt(_ user: AuthManager.AuthUser) async {
        let fallbackName = user.name ?? Self.displayName(from: user.email)
        var resolved = UserAccount(
            id: user.id,
            fullName: account?.id == user.id ? (account?.fullName ?? fallbackName) : fallbackName,
            email: user.email,
            phone: account?.id == user.id ? (account?.phone ?? "") : "",
            role: role,
            rating: account?.id == user.id ? (account?.rating ?? 5.0) : 5.0,
            memberSince: account?.id == user.id ? (account?.memberSince ?? Date()) : Date(),
            avatarURL: user.picture.flatMap { URL(string: $0) }
        )
        account = resolved
        persist()

        guard Backend.isConfigured else { return }

        do {
            let profile = try await backend.syncProfile(
                id: user.id,
                email: user.email,
                fullName: resolved.fullName,
                phone: resolved.phone.isEmpty ? nil : resolved.phone,
                avatarURL: user.picture,
                role: role
            )

            if let profile {
                resolved.fullName = profile.fullName ?? resolved.fullName
                resolved.phone = profile.phone ?? resolved.phone
                resolved.rating = profile.rating
                resolved.memberSince = profile.createdAt ?? resolved.memberSince
                account = resolved
                persist()
            }

            if role == .driver {
                try await backend.ensureDriverProfile(id: user.id)
            }
        } catch {
            // Never block sign-in on a sync failure — the app works offline and
            // the profile is re-synced on the next launch.
            print("RideAX: profile sync failed — \(error.localizedDescription)")
        }
    }

    // MARK: - Onboarding & role

    func completeOnboarding(role: AppRole) {
        self.role = role
        hasCompletedOnboarding = true
        account?.role = role
        persist()
        pushRole(role)
    }

    func switchRole(to role: AppRole) {
        guard role != self.role else { return }
        self.role = role
        account?.role = role
        persist()
        pushRole(role)
    }

    /// Keeps the server-side role in step with the shell the person is using.
    private func pushRole(_ role: AppRole) {
        guard Backend.isConfigured, let id = account?.id else { return }
        Task { [backend] in
            do {
                try await backend.updateRole(id: id, role: role)
                if role == .driver {
                    try await backend.ensureDriverProfile(id: id)
                }
            } catch {
                print("RideAX: role sync failed — \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Preferences

    func addSavedPlace(_ place: Place) {
        guard !savedPlaces.contains(where: { $0.id == place.id }) else { return }
        savedPlaces.append(place)
        persist()
    }

    func removeSavedPlace(_ place: Place) {
        savedPlaces.removeAll { $0.id == place.id }
        persist()
    }

    func makeDefault(_ method: PaymentMethod) {
        paymentMethods = paymentMethods.map { current in
            var copy = current
            copy.isDefault = current.id == method.id
            return copy
        }
        persist()
    }

    // MARK: - Persistence

    private func persist() {
        Persistence.save(
            SessionSnapshot(
                hasCompletedOnboarding: hasCompletedOnboarding,
                account: account,
                role: role,
                savedPlaces: savedPlaces,
                paymentMethods: paymentMethods,
                notificationsEnabled: notificationsEnabled
            ),
            key: Persistence.Key.session
        )
    }

    private func restore() {
        guard let snapshot = Persistence.load(SessionSnapshot.self, key: Persistence.Key.session) else { return }
        hasCompletedOnboarding = snapshot.hasCompletedOnboarding
        account = snapshot.account
        role = snapshot.role
        savedPlaces = snapshot.savedPlaces.isEmpty ? Place.defaultSaved : snapshot.savedPlaces
        paymentMethods = snapshot.paymentMethods.isEmpty ? PaymentMethod.defaults : snapshot.paymentMethods
        notificationsEnabled = snapshot.notificationsEnabled
    }

    private static func displayName(from email: String) -> String {
        let handle = email.split(separator: "@").first.map(String.init) ?? "Rider"
        let cleaned = handle
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")
        let name = cleaned
            .split(separator: " ")
            .map { $0.capitalized }
            .joined(separator: " ")
        return name.isEmpty ? "Rider" : name
    }
}

extension Place {
    static let defaultSaved: [Place] = [
        Place(id: "home", name: "Home", subtitle: "Rue Oberkampf", point: GeoPoint(latitude: 48.8656, longitude: 2.3712), symbol: "house.fill"),
        Place(id: "work", name: "Work", subtitle: "La Défense", point: GeoPoint(latitude: 48.8918, longitude: 2.2380), symbol: "briefcase.fill"),
        Place(id: "airport", name: "CDG Airport", subtitle: "Roissy-en-France", point: GeoPoint(latitude: 49.0097, longitude: 2.5479), symbol: "airplane")
    ]

    static let gareDuNord = Place(
        id: "gare-du-nord",
        name: "Gare du Nord",
        subtitle: "18 Rue de Dunkerque, Paris",
        point: GeoPoint(latitude: 48.8809, longitude: 2.3553),
        symbol: "tram.fill"
    )
}

extension PaymentMethod {
    /// Placeholder rows shown before the rider has paid once. Real cards are
    /// held by Stripe and offered inside its sheet — RideAX never stores them.
    static let defaults: [PaymentMethod] = [
        PaymentMethod(id: "pm_stripe", brand: "Card via Stripe", last4: "", isDefault: true, supportsWallet: false)
    ]
}
