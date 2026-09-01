import SwiftUI
import AuthenticationServices
import CryptoKit

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
}

/// Owns the OAuth handshake and the tokens that come out of it.
///
/// The app never sees a password: the provider returns an authorization code,
/// which is exchanged for a short-lived JWT plus a refresh token. Both live in
/// the Keychain, and the JWT is what Postgres row-level security reads to
/// decide which rides and payments this person is allowed to touch.
@Observable
final class AuthManager {
    static let shared = AuthManager()

    private(set) var user: AuthUser?
    private(set) var isRestoring: Bool = true
    private(set) var isSigningIn: Bool = false
    private(set) var errorMessage: String?

    private let authURL = Config.EXPO_PUBLIC_RORK_AUTH_URL
    private let appKey = Config.EXPO_PUBLIC_RORK_APP_KEY
    private let projectID = Config.EXPO_PUBLIC_PROJECT_ID
    private var codeVerifier: String?
    private var webAuthSession: ASWebAuthenticationSession?

    /// Injected by Rork into UserDefaults at install time on the iOS Simulator
    /// only. Read fresh every time: the write can land after the app process
    /// has already started, and caching it would freeze `nil` for the session.
    private var developerHint: String? {
        UserDefaults.standard.string(forKey: "RORK_DEVELOPER_HINT")
    }

    nonisolated struct AuthUser: Codable, Sendable, Hashable {
        let id: String
        let email: String
        let name: String?
        let picture: String?
    }

    private init() {}

    /// The bearer token Supabase attaches to every request. Deliberately
    /// `nonisolated` and synchronous — the Supabase client calls it off the
    /// main actor on every query.
    nonisolated static func currentAccessToken() -> String? {
        KeychainHelper.get("access_token")
    }

    var isSignedIn: Bool { user != nil }

    // MARK: - Session lifecycle

    /// Restores a previous session on launch, refreshing the token if needed.
    func restore() async {
        defer { isRestoring = false }

        if let accessToken = KeychainHelper.get("access_token"),
           let user = userFromToken(accessToken) {
            self.user = user
            return
        }

        if storedRefreshToken() != nil {
            await refreshToken()
        }
    }

    func signIn(provider: AuthProvider) async {
        isSigningIn = true
        errorMessage = nil
        defer { isSigningIn = false }

        do {
            let verifier = generateCodeVerifier()
            codeVerifier = verifier

            let initiate = try await initiateOAuth(
                provider: provider,
                challenge: generateCodeChallenge(from: verifier)
            )

            let code: String
            if initiate.flow == "popup" {
                do {
                    code = try await pollForCode(state: initiate.state)
                } catch AuthFlowError.cancelledByUser {
                    // The developer chose the simulator instead of the host
                    // browser; the backend already flipped this state to
                    // in-app, so the same auth_url now redirects to our scheme.
                    code = try await runWebAuthSession(authURL: initiate.auth_url)
                }
            } else {
                code = try await runWebAuthSession(authURL: initiate.auth_url)
            }

            try await exchangeCode(code)
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            return
        } catch let error as AuthFlowError {
            errorMessage = error.errorDescription
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func signOut() {
        KeychainHelper.delete("access_token")
        KeychainHelper.delete("refresh_token")
        UserDefaults.standard.removeObject(forKey: "RORK_AUTH_REFRESH_TOKEN")
        user = nil
        errorMessage = nil
    }

    func clearError() {
        errorMessage = nil
    }

    // MARK: - OAuth steps

    private func initiateOAuth(provider: AuthProvider, challenge: String) async throws -> InitiateResponse {
        guard let url = URL(string: "\(authURL)/oauth/initiate") else { throw AuthFlowError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        var body: [String: String] = [
            "app_key": appKey,
            "provider": provider.rawValue,
            "code_challenge": challenge,
            "target": "swift",
            "env": authEnv
        ]
        if authEnv == "simulator", let developerHint {
            body["developer_hint"] = developerHint
        }
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            if let failure = try? JSONDecoder().decode(ErrorResponse.self, from: data) {
                throw AuthFlowError.server(message: failure.error)
            }
            throw AuthFlowError.serverStatus(status)
        }
        return try JSONDecoder().decode(InitiateResponse.self, from: data)
    }

    /// Used when the OAuth window opens in the developer's browser rather than
    /// inside the simulator. Polls until the callback stores the code.
    private func pollForCode(state: String) async throws -> String {
        guard let url = URL(string: "\(authURL)/oauth/poll-code") else { throw AuthFlowError.invalidURL }
        let deadline = Date().addingTimeInterval(5 * 60)

        while Date() < deadline {
            try await Task.sleep(for: .milliseconds(1500))

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(["app_key": appKey, "state": state])

            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let poll = try? JSONDecoder().decode(PollCodeResponse.self, from: data) else { continue }

            if poll.status == "cancelled" { throw AuthFlowError.cancelledByUser }
            if poll.status == "ready", let code = poll.code { return code }
        }

        throw AuthFlowError.popupTimeout
    }

    private func runWebAuthSession(authURL authURLString: String) async throws -> String {
        let callbackScheme = "rork-\(projectID)"
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            guard let url = URL(string: authURLString) else {
                continuation.resume(throwing: AuthFlowError.invalidURL)
                return
            }

            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                self?.webAuthSession = nil

                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                guard let callbackURL,
                      let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false),
                      let code = components.queryItems?.first(where: { $0.name == "code" })?.value else {
                    continuation.resume(throwing: AuthFlowError.noCode)
                    return
                }

                continuation.resume(returning: code)
            }

            self.webAuthSession = session
            session.presentationContextProvider = WebAuthPresentationContext.shared
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
    }

