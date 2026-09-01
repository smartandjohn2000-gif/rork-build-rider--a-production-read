import SwiftUI

nonisolated enum AuthMode: String, Sendable {
    case signIn
    case signUp

    var title: String {
        switch self {
        case .signIn: "Welcome back"
        case .signUp: "Create your account"
        }
    }

    var cta: String {
        switch self {
        case .signIn: "Sign in"
        case .signUp: "Create account"
        }
    }
}

/// Sign-in / sign-up sheet. Role is carried through from onboarding so the
/// person lands directly in the right shell.
struct AuthView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    let mode: AuthMode
    let role: AppRole

    @State private var currentMode: AuthMode
    @State private var name: String = ""
    @State private var email: String = ""
    @State private var phone: String = ""
    @State private var password: String = ""

    init(mode: AuthMode, role: AppRole) {
        self.mode = mode
        self.role = role
        _currentMode = State(initialValue: mode)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                RideTheme.backdrop.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        VStack(spacing: 10) {
                            BrandMark(size: 58)
                            Text(currentMode.title)
                                .font(.title2.bold())
                                .foregroundStyle(RideTheme.textPrimary)
                            Text(role == .rider
                                 ? "Book rides, track drivers and pay securely."
                                 : "Set up your driver profile and start earning.")
                                .font(.subheadline)
                                .foregroundStyle(RideTheme.textSecondary)
                                .multilineTextAlignment(.center)
                        }
                        .padding(.top, 12)

                        VStack(spacing: 12) {
                            if currentMode == .signUp {
                                field(title: "Full name", text: $name, symbol: "person.fill", content: .name)
                                field(title: "Phone number", text: $phone, symbol: "phone.fill", content: .telephoneNumber, keyboard: .phonePad)
                            }
                            field(title: "Email", text: $email, symbol: "envelope.fill", content: .emailAddress, keyboard: .emailAddress)
                            secureField(title: "Password", text: $password)
                        }

                        if let error = session.authError {
                            Label(error, systemImage: "exclamationmark.triangle.fill")
                                .font(.footnote)
                                .foregroundStyle(RideTheme.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        Button {
                            Task { await submit() }
                        } label: {
                            if session.isAuthenticating {
                                ProgressView().tint(Color(hex: 0x14203A))
                            } else {
                                Text(currentMode.cta)
                            }
                        }
                        .buttonStyle(RidePrimaryButtonStyle())
                        .disabled(session.isAuthenticating)

                        Button {
                            session.clearAuthError()
                            withAnimation { currentMode = currentMode == .signIn ? .signUp : .signIn }
                        } label: {
                            Text(currentMode == .signIn ? "New to RideAX? Create an account" : "Already have an account? Sign in")
                                .font(.subheadline)
                                .foregroundStyle(RideTheme.accent)
                        }
                        .buttonStyle(.plain)

                        Text("By continuing you agree to the RideAX Terms and Privacy Policy.")
                            .font(.caption2)
                            .foregroundStyle(RideTheme.textSecondary.opacity(0.8))
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    }
                    .padding(.horizontal, RideTheme.margin)
                    .padding(.bottom, 32)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                        .foregroundStyle(RideTheme.textSecondary)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func field(
        title: String,
        text: Binding<String>,
        symbol: String,
        content: UITextContentType,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.footnote)
                .foregroundStyle(RideTheme.textSecondary)
                .frame(width: 20)
            TextField("", text: text, prompt: Text(title).foregroundStyle(RideTheme.textSecondary))
                .textContentType(content)
                .keyboardType(keyboard)
                .textInputAutocapitalization(content == .name ? .words : .never)
                .autocorrectionDisabled()
                .foregroundStyle(RideTheme.textPrimary)
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .background(RideTheme.surface, in: .rect(cornerRadius: RideTheme.controlRadius))
        .overlay {
            RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                .strokeBorder(RideTheme.hairline, lineWidth: 1)
        }
    }

    private func secureField(title: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.fill")
                .font(.footnote)
                .foregroundStyle(RideTheme.textSecondary)
                .frame(width: 20)
            SecureField("", text: text, prompt: Text(title).foregroundStyle(RideTheme.textSecondary))
                .textContentType(currentMode == .signUp ? .newPassword : .password)
                .foregroundStyle(RideTheme.textPrimary)
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .background(RideTheme.surface, in: .rect(cornerRadius: RideTheme.controlRadius))
        .overlay {
            RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                .strokeBorder(RideTheme.hairline, lineWidth: 1)
        }
    }

    private func submit() async {
        switch currentMode {
        case .signIn:
            await session.signIn(email: email, password: password)
        case .signUp:
            await session.signUp(name: name, email: email, phone: phone, password: password)
        }
        if session.isSignedIn {
            session.completeOnboarding(role: role)
            Haptics.success()
            dismiss()
        }
    }
}

#Preview {
    AuthView(mode: .signUp, role: .rider)
        .environment(SessionStore())
}
