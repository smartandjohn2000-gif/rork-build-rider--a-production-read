import SwiftUI

/// Central design tokens for RideAX. Derived from the brand logo:
/// deep navy canvas with an orange highway accent.
enum RideTheme {
    static let canvas = Color(hex: 0x0D1830)
    static let canvasDeep = Color(hex: 0x070E1D)
    static let surface = Color(hex: 0x172440)
    static let surfaceRaised = Color(hex: 0x1E2C4C)
    static let hairline = Color.white.opacity(0.08)

    static let textPrimary = Color(hex: 0xEDF1F8)
    static let textSecondary = Color(hex: 0x93A2BC)

    static let accent = Color(hex: 0xF7911E)
    static let accentWarm = Color(hex: 0xFFB547)
    static let success = Color(hex: 0x2FBE6F)
    static let danger = Color(hex: 0xE5484D)

    static let cardRadius: CGFloat = 18
    static let controlRadius: CGFloat = 14
    static let margin: CGFloat = 16

    /// Atmospheric background used behind onboarding and non-map screens.
    static var backdrop: LinearGradient {
        LinearGradient(
            colors: [canvas, canvasDeep],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

extension View {
    /// Standard elevated dark card used across both rider and driver shells.
    func rideCard(padding: CGFloat = 16, radius: CGFloat = RideTheme.cardRadius) -> some View {
        self
            .padding(padding)
            .background(RideTheme.surface, in: .rect(cornerRadius: radius))
            .overlay {
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(RideTheme.hairline, lineWidth: 1)
            }
    }

    /// Card variant that floats above a map and needs a touch more separation.
    func rideFloatingCard(padding: CGFloat = 16, radius: CGFloat = RideTheme.cardRadius) -> some View {
        self
            .padding(padding)
            .background(RideTheme.surface.opacity(0.96), in: .rect(cornerRadius: radius))
            .overlay {
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(RideTheme.hairline, lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.45), radius: 18, y: 8)
    }
}

/// Primary orange call to action shared by every screen.
struct RidePrimaryButtonStyle: ButtonStyle {
    var isEnabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(isEnabled ? Color(hex: 0x14203A) : RideTheme.textSecondary)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                isEnabled ? RideTheme.accent : RideTheme.surfaceRaised,
                in: .rect(cornerRadius: 27)
            )
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Quiet secondary action on dark surfaces.
struct RideSecondaryButtonStyle: ButtonStyle {
    var tint: Color = RideTheme.textPrimary
    var bordered: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(RideTheme.surfaceRaised, in: .rect(cornerRadius: RideTheme.controlRadius))
            .overlay {
                RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                    .strokeBorder(bordered ? tint.opacity(0.8) : RideTheme.hairline, lineWidth: 1)
            }
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

enum Haptics {
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}