    private func exchangeCode(_ code: String) async throws {
        guard let verifier = codeVerifier else { throw AuthFlowError.noCode }
        codeVerifier = nil

        guard let url = URL(string: "\(authURL)/oauth/token") else { throw AuthFlowError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode([
            "app_key": appKey,
            "code": code,
            "code_verifier": verifier
        ])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            if let failure = try? JSONDecoder().decode(ErrorResponse.self, from: data) {
                throw AuthFlowError.server(message: failure.error)
            }
            throw AuthFlowError.serverStatus(status)
        }

        let token = try JSONDecoder().decode(TokenResponse.self, from: data)
        KeychainHelper.set("access_token", value: token.access_token)
        KeychainHelper.set("refresh_token", value: token.refresh_token)
        user = token.user
    }

    private func refreshToken() async {
        guard let refresh = storedRefreshToken(),
              let url = URL(string: "\(authURL)/oauth/refresh") else {
            user = nil
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(["app_key": appKey, "refresh_token": refresh])

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                signOut()
                return
            }
            let refreshed = try JSONDecoder().decode(RefreshResponse.self, from: data)
            KeychainHelper.set("access_token", value: refreshed.access_token)
            user = userFromToken(refreshed.access_token)
        } catch {
            signOut()
        }
    }

    // MARK: - Helpers

    private var authEnv: String {
        #if targetEnvironment(simulator)
        return "simulator"
        #else
        return "native"
        #endif
    }

    /// Simulators can't always reach the Keychain, so Rork injects the refresh
    /// token into UserDefaults there. Keychain remains the source on device.
    private func storedRefreshToken() -> String? {
        #if targetEnvironment(simulator)
        if let injected = UserDefaults.standard.string(forKey: "RORK_AUTH_REFRESH_TOKEN") {
            return injected
        }
        #endif
        return KeychainHelper.get("refresh_token")
    }

    /// Reads the claims out of a token we issued and stored ourselves — the
    /// signature was already verified by Rork when it was minted.
    private func userFromToken(_ token: String) -> AuthUser? {
        let parts = token.split(separator: ".")
        guard parts.count == 3 else { return nil }

        var base64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }

        guard let data = Data(base64Encoded: base64) else { return nil }

        nonisolated struct JWTPayload: Codable, Sendable {
            let sub: String
            let email: String?
            let name: String?
            let picture: String?
            let exp: TimeInterval?
        }

        guard let payload = try? JSONDecoder().decode(JWTPayload.self, from: data) else { return nil }
        if let exp = payload.exp, Date(timeIntervalSince1970: exp) < Date() { return nil }

        return AuthUser(id: payload.sub, email: payload.email ?? "", name: payload.name, picture: payload.picture)
    }

    private func generateCodeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).urlSafeBase64
    }

    private func generateCodeChallenge(from verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).urlSafeBase64
    }
}

private extension Data {
    var urlSafeBase64: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

// MARK: - Wire types

private nonisolated struct InitiateResponse: Codable, Sendable {
    let auth_url: String
    let state: String
    let flow: String?
}

private nonisolated struct PollCodeResponse: Codable, Sendable {
    let status: String
    let code: String?
}

private nonisolated struct TokenResponse: Codable, Sendable {
    let access_token: String
    let refresh_token: String
    let user: AuthManager.AuthUser
}

private nonisolated struct RefreshResponse: Codable, Sendable {
    let access_token: String
    let expires_in: Int
}

private nonisolated struct ErrorResponse: Codable, Sendable {
    let error: String
}

nonisolated enum AuthFlowError: LocalizedError, Sendable {
    case noCode
    case invalidURL
    case server(message: String)
    case serverStatus(Int)
    case popupTimeout
    case cancelledByUser

    var errorDescription: String? {
        switch self {
        case .noCode: "We didn't get a response from your provider. Please try again."
        case .invalidURL: "Sign-in is misconfigured. Please contact support."
        case .server(let message): message
        case .serverStatus(let code): "Sign-in failed (\(code)). Please try again."
        case .popupTimeout: "Sign-in timed out. Please try again."
        case .cancelledByUser: "Sign-in cancelled."
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
