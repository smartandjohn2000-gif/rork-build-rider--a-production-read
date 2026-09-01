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

/// Owns authentication, the active role and account-level preferences.
/// The auth calls are stubbed against a local identity so the app is fully
/// usable; swapping in a real provider only touches `signIn`/`signUp`.
@Observable
@MainActor
final class SessionStore {
    private(set) var hasCompletedOnboarding: Bool = false
    private(set) var account: UserAccount?
    var role: AppRole = .rider
    private(set) var savedPlaces: [Place] = Place.defaultSaved
    private(set) var paymentMethods: [PaymentMethod] = PaymentMethod.defaults
    var notificationsEnabled: Bool = true

    private(set) var isAuthenticating: Bool = false
    private(set) var authError: String?

    var isSignedIn: Bool { account != nil }

    var defaultPaymentMethod: PaymentMethod? {
        paymentMethods.first(where: \.isDefault) ?? paymentMethods.first
    }

    init() {
        restore()
    }

    // MARK: - Onboarding

    func completeOnboarding(role: AppRole) {
        self.role = role
        hasCompletedOnboarding = true
        persist()
    }

    func switchRole(to role: AppRole) {
        guard role != self.role else { return }
        self.role = role
        persist()
    }

    // MARK: - Authentication

    func signIn(email: String, password: String) async {
        guard isValid(email: email), password.count >= 6 else {
            authError = "Enter a valid email and a password of at least 6 characters."
            return
        }
        isAuthenticating = true
        authError = nil
        try? await Task.sleep(for: .milliseconds(700))
        account = UserAccount(
            id: UUID().uuidString,
            fullName: Self.displayName(from: email),
            email: email,
            phone: "+33 6 12 34 56 78",
            role: role,
            rating: 4.92,
            memberSince: Date(timeIntervalSinceNow: -60 * 60 * 24 * 214),
            avatarURL: nil
        )
        isAuthenticating = false
        persist()
    }

    func signUp(name: String, email: String, phone: String, password: String) async {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            authError = "Please enter your name."
            return
        }
        guard isValid(email: email) else {
            authError = "That email address doesn't look right."
            return
        }
        guard password.count >= 6 else {
            authError = "Choose a password of at least 6 characters."
            return
        }
        isAuthenticating = true
        authError = nil
        try? await Task.sleep(for: .milliseconds(800))
        account = UserAccount(
            id: UUID().uuidString,
            fullName: name,
            email: email,
            phone: phone.isEmpty ? "+33 6 12 34 56 78" : phone,
            role: role,
            rating: 5.0,
            memberSince: Date(),
            avatarURL: nil
        )
        isAuthenticating = false
        persist()
    }

    func signOut() {
        account = nil
        hasCompletedOnboarding = false
        authError = nil
        persist()
    }

    func clearAuthError() {
        authError = nil
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

    private func isValid(email: String) -> Bool {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        return trimmed.contains("@") && trimmed.contains(".") && trimmed.count > 5
    }

    private static func displayName(from email: String) -> String {
        let handle = email.split(separator: "@").first.map(String.init) ?? "Rider"
        return handle
            .replacingOccurrences(of: ".", with: " ")
            .split(separator: " ")
            .map { $0.capitalized }
            .joined(separator: " ")
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
    static let defaults: [PaymentMethod] = [
        PaymentMethod(id: "pm_visa", brand: "Visa", last4: "4242", isDefault: true, supportsWallet: true),
        PaymentMethod(id: "pm_mc", brand: "Mastercard", last4: "8210", isDefault: false, supportsWallet: false)
    ]
}
