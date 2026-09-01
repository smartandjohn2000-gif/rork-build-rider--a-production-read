import SwiftUI

/// Driver registration: vehicle details and the documents admin must approve.
struct DriverDocumentsView: View {
    @Environment(DriverStore.self) private var driverStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                statusCard
                vehicleCard

                SectionHeader(title: "Documents")
                    .padding(.top, 4)

                ForEach(driverStore.documents) { document in
                    documentRow(document)
                }

                if driverStore.approvalStatus == .notSubmitted || driverStore.approvalStatus == .rejected {
                    Button("Submit for approval") {
                        driverStore.submitForApproval()
                    }
                    .buttonStyle(RidePrimaryButtonStyle(isEnabled: driverStore.documents.allSatisfy(\.isUploaded)))
                    .disabled(!driverStore.documents.allSatisfy(\.isUploaded))
                    .padding(.top, 6)
                }

                Text("Documents are stored encrypted and reviewed by the RideAX compliance team. You'll be notified as soon as a decision is made.")
                    .font(.caption2)
                    .foregroundStyle(RideTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 4)
            }
            .padding(.horizontal, RideTheme.margin)
            .padding(.bottom, 28)
        }
        .background(RideTheme.canvas)
        .navigationTitle("Driver profile")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(RideTheme.canvas, for: .navigationBar)
        .preferredColorScheme(.dark)
    }

    private var statusCard: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .background(tint.opacity(0.14), in: .circle)

            VStack(alignment: .leading, spacing: 3) {
                Text(driverStore.approvalStatus.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideTheme.textPrimary)
                Text(driverStore.approvalStatus.detail)
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .rideCard()
    }

    private var symbol: String {
        switch driverStore.approvalStatus {
        case .approved: "checkmark.seal.fill"
        case .pending: "clock.fill"
        case .rejected: "exclamationmark.triangle.fill"
        case .notSubmitted: "doc.badge.plus"
        }
    }

    private var tint: Color {
        switch driverStore.approvalStatus {
        case .approved: RideTheme.success
        case .rejected: RideTheme.danger
        default: RideTheme.accent
        }
    }

    private var vehicleCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Your vehicle")
            HStack(spacing: 12) {
                Image(systemName: "car.side.fill")
                    .font(.title3)
                    .foregroundStyle(RideTheme.accent)
                    .frame(width: 44, height: 44)
                    .background(RideTheme.surfaceRaised, in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    Text(driverStore.vehicle.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RideTheme.textPrimary)
                    Text("\(driverStore.vehicle.year.formatted(.number.grouping(.never))) · \(driverStore.vehicle.plate)")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(RideTheme.textSecondary)
                }
                Spacer(minLength: 0)
            }
        }
        .rideCard()
    }

    private func documentRow(_ document: DriverDocument) -> some View {
        HStack(spacing: 12) {
            Image(systemName: document.symbol)
                .font(.system(size: 16))
                .foregroundStyle(document.isUploaded ? RideTheme.success : RideTheme.accent)
                .frame(width: 42, height: 42)
                .background(RideTheme.surfaceRaised, in: .circle)

            VStack(alignment: .leading, spacing: 2) {
                Text(document.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(RideTheme.textPrimary)
                Text(document.detail)
                    .font(.caption)
                    .foregroundStyle(RideTheme.textSecondary)
            }

            Spacer(minLength: 0)

            if document.isUploaded {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(RideTheme.success)
            } else {
                Button("Upload") {
                    driverStore.uploadDocument(id: document.id)
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(RideTheme.accent)
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(RideTheme.accent.opacity(0.14), in: .capsule)
                .buttonStyle(.plain)
            }
        }
        .rideCard(padding: 12)
    }
}
