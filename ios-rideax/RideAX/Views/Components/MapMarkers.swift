import SwiftUI
import MapKit

/// Orange pickup pin with a soft glow, matching the brand map language.
struct PickupPin: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(RideTheme.accent.opacity(0.22))
                .frame(width: 46, height: 46)
                .blur(radius: 6)
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 30))
                .foregroundStyle(.white, RideTheme.accent)
                .shadow(color: .black.opacity(0.5), radius: 4, y: 2)
        }
        .accessibilityLabel("Pickup location")
    }
}

/// Square destination marker.
struct DropoffMarker: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .fill(RideTheme.accent)
                .frame(width: 16, height: 16)
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(.white, lineWidth: 2.5)
                .frame(width: 16, height: 16)
        }
        .shadow(color: .black.opacity(0.5), radius: 4, y: 2)
        .accessibilityLabel("Destination")
    }
}

/// Vehicle marker used for the matched driver and for ambient nearby cars.
///
/// The glyph stays upright so it is always readable; direction of travel is
/// carried by the cone behind it, which rotates with the smoothed heading.
struct CarMarker: View {
    var isActive: Bool = false
    var heading: Double = 0
    var isMoving: Bool = false

    private var size: CGFloat { isActive ? 34 : 26 }

    var body: some View {
        ZStack {
            if isActive {
                HeadingCone()
                    .fill(
                        LinearGradient(
                            colors: [RideTheme.accent.opacity(isMoving ? 0.55 : 0.2), RideTheme.accent.opacity(0)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .frame(width: 54, height: 44)
                    .offset(y: -24)
                    .rotationEffect(.degrees(heading))
                    .blur(radius: 1.5)
            }

            Image(systemName: "car.fill")
                .font(.system(size: isActive ? 16 : 13, weight: .semibold))
                .foregroundStyle(isActive ? Color(hex: 0x14203A) : RideTheme.textPrimary)
                .frame(width: size, height: size)
                .background(Circle().fill(isActive ? RideTheme.accent : RideTheme.surfaceRaised))
                .overlay(Circle().strokeBorder(.white.opacity(isActive ? 0.9 : 0.25), lineWidth: 1.5))
                .shadow(color: .black.opacity(0.45), radius: 5, y: 2)
        }
        .frame(width: 60, height: 60)
        .accessibilityLabel(isActive ? "Your driver" : "Nearby driver")
    }
}

/// The direction-of-travel cone drawn behind the active vehicle marker.
private struct HeadingCone: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY),
            control: CGPoint(x: rect.midX, y: rect.maxY * 0.72)
        )
        path.closeSubpath()
        return path
    }
}

/// Live caption pinned above the driver's vehicle: ETA while they approach,
/// speed once the trip is under way.
struct DriverMarkerCaption: View {
    var text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .monospacedDigit()
            .foregroundStyle(RideTheme.textPrimary)
            .padding(.horizontal, 8)
            .frame(height: 22)
            .background(RideTheme.surface.opacity(0.96), in: .capsule)
            .overlay(Capsule().strokeBorder(RideTheme.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.35), radius: 4, y: 2)
            .fixedSize()
            .accessibilityHidden(true)
    }
}

/// Driver's own position on the driver map.
struct DriverSelfMarker: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(RideTheme.accent.opacity(0.25))
                .frame(width: 44, height: 44)
            Image(systemName: "location.north.fill")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Color(hex: 0x14203A))
                .frame(width: 30, height: 30)
                .background(Circle().fill(RideTheme.accent))
                .overlay(Circle().strokeBorder(.white, lineWidth: 2))
        }
        .accessibilityLabel("Your vehicle")
    }
}

extension MKCoordinateRegion {
    /// Region that comfortably contains every supplied point.
    static func containing(_ points: [GeoPoint], paddingFactor: Double = 1.6) -> MKCoordinateRegion? {
        guard !points.isEmpty else { return nil }
        let latitudes = points.map(\.latitude)
        let longitudes = points.map(\.longitude)
        guard let minLat = latitudes.min(), let maxLat = latitudes.max(),
              let minLon = longitudes.min(), let maxLon = longitudes.max() else { return nil }

        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * paddingFactor, 0.008),
            longitudeDelta: max((maxLon - minLon) * paddingFactor, 0.008)
        )
        return MKCoordinateRegion(center: center, span: span)
    }
}
