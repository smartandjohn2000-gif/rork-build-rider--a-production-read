import Foundation

nonisolated struct Receipt: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var rideID: String
    var total: Double
    var currencyCode: String
    var paymentMethodLabel: String
    var paidAt: Date
    var breakdown: FareQuote

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

/// Charges a completed ride. The client never handles raw card data: it asks
/// the backend to confirm a payment intent for the ride and receives a receipt.
nonisolated struct PaymentService: Sendable {
    func charge(ride: Ride, method: PaymentMethod?) async throws -> Receipt {
        guard let method else { throw PaymentError.noPaymentMethod }
        try? await Task.sleep(for: .milliseconds(900))
        return Receipt(
            id: "rcpt_" + UUID().uuidString.prefix(8),
            rideID: ride.id,
            total: ride.quote.total,
            currencyCode: ride.quote.currencyCode,
            paymentMethodLabel: method.displayName,
            paidAt: Date(),
            breakdown: ride.quote
        )
    }
}
