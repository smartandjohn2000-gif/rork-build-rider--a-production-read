import SwiftUI

/// A pickup → dropoff route summary with the connecting dotted spine.
struct RouteSummaryRow: View {
    var pickupName: String
    var dropoffName: String
    var compact: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 3) {
                Circle()
                    .fill(RideTheme.accent)
                    .frame(width: 10, height: 10)
                ForEach(0..<3, id: \.self) { _ in
                    Circle()
                        .fill(RideTheme.textSecondary.opacity(0.5))
                        .frame(width: 2, height: 2)
                }
                RoundedRectangle(cornerRadius: 2)
                    .strokeBorder(RideTheme.accent, lineWidth: 2)
                    .frame(width: 9, height: 9)
            }
            .padding(.top, 4)

            VStack(alignment: .leading, spacing: compact ? 8 : 12) {
                Text(pickupName)
                    .font(compact ? .subheadline : .body)
                    .foregroundStyle(RideTheme.textPrimary)
                Text(dropoffName)
                    .font(compact ? .subheadline : .body)
                    .foregroundStyle(RideTheme.textPrimary)
            }

            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("From \(pickupName) to \(dropoffName)")
    }
}

/// Compact stat used in driver strips and account summaries.
struct StatTile: View {
    var symbol: String
    var value: String
    var caption: String
    var tint: Color = RideTheme.textPrimary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.footnote)
                    .foregroundStyle(tint)
                Text(value)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(RideTheme.textPrimary)
            }
            Text(caption)
                .font(.caption2)
                .foregroundStyle(RideTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(value) \(caption)")
    }
}

/// Star rating display used for drivers and riders.
struct RatingLabel: View {
    var rating: Double
    var detail: String?

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "star.fill")
                .font(.caption2)
                .foregroundStyle(RideTheme.accentWarm)
            Text(rating.formatted(.number.precision(.fractionLength(rating == rating.rounded() ? 1 : 2))))
                .monospacedDigit()
            if let detail {
                Text("· \(detail)")
            }
        }
        .font(.subheadline)
        .foregroundStyle(RideTheme.textSecondary)
    }
}

/// Circular icon button (call, message, close).
struct CircleIconButton: View {
    var symbol: String
    var label: String
    var tint: Color = RideTheme.textPrimary
    var background: Color = RideTheme.surfaceRaised
    var action: () -> Void

    var body: some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 46, height: 46)
                .background(Circle().fill(background))
                .overlay(Circle().strokeBorder(RideTheme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Section header with an optional trailing action.
struct SectionHeader: View {
    var title: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack {
            Text(title)
                .font(.headline)
                .foregroundStyle(RideTheme.textPrimary)
            Spacer()
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideTheme.accent)
            }
        }
    }
}

/// Empty state used across Activity and driver history.
struct EmptyStateView: View {
    var symbol: String
    var title: String
    var message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 34))
                .foregroundStyle(RideTheme.accent.opacity(0.8))
            Text(title)
                .font(.headline)
                .foregroundStyle(RideTheme.textPrimary)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(RideTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// Thin progress line used for arrival countdowns.
struct ProgressLine: View {
    var progress: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(RideTheme.textSecondary.opacity(0.25))
                Capsule()
                    .fill(RideTheme.accent)
                    .frame(width: max(6, proxy.size.width * min(max(progress, 0), 1)))
            }
        }
        .frame(height: 5)
        .animation(.easeInOut(duration: 0.4), value: progress)
        .accessibilityHidden(true)
    }
}
