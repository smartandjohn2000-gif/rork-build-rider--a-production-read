import SwiftUI

/// The rider-facing live fare readout: the current total for the selected tier,
/// how it moved since the last reprice, what demand is doing, and an itemised
/// breakdown of every charge. Everything here re-renders as the route, the
/// rider's position or market conditions change.
struct LiveFareCard: View {
    var quote: FareQuote?
    var demand: DemandSnapshot
    var estimate: RouteEstimate?
    var rates: (perKilometre: Double, perMinute: Double)
    var isQuoting: Bool
    var isRepricing: Bool
    var updatedAt: Date?
    var fareChange: Double
    var isFareCurrent: Bool
    var driftMeters: Double
    @Binding var isExpanded: Bool
    var onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            if let quote, isExpanded {
                VStack(spacing: 0) {
                    Rectangle()
                        .fill(RideTheme.hairline)
                        .frame(height: 1)
                        .padding(.vertical, 10)

                    FareBreakdownList(
                        quote: quote,
                        perKilometre: rates.perKilometre,
                        perMinute: rates.perMinute,
                        demandReason: demand.isSurging ? demand.reason : nil
                    )
                }
                .transition(.asymmetric(
                    insertion: .push(from: .top).combined(with: .opacity),
                    removal: .opacity
                ))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RideTheme.surfaceRaised.opacity(0.7), in: .rect(cornerRadius: RideTheme.controlRadius))
        .overlay {
            RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                .strokeBorder(demand.isSurging ? RideTheme.accent.opacity(0.45) : RideTheme.hairline, lineWidth: 1)
        }
        .animation(.spring(response: 0.34, dampingFraction: 0.82), value: isExpanded)
        .animation(.snappy(duration: 0.35), value: quote?.total)
        .animation(.easeInOut(duration: 0.25), value: demand)
    }

    // MARK: - Header

    private var header: some View {
        Button {
            guard quote != nil else { return }
            Haptics.tap()
            isExpanded.toggle()
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    LiveFareIndicator(updatedAt: updatedAt, isRefreshing: isQuoting || isRepricing)
                    Spacer(minLength: 0)
                    if let estimate {
                        Text("\(estimate.formattedDistance) · \(estimate.minutes) min")
                            .font(.caption)
                            .monospacedDigit()
                            .foregroundStyle(RideTheme.textSecondary)
                    }
                }

                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    if let quote {
                        Text(quote.formattedTotal)
                            .font(.system(size: 32, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .contentTransition(.numericText(value: quote.total))
                            .foregroundStyle(RideTheme.textPrimary)
                    } else {
                        Text("Calculating…")
                            .font(.system(size: 24, weight: .semibold, design: .rounded))
                            .foregroundStyle(RideTheme.textSecondary)
                            .redacted(reason: .placeholder)
                    }

                    if fareChange != 0, let quote {
                        FareDeltaChip(delta: fareChange, currencyCode: quote.currencyCode)
                            .transition(.scale(scale: 0.7).combined(with: .opacity))
                    }

                    Spacer(minLength: 0)

                    if quote != nil {
                        Image(systemName: "chevron.down")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(RideTheme.textSecondary)
                            .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    }
                }

                HStack(spacing: 8) {
                    if demand.isSurging {
                        SurgeBadge(demand: demand)
                            .transition(.scale(scale: 0.85).combined(with: .opacity))
                    } else {
                        Text("Standard fare · no surge")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(RideTheme.textSecondary)
                    }

                    Spacer(minLength: 0)

                    if !isFareCurrent {
                        staleFareButton
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint(quote == nil ? "" : "Double tap to \(isExpanded ? "hide" : "show") the fare breakdown")
    }

    private var staleFareButton: some View {
        Button {
            Haptics.tap()
            onRefresh()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "arrow.clockwise")
                Text("Moved \(Int(driftMeters)) m · update")
            }
            .font(.caption2.weight(.semibold))
            .monospacedDigit()
            .foregroundStyle(RideTheme.accent)
        }
        .buttonStyle(.plain)
    }

    private var accessibilityText: String {
        guard let quote else { return "Calculating your fare" }
        var text = "Live fare \(quote.formattedTotal)"
        if let estimate {
            text += ", \(estimate.formattedDistance) over \(estimate.minutes) minutes"
        }
        if demand.isSurging {
            text += ", \(demand.label), \(demand.formattedMultiplier) multiplier"
        }
        return text
    }
}

