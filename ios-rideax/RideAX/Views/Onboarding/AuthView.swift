import SwiftUI
import AuthenticationServices

/// Sign-in sheet. Email and password, or continue with Apple or Google —
/// and the role chosen during onboarding is carried through so the person
/// lands directly in the right shell.
struct AuthView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    let role: AppRole

    private enum Mode { case signIn, signUp }
    @State private var mode: Mode = .signIn
    @State private var email = ""
    @State private var password = ""
    @State private var fullName = ""
    @State private var pressedProvider: AuthProvider?
    @FocusState private var focusedField: Field?

    private enum Field { case name, email, password }

    private var emailIsValid: Bool {
        email.contains("@") && email.contains(".") && email.count > 5
    }
    private var passwordIsValid: Bool {
        password.count >= 8
    }
    private var canSubmit: Bool {
        emailIsValid && passwordIsValid && (mode == .signIn || !fullName.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                RideTheme.backdrop.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 0) {
                        header
                        credentialsForm
                        socialSection
                    }
                    .padding(.bottom, 24)
                }
                .scrollBounceBehavior(.basedOnSize)
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

    private var header: some View {
        VStack(spacing: 12) {
            BrandMark(size: 64)
            Text(role == .rider
                 ? (mode == .signUp ? "Create your account" : "Ride in seconds")
                 : (mode == .signUp ? "Start earning" : "Welcome back, driver"))
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
        .padding(.top, 12)
        .padding(.bottom, 20)
    }

    private var credentialsForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("", selection: $mode.animation(.snappy(duration: 0.25))) {
                Text("Sign in").tag(Mode.signIn)
                Text("Create account").tag(Mode.signUp)
            }
            .pickerStyle(.segmented)
            .tint(RideTheme.accent)

            if mode == .signUp {
                field("Full name", systemImage: "person", text: $fullName, field: .name)
                    .textContentType(.name)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .email }
            }

            field("Email", systemImage: "envelope", text: $email, field: .email)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(mode == .signUp ? .emailAddress : .username)
                .submitLabel(.next)
                .onSubmit { focusedField = .password }

            field("Password", systemImage: "lock", text: $password, field: .password, secure: true)
                .textContentType(mode == .signUp ? .newPassword : .password)
                .submitLabel(.go)

            if mode == .signUp {
                Text("At least 8 characters.")
                    .font(.caption2)
                    .foregroundStyle(RideTheme.textSecondary.opacity(0.7))
            }

            if let error = session.authError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(RideTheme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }

            Button {
                Haptics.tap()
                Task {
                    await session.authenticate(
                        email: email.trimmingCharacters(in: .whitespaces).lowercased(),
                        password: password,
                        fullName: fullName.trimmingCharacters(in: .whitespaces).isEmpty
                            ? nil : fullName.trimmingCharacters(in: .whitespaces),
                        isSignUp: mode == .signUp
                    )
                }
            } label: {
                HStack(spacing: 8) {
                    if session.isAuthenticating && pressedProvider == nil {
                        ProgressView().tint(Color(hex: 0x14203A))
                    } else {
                        Image(systemName: "arrow.right")
                        Text(mode == .signUp ? "Create account" : "Sign in")
                    }
                }
                .font(.headline)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .foregroundStyle(Color(hex: 0x14203A))
                .background(canSubmit ? AnyShapeStyle(RideTheme.accent) : AnyShapeStyle(RideTheme.accent.opacity(0.35)), in: .rect(cornerRadius: 27))
            }
            .buttonStyle(.plain)
            .disabled(!canSubmit || session.isAuthenticating)
        }
        .padding(.horizontal, RideTheme.margin)
        .animation(.snappy(duration: 0.25), value: session.authError)
        .animation(.snappy(duration: 0.25), value: mode)
    }

    private var socialSection: some View {
        VStack(spacing: 12) {
            dividerRow
            providerButton(.apple)
            providerButton(.google)

            trustRow
                .padding(.top, 6)

            Text("By continuing you agree to the RideAX Terms and Privacy Policy.")
                .font(.caption2)
                .foregroundStyle(RideTheme.textSecondary.opacity(0.8))
                .multilineTextAlignment(.center)
                .padding(.top, 2)
        }
        .padding(.top, 20)
        .padding(.horizontal, RideTheme.margin)
    }

    private var dividerRow: some View {
        HStack(spacing: 12) {
            Rectangle().fill(RideTheme.hairline).frame(height: 1)
            Text("or")
                .font(.caption)
                .foregroundStyle(RideTheme.textSecondary)
            Rectangle().fill(RideTheme.hairline).frame(height: 1)
        }
    }

    private func field(_ placeholder: String, systemImage: String, text: Binding<String>, field: Field, secure: Bool = false) -> some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(RideTheme.textSecondary)
                .frame(width: 18)
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(RideTheme.surfaceRaised, in: .rect(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(focusedField == field ? RideTheme.accent.opacity(0.6) : RideTheme.hairline, lineWidth: 1)
        }
        .focused($focusedField, equals: field)
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
            trustItem(symbol: "envelope.badge.fill", label: "You're in control")
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
