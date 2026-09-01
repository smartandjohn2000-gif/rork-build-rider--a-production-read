import SwiftUI

/// The RideAX monogram: an "R" whose bowl opens into a curving highway,
/// drawn in code so it stays crisp at any size.
struct BrandMark: View {
    var size: CGFloat = 96

    var body: some View {
        ZStack {
            Text("R")
                .font(.system(size: size, weight: .black, design: .rounded))
                .foregroundStyle(RideTheme.accent)
                .offset(x: -size * 0.04)

            RoadCurve()
                .stroke(
                    RideTheme.canvas,
                    style: StrokeStyle(lineWidth: size * 0.13, lineCap: .round)
                )
                .frame(width: size * 0.62, height: size * 0.78)
                .offset(x: size * 0.02, y: size * 0.06)

            RoadCurve()
                .stroke(
                    Color.white.opacity(0.85),
                    style: StrokeStyle(lineWidth: size * 0.02, lineCap: .round, dash: [size * 0.05, size * 0.05])
                )
                .frame(width: size * 0.62, height: size * 0.78)
                .offset(x: size * 0.02, y: size * 0.06)
        }
        .frame(width: size * 1.1, height: size * 1.1)
        .accessibilityHidden(true)
    }
}

/// A road that sweeps from the lower left up to the top right.
struct RoadCurve: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + rect.width * 0.05, y: rect.maxY))
        path.addCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY),
            control1: CGPoint(x: rect.minX + rect.width * 0.1, y: rect.midY),
            control2: CGPoint(x: rect.maxX * 0.75, y: rect.minY + rect.height * 0.32)
        )
        return path
    }
}

/// Full lockup: "Ride" in white, "AX" in orange, with the tagline.
struct BrandWordmark: View {
    var size: CGFloat = 44
    var showsTagline: Bool = true

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                Text("Ride")
                    .foregroundStyle(RideTheme.textPrimary)
                Text("AX")
                    .foregroundStyle(RideTheme.accent)
            }
            .font(.system(size: size, weight: .heavy, design: .default))
            .kerning(-0.5)

            if showsTagline {
                Text("YOUR RIDE, OUR PRIORITY")
                    .font(.system(size: max(10, size * 0.24), weight: .medium))
                    .kerning(size * 0.06)
                    .foregroundStyle(RideTheme.textSecondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("RideAX. Your ride, our priority.")
    }
}

/// Soft navy wash with a faint orange highway sweep — used behind the logo.
struct BrandBackdrop: View {
    var body: some View {
        ZStack {
            RideTheme.backdrop

            RadialGradient(
                colors: [RideTheme.accent.opacity(0.22), .clear],
                center: .init(x: 0.75, y: 0.18),
                startRadius: 8,
                endRadius: 320
            )

            RoadCurve()
                .stroke(
                    LinearGradient(
                        colors: [RideTheme.accent.opacity(0.0), RideTheme.accent.opacity(0.35)],
                        startPoint: .bottomLeading,
                        endPoint: .topTrailing
                    ),
                    style: StrokeStyle(lineWidth: 46, lineCap: .round)
                )
                .frame(width: 380, height: 420)
                .blur(radius: 26)
                .offset(x: 90, y: -170)
        }
        .ignoresSafeArea()
    }
}

#Preview {
    ZStack {
        BrandBackdrop()
        VStack(spacing: 16) {
            BrandMark()
            BrandWordmark()
        }
    }
}
