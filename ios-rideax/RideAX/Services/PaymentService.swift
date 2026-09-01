import Foundation

nonisolated struct Receipt: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var rideID: String
    var total: Double
    var currencyCode: String
    var paymentMethodLabel: String
    var paidAt: Date
    var breakdown: FareQuote
    /// Stripe's hosted receipt, available once the webhook has confirmed the charge.
    var receiptURL: URL?
    /// False when the charge was settled locally because card payments are not
    /// configured yet — the UI labels those honestly instead of faking success.
    var isLiveCharge: Bool = false

    var formattedTotal: String {
        total.formatted(.currency(code: currencyCode).precision(.fractionLength(2)))
    }
}

nonisolated enum PaymentError: LocalizedError, Sendable {
    case noPaymentMethod
    case declined
    case network

    var errorDescription: String? {
        switch self {
        case .noPaymentMethod: "Add a payment method to complete this trip."
        case .declined: "Your card was declined. Try another payment method."
        case .network: "We couldn't reach the payment service. Please try again."
        }
    }
}

/// Local settlement used only when Stripe isn't configured for the account.
///
/// Real charges go through `StripeService`: the app asks the backend for a
/// PaymentIntent priced from the ride row, Stripe's sheet collects the card,
/// and Stripe's webhook is what marks the ride paid. This path exists so a
/// trip can still be closed out when card payments aren't switched on.
nonisolated struct PaymentService: Sendable {
    func settleLocally(ride: Ride, method: PaymentMethod?) async throws -> Receipt {
        try? await Task.sleep(for: .milliseconds(600))
        return Receipt(
            id: "rcpt_" + UUID().uuidString.prefix(8),
            rideID: ride.id,
            total: ride.quote.total,
            currencyCode: ride.quote.currencyCode,
            paymentMethodLabel: method?.displayName ?? "Pending card payment",
            paidAt: Date(),
            breakdown: ride.quote,
            receiptURL: nil,
            isLiveCharge: false
        )
    }
}
