import SwiftUI
import AuthenticationServices

/// Sign-in sheet. Identity is handled by Apple or Google — RideAX never sees a
/// password — and the role chosen during onboarding is carried through so the
/// person lands directly in the right shell.
struct AuthView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    let role: AppRole

    @State private var pressedProvider: AuthProvider?

    var body: some View {
        NavigationStack {
            ZStack {
                RideTheme.backdrop.ignoresSafeArea()

                VStack(spacing: 0) {
                    Spacer(minLength: 12)

                    VStack(spacing: 12) {
                        BrandMark(size: 64)
                        Text(role == .rider ? "Ride in seconds" : "Start earning")
                            .font(.title2.bold())
                            .foregroundStyle(RideTheme.textPrimary)
                        Text(role == .rider
                             ? "Sign in to book rides, follow your driver live and pay securely."
                             : "Sign in to set up your driver profile and go online.")
                            .font(.subheadline)
                            .foregroundStyle(RideTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 28)
                    }

                    Spacer(minLength: 20)

                    VStack(spacing: 12) {
                        providerButton(.apple)
                        providerButton(.google)

                        if let error = session.authError {
                            Label(error, systemImage: "exclamationmark.triangle.fill")
                                .font(.footnote)
                                .foregroundStyle(RideTheme.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }

                        trustRow
                            .padding(.top, 6)

                        Text("By continuing you agree to the RideAX Terms and Privacy Policy.")
                            .font(.caption2)
                            .foregroundStyle(RideTheme.textSecondary.opacity(0.8))
                            .multilineTextAlignment(.center)
                            .padding(.top, 2)
                    }
                    .padding(.horizontal, RideTheme.margin)
                    .padding(.bottom, 24)
                }
                .animation(.snappy(duration: 0.25), value: session.authError)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                        .foregroundStyle(RideTheme.textSecondary)
                        .disabled(session.isAuthenticating)
                }
            }
        }
        .preferredColorScheme(.dark)
        .interactiveDismissDisabled(session.isAuthenticating)
        .onChange(of: session.isSignedIn) { _, signedIn in
            guard signedIn else { return }
            session.completeOnboarding(role: role)
            Haptics.success()
            dismiss()
        }
    }

    private func providerButton(_ provider: AuthProvider) -> some View {
        Button {
            Haptics.tap()
            pressedProvider = provider
            Task {
                await session.signIn(with: provider)
                pressedProvider = nil
            }
        } label: {
            HStack(spacing: 10) {
                if session.isAuthenticating, pressedProvider == provider {
                    ProgressView()
                        .tint(provider == .apple ? Color(hex: 0x14203A) : RideTheme.textPrimary)
                } else {
                    providerGlyph(provider)
                    Text(provider.title)
                        .font(.headline)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .foregroundStyle(provider == .apple ? Color(hex: 0x14203A) : RideTheme.textPrimary)
            .background(
                provider == .apple ? AnyShapeStyle(Color.white) : AnyShapeStyle(RideTheme.surfaceRaised),
                in: .rect(cornerRadius: 27)
            )
            .overlay {
                if provider != .apple {
                    RoundedRectangle(cornerRadius: 27)
                        .strokeBorder(RideTheme.hairline, lineWidth: 1)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(session.isAuthenticating)
        .opacity(session.isAuthenticating && pressedProvider != provider ? 0.5 : 1)
    }

    @ViewBuilder
    private func providerGlyph(_ provider: AuthProvider) -> some View {
        switch provider {
        case .apple:
            Image(systemName: "apple.logo")
                .font(.system(size: 18, weight: .medium))
        case .google:
            // Google's mark can't ship as an SF Symbol, so the wordmark's "G"
            // is drawn in the brand's four colours.
            Text("G")
                .font(.system(size: 19, weight: .bold, design: .rounded))
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color(hex: 0x4285F4), Color(hex: 0xEA4335), Color(hex: 0xFBBC05), Color(hex: 0x34A853)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        }
    }

    private var trustRow: some View {
        HStack(spacing: 16) {
            trustItem(symbol: "lock.shield.fill", label: "Encrypted")
            trustItem(symbol: "creditcard.fill", label: "Stripe payments")
            trustItem(symbol: "hand.raised.fill", label: "No passwords")
        }
        .frame(maxWidth: .infinity)
    }

    private func trustItem(symbol: String, label: String) -> some View {
        VStack(spacing: 5) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RideTheme.accent)
            Text(label)
                .font(.caption2)
                .foregroundStyle(RideTheme.textSecondary)
        }
    }
}

#Preview {
    AuthView(role: .rider)
        .environment(SessionStore())
}