/// Itemised charges, recomputed from the current quote on every reprice.
struct FareBreakdownList: View {
    var quote: FareQuote
    var perKilometre: Double
    var perMinute: Double
    var demandReason: String?

    var body: some View {
        VStack(spacing: 9) {
            ForEach(quote.lines(perKilometre: perKilometre, perMinute: perMinute)) { line in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(line.label)
                            .font(.footnote)
                            .foregroundStyle(line.isHighlighted ? RideTheme.accent : RideTheme.textPrimary)
                        if let detail = line.detail {
                            Text(detail)
                                .font(.caption2)
                                .monospacedDigit()
                                .foregroundStyle(RideTheme.textSecondary)
                        }
                    }
                    Spacer(minLength: 0)
                    Text(line.amount)
                        .font(.footnote.weight(.medium))
                        .monospacedDigit()
                        .contentTransition(.numericText())
                        .foregroundStyle(line.isHighlighted ? RideTheme.accent : RideTheme.textPrimary)
                }
            }

            Rectangle()
                .fill(RideTheme.hairline)
                .frame(height: 1)
                .padding(.vertical, 2)

            HStack {
                Text("Total")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideTheme.textPrimary)
                Spacer()
                Text(quote.formattedTotal)
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                    .contentTransition(.numericText(value: quote.total))
                    .foregroundStyle(RideTheme.textPrimary)
            }

            Text(demandReason ?? "Your final fare can change if the route or waiting time does. Tolls are billed at cost.")
                .font(.caption2)
                .foregroundStyle(RideTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Live demand pill shown whenever a surge multiplier is in effect.
struct SurgeBadge: View {
    var demand: DemandSnapshot

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "bolt.fill")
                .font(.system(size: 9, weight: .bold))
            Text(demand.formattedMultiplier)
                .monospacedDigit()
            Text(demand.label)
        }
        .font(.caption2.weight(.bold))
        .foregroundStyle(Color(hex: 0x14203A))
        .padding(.horizontal, 9)
        .frame(height: 22)
        .background(RideTheme.accent, in: .capsule)
    }
}

/// Shows how far the fare moved at the last reprice.
struct FareDeltaChip: View {
    var delta: Double
    var currencyCode: String

    private var isUp: Bool { delta > 0 }

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: isUp ? "arrow.up" : "arrow.down")
                .font(.system(size: 9, weight: .bold))
            Text(abs(delta).formatted(.currency(code: currencyCode).precision(.fractionLength(2))))
                .monospacedDigit()
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(isUp ? RideTheme.accentWarm : RideTheme.textSecondary)
        .padding(.horizontal, 7)
        .frame(height: 20)
        .background(RideTheme.surface, in: .capsule)
        .overlay(Capsule().strokeBorder(RideTheme.hairline, lineWidth: 1))
        .accessibilityLabel(isUp ? "Fare went up" : "Fare came down")
    }
}

/// Pulsing dot plus a relative timestamp proving the price is being tracked.
struct LiveFareIndicator: View {
    var updatedAt: Date?
    var isRefreshing: Bool

    var body: some View {
        HStack(spacing: 6) {
            PulsingDot(color: isRefreshing ? RideTheme.accentWarm : RideTheme.accent)

            Group {
                if isRefreshing {
                    Text("Updating fare…")
                } else if let updatedAt {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        Text(Self.caption(since: updatedAt, now: context.date))
                            .monospacedDigit()
                    }
                } else {
                    Text("Live fare")
                }
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(RideTheme.textSecondary)
        }
        .accessibilityHidden(true)
    }

    private static func caption(since date: Date, now: Date) -> String {
        let seconds = Int(now.timeIntervalSince(date))
        switch seconds {
        case ..<3: return "Live fare · just now"
        case ..<60: return "Live fare · \(seconds)s ago"
        default: return "Live fare · \(seconds / 60)m ago"
        }
    }
}

/// Small radar-style pulse used by the live fare indicator.
struct PulsingDot: View {
    var color: Color
    @State private var isPulsing: Bool = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 7, height: 7)
            .overlay {
                Circle()
                    .strokeBorder(color, lineWidth: 1.5)
                    .scaleEffect(isPulsing ? 2.6 : 1)
                    .opacity(isPulsing ? 0 : 0.85)
            }
            .onAppear {
                withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) {
                    isPulsing = true
                }
            }
    }
}
