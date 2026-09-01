import Foundation

/// Which side of the marketplace the person is using right now.
nonisolated enum AppRole: String, Codable, CaseIterable, Sendable {
    case rider
    case driver

    var title: String {
        switch self {
        case .rider: "Rider"
        case .driver: "Driver"
        }
    }

    var ctaTitle: String {
        switch self {
        case .rider: "Continue as rider"
        case .driver: "Continue as driver"
        }
    }
}

/// The signed-in account. A single account can hold both profiles.
nonisolated struct UserAccount: Codable, Hashable, Sendable {
    var id: String
    var fullName: String
    var email: String
    var phone: String
    var role: AppRole
    var rating: Double
    var memberSince: Date
    var avatarURL: URL?
}

/// Verification state for the driver side, mirroring admin approval.
nonisolated enum DriverApprovalStatus: String, Codable, Sendable {
    case notSubmitted
    case pending
    case approved
    case rejected

    var title: String {
        switch self {
        case .notSubmitted: "Documents required"
        case .pending: "Awaiting approval"
        case .approved: "Approved to drive"
        case .rejected: "Action required"
        }
    }

    var detail: String {
        switch self {
        case .notSubmitted: "Upload your licence, insurance and vehicle details to start driving."
        case .pending: "Our team is reviewing your documents. This usually takes under 24 hours."
        case .approved: "You're verified. Go online whenever you're ready to earn."
        case .rejected: "One of your documents couldn't be verified. Please re-upload."
        }
    }
}

/// A document the driver must supply before admin approval.
nonisolated struct DriverDocument: Identifiable, Codable, Hashable, Sendable {
    var id: String
    var title: String
    var detail: String
    var symbol: String
    var isUploaded: Bool

    static let required: [DriverDocument] = [
        DriverDocument(id: "licence", title: "Driving licence", detail: "Front and back, valid for at least 3 months", symbol: "person.text.rectangle.fill", isUploaded: true),
        DriverDocument(id: "insurance", title: "Vehicle insurance", detail: "Certificate showing commercial cover", symbol: "checkmark.shield.fill", isUploaded: true),
        DriverDocument(id: "registration", title: "Vehicle registration", detail: "Matching the plate on your profile", symbol: "car.rear.fill", isUploaded: true),
        DriverDocument(id: "profile", title: "Profile photo", detail: "Clear photo of your face, no sunglasses", symbol: "camera.fill", isUploaded: false)
    ]
}

/// The driver's own vehicle record.
nonisolated struct Vehicle: Codable, Hashable, Sendable {
    var make: String
    var model: String
    var year: Int
    var color: String
    var plate: String

    var displayName: String { "\(color) \(make) \(model)" }
}
