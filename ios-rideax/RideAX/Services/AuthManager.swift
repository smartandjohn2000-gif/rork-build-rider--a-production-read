import SwiftUI
import AuthenticationServices
import Supabase
import Auth

/// The identity providers RideAX accepts.
nonisolated enum AuthProvider: String, Sendable, CaseIterable {
    case apple
    case google

    var title: String {
        switch self {
        case .apple: "Continue with Apple"
        case .google: "Continue with Google"
        }
    }

    var supabaseProvider: Auth.Provider {
        switch self {
        case .apple: .apple
        case .google: .google
        }
    }
}

/// Owns the signed-in identity.
///
/// People register with email and password, or continue with Apple or Google.
/// All three flow through native Supabase Auth, whose JWT is what Postgres
/// row-level security reads to decide which rides and payments this person is
/// allowed to touch. The Supabase client owns session storage and refreshing;
/// this manager just decides what the UI shows.
@Observable
final class AuthManager {
    static let shared = AuthManager()

    private(set) var user: AuthUser?
    private(set) var isRestoring: Bool = true
    private(set) var isSigningIn: Bool = false
    private(set) var errorMessage: String?

    private let projectID = Config.EXPO_PUBLIC_PROJECT_ID
    private var webAuthSession: ASWebAuthenticationSession?

    nonisolated struct AuthUser: Codable, Sendable, Hashable {
        let id: String
        let email: String
        let name: String?
        let picture: String?
    }

    private init() {}

    var isSignedIn: Bool { user != nil }

    private var client: SupabaseClient { Backend.client }

    /// The deep link both apps and the allow-listed OAuth redirect use.
    private var callbackURL: URL? {
        URL(string: "rork-\(projectID)://auth/callback")
    }

    // MARK: - Session lifecycle

    /// Restores a previous session on launch, refreshing the token if needed.
    func restore() async {
        defer { isRestoring = false }

        guard let session = client.auth.currentSession else { return }
        if session.expiresAt < Date().timeIntervalSince1970 {
            guard (try? await client.auth.refreshSession()) != nil else { return }
        }
        user = client.auth.currentSession.flatMap { Self.user(from: $0.user) }
    }

    // MARK: - Email & password

    /// Signs in with email and password, or creates the account first when
    /// `isSignUp` is set. Errors are surfaced through `errorMessage`.
    func authenticate(email: String, password: String, fullName: String?, isSignUp: Bool) async {
        errorMessage = nil
        do {
            if isSignUp {
                var data: [String: AnyJSON] = [:]
                if let fullName, !fullName.isEmpty {
                    data["full_name"] = .string(fullName)
                }
                let response = try await client.auth.signUp(email: email, password: password, data: data)
                if let session = response.session {
                    user = Self.user(from: session.user)
                }
            } else {
                let session = try await client.auth.signIn(email: email, password: password)
                user = Self.user(from: session.user)
            }
        } catch {
            errorMessage = Self.describe(error)
        }
    }

    // MARK: - Apple / Google

    /// Runs the provider's OAuth flow in an in-app browser. The provider must
    /// be configured on the backend first; until then a clear message explains
    /// that instead of a dead button.
    func signIn(provider: AuthProvider) async {
        errorMessage = nil
        guard let callbackURL else {
            errorMessage = "Sign-in is misconfigured. Please contact support."
            return
        }

        do {
            // On Apple platforms the client presents the provider's web flow
            // itself and completes the callback exchange before returning.
            let session = try await client.auth.signInWithOAuth(
                provider: provider.supabaseProvider,
                redirectTo: callbackURL
            )
            user = Self.user(from: session.user)
        } catch is CancellationError {
            return
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            return
        } catch {
            errorMessage = Self.describe(error)
        }
    }

    func signOut() {
        let client = client
        user = nil
        errorMessage = nil
        Task {
            try? await client.auth.signOut()
        }
    }

    func clearError() {
        errorMessage = nil
    }

    // MARK: - Account deletion

    /// Deletes the account and its data server-side, then clears the session.
    /// The caller confirms intent first; a failure must be shown, not absorbed.
    func deleteAccount() async throws {
        guard let url = URL(string: "\(Config.EXPO_PUBLIC_SUPABASE_URL.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/functions/v1/delete-account"),
              let token = client.auth.currentSession?.accessToken else {
            throw AuthFlowError.server(message: "Your session has expired. Please sign in again.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (_, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw AuthFlowError.server(message: "Account deletion failed. Please try again.")
        }

        signOut()
    }

    // MARK: - OAuth browser

    private func runWebAuthSession(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                self?.webAuthSession = nil
                if let error {
                    continuation.resume(throwing: error)
                } else if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else {
                    continuation.resume(throwing: AuthFlowError.noCode)
                }
            }

            self.webAuthSession = session
            session.presentationContextProvider = WebAuthPresentationContext.shared
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
    }

    // MARK: - Helpers

    /// Maps the signed-in Supabase user into the shape the app renders.
    nonisolated private static func user(from supabaseUser: Auth.User) -> AuthUser {
        let metadata = supabaseUser.userMetadata
        let name = metadata["full_name"]?.stringValue
            ?? metadata["name"]?.stringValue
        return AuthUser(
            id: supabaseUser.id.uuidString,
            email: supabaseUser.email ?? "",
            name: name,
            picture: metadata["picture"]?.stringValue ?? metadata["avatar_url"]?.stringValue
        )
    }

    private static func describe(_ error: Error) -> String {
        let message = error.localizedDescription
        if message.contains("Invalid login credentials") {
            return "Email or password is incorrect."
        }
        if message.contains("already registered") {
            return "An account with this email already exists. Sign in instead."
        }
        if message.contains("Password should be at least") {
            return "Your password needs at least 8 characters."
        }
        if message.lowercased().contains("rate limit") {
            return "Too many attempts. Please wait a minute and try again."
        }
        if message.contains("not enabled") || message.lowercased().contains("provider") {
            return "This sign-in provider isn't set up yet. Use email and password for now."
        }
        if message.contains("Internet connection") || message.contains("offline") {
            return "We couldn't reach the sign-in service. Check your connection and try again."
        }
        return message
    }
}

nonisolated enum AuthFlowError: LocalizedError, Sendable {
    case noCode
    case server(message: String)

    var errorDescription: String? {
        switch self {
        case .noCode: "We didn't get a response from your provider. Please try again."
        case .server(let message): message
        }
    }
}

/// Hosts the OAuth sheet on the key window.
final class WebAuthPresentationContext: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = WebAuthPresentationContext()

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}
