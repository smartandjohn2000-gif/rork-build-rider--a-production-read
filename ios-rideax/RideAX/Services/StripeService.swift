import SwiftUI
import StripePaymentSheet
import Supabase
import Functions

nonisolated struct StripeIntentRequest: Encodable, Sendable {
    let rideId: String
    let stripeApiVersion: String
}

nonisolated struct StripeIntentResponse: Codable, Sendable {
    let paymentId: String
    let paymentIntentClientSecret: String
    let ephemeralKeySecret: String?
    let customerId: String?
    let publishableKey: String
    let amountCents: Int
    let currency: String
}

nonisolated enum StripeOutcome: Sendable {
    case paid
    case cancelled
    case failed(String)
}

nonisolated enum StripeError: LocalizedError, Sendable {
    case notConfigured
    case alreadyPaid
    case noPresenter
    case server(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: "Card payments aren't switched on yet for this account."
        case .alreadyPaid: "This trip has already been paid."
        case .noPresenter: "Couldn't open the payment sheet. Please try again."
        case .server(let message): message
        }
    }
}

/// Presents Stripe's PaymentSheet for a completed ride.
///
/// The app never sees card data and never decides the amount: it asks the
/// backend for a PaymentIntent (priced from the ride row in Postgres), shows
/// Stripe's native sheet, and Stripe's webhook is what ultimately marks the
/// ride paid. Saved cards come back automatically through the rider's Stripe
/// customer record.
@Observable
@MainActor
final class StripeService {
    static let shared = StripeService()

    private(set) var isProcessing: Bool = false
    /// Retained while the sheet is on screen — dropping it cancels the flow.
    private var paymentSheet: PaymentSheet?

    private init() {}

    func payForRide(remoteRideID: UUID) async throws -> StripeOutcome {
        isProcessing = true
        defer { isProcessing = false }

        let intent = try await requestIntent(rideID: remoteRideID)
        STPAPIClient.shared.publishableKey = intent.publishableKey

        let sheet = PaymentSheet(
            paymentIntentClientSecret: intent.paymentIntentClientSecret,
            configuration: configuration(for: intent)
        )
        paymentSheet = sheet
        defer { paymentSheet = nil }

        guard let presenter = Self.topViewController() else { throw StripeError.noPresenter }

        let result: PaymentSheetResult = await withCheckedContinuation { continuation in
            sheet.present(from: presenter) { outcome in
                continuation.resume(returning: outcome)
            }
        }

        switch result {
        case .completed:
            Haptics.success()
            return .paid
        case .canceled:
            return .cancelled
        case .failed(let error):
            Haptics.warning()
            return .failed(error.localizedDescription)
        }
    }

    // MARK: - Backend

    private func requestIntent(rideID: UUID) async throws -> StripeIntentResponse {
        do {
            return try await Backend.client.functions.invoke(
                "create-ride-payment",
                options: .init(
                    body: StripeIntentRequest(
                        rideId: rideID.uuidString,
                        stripeApiVersion: STPAPIClient.apiVersion
                    )
                )
            )
        } catch let error as FunctionsError {
            throw Self.mapped(error)
        }
    }

    /// Translates the edge function's error codes into something the ride
    /// screen can act on — "not configured" falls back, the rest are shown.
    private static func mapped(_ error: FunctionsError) -> Error {
        guard case .httpError(let code, let data) = error else { return error }
        let body = (try? JSONDecoder().decode([String: String].self, from: data))?["error"]
        switch (code, body) {
        case (503, _), (_, "stripe_not_configured"): return StripeError.notConfigured
        case (409, _), (_, "already_paid"): return StripeError.alreadyPaid
        case (401, _): return StripeError.server("Please sign in again to pay for this trip.")
        default: return StripeError.server("Payment couldn't be started (\(code)).")
        }
    }

    // MARK: - Presentation

    /// RideAX's own navy/orange skin on Stripe's native sheet, so checkout
    /// doesn't look like it belongs to a different app.
    private func configuration(for intent: StripeIntentResponse) -> PaymentSheet.Configuration {
        var configuration = PaymentSheet.Configuration()
        configuration.merchantDisplayName = "RideAX"
        configuration.style = .alwaysDark
        configuration.returnURL = "rork-\(Config.EXPO_PUBLIC_PROJECT_ID)://stripe-redirect"
        configuration.allowsDelayedPaymentMethods = false

        if let customerId = intent.customerId, let ephemeralKeySecret = intent.ephemeralKeySecret {
            configuration.customer = .init(id: customerId, ephemeralKeySecret: ephemeralKeySecret)
        }

        var appearance = PaymentSheet.Appearance()
        appearance.cornerRadius = RideTheme.controlRadius
        appearance.colors.background = UIColor(RideTheme.canvas)
        appearance.colors.componentBackground = UIColor(RideTheme.surface)
        appearance.colors.componentBorder = UIColor(RideTheme.surfaceRaised)
        appearance.colors.componentDivider = UIColor(RideTheme.surfaceRaised)
        appearance.colors.primary = UIColor(RideTheme.accent)
        appearance.colors.text = UIColor(RideTheme.textPrimary)
        appearance.colors.textSecondary = UIColor(RideTheme.textSecondary)
        appearance.colors.componentText = UIColor(RideTheme.textPrimary)
        appearance.colors.componentPlaceholderText = UIColor(RideTheme.textSecondary)
        appearance.colors.icon = UIColor(RideTheme.textSecondary)
        appearance.colors.danger = UIColor(RideTheme.danger)
        appearance.primaryButton.backgroundColor = UIColor(RideTheme.accent)
        appearance.primaryButton.textColor = UIColor(Color(hex: 0x14203A))
        appearance.primaryButton.cornerRadius = 27
        configuration.appearance = appearance

        return configuration
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first

        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
