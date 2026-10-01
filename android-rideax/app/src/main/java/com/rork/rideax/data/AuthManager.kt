package com.rork.rideax.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.rork.rideax.Config
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/** The identity providers RideAX accepts. */
enum class AuthProvider(val wire: String, val title: String) {
    APPLE("apple", "Continue with Apple"),
    GOOGLE("google", "Continue with Google"),
}

@Serializable
data class AuthUser(
    val id: String,
    val email: String = "",
    val name: String? = null,
    val picture: String? = null,
)

/** Thrown for sign-in failures that should be shown to the person. */
class AuthFlowError(message: String) : Exception(message)

/**
 * Owns the OAuth handshake and the tokens that come out of it.
 *
 * The app never sees a password: Apple or Google return an authorization code
 * through a Custom Tab, which is exchanged (with PKCE) for a short-lived JWT and
 * a refresh token. Both live in the Keystore-backed [SecureStore], and the JWT
 * is what Postgres row-level security reads to decide which rides and payments
 * this person may touch — the same identity the iOS app uses.
 */
class AuthManager(private val secure: SecureStore) {
    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshLock = Mutex()

    private val authUrl = Config.EXPO_PUBLIC_RORK_AUTH_URL.trimEnd('/')
    private val appKey = Config.EXPO_PUBLIC_RORK_APP_KEY
    private val callbackScheme = "rork-${Config.EXPO_PUBLIC_PROJECT_ID}"

    private var codeVerifier: String? = null
    private var pendingCode: CompletableDeferred<String?>? = null
    private var launchedAt: Long = 0

    /** Restores a previous session on launch, refreshing the token if needed. */
    suspend fun restore() {
        val access = secure.get(ACCESS)
        if (access != null) {
            val claims = claims(access)
            if (claims != null && !claims.isExpired(leewaySeconds = 0)) {
                _user.value = claims.user
                return
            }
        }
        if (secure.get(REFRESH) != null) refresh()
    }

    /**
     * Runs the full sign-in. Returns normally when signed in or when the person
     * closed the browser; throws [AuthFlowError] for anything worth showing.
     */
    suspend fun signIn(provider: AuthProvider, context: Context) {
        val verifier = randomUrlSafe(32)
        codeVerifier = verifier

        val initiate = post(
            "/oauth/initiate",
            InitiateRequest(appKey, provider.wire, challenge(verifier), target = "rn", env = "native"),
            InitiateRequest.serializer(),
        )
        val authUrl = initiate["auth_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AuthFlowError("Sign-in is misconfigured. Please contact support.")

        val deferred = CompletableDeferred<String?>()
        pendingCode = deferred
        launchedAt = System.currentTimeMillis()

        CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF0D1830.toInt())
                    .setNavigationBarColor(0xFF0D1830.toInt())
                    .build()
            )
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(authUrl))

        val code = deferred.await()
        pendingCode = null
        if (code == null) {
            codeVerifier = null
            return
        }
        exchange(code)
    }

    /** Called by the activity for every incoming deep link. */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != callbackScheme || uri.host != "auth") return false
        val code = uri.getQueryParameter("code")
        val deferred = pendingCode ?: return true
        if (code.isNullOrBlank()) {
            deferred.completeExceptionally(
                AuthFlowError(uri.getQueryParameter("error_description") ?: "We didn't get a response from your provider. Please try again.")
            )
        } else {
            deferred.complete(code)
        }
        return true
    }

    /**
     * When the person closes the Custom Tab without finishing, no redirect ever
     * arrives. Returning to the app with sign-in still pending means cancelled.
     */
    fun onHostResumed() {
        val deferred = pendingCode ?: return
        if (System.currentTimeMillis() - launchedAt < 700) return
        scope.launch {
            delay(900)
            if (pendingCode === deferred && !deferred.isCompleted) deferred.complete(null)
        }
    }

    fun signOut() {
        secure.remove(ACCESS)
        secure.remove(REFRESH)
        _user.value = null
    }

    /**
     * A valid bearer token for Postgres and the edge functions, refreshed
     * transparently when it is about to expire.
     */
    suspend fun accessToken(): String? {
        val current = secure.get(ACCESS)
        val claims = current?.let(::claims)
        if (current != null && claims != null && !claims.isExpired(leewaySeconds = 60)) return current
        if (secure.get(REFRESH) == null) return null
        refresh()
        return secure.get(ACCESS)
    }

    private suspend fun exchange(code: String) {
        val verifier = codeVerifier ?: throw AuthFlowError("Sign-in expired. Please try again.")
        codeVerifier = null
        val token = post("/oauth/token", TokenRequest(appKey, code, verifier), TokenRequest.serializer())
        val access = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw AuthFlowError("Sign-in failed. Please try again.")
        val refresh = token["refresh_token"]?.jsonPrimitive?.contentOrNull
        secure.put(ACCESS, access)
        refresh?.let { secure.put(REFRESH, it) }

        val fromResponse = token["user"]?.let {
            runCatching { Http.json.decodeFromJsonElement(AuthUser.serializer(), it) }.getOrNull()
        }
        _user.value = fromResponse ?: claims(access)?.user
    }

    private suspend fun refresh() {
        refreshLock.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        // Another caller may have refreshed while we waited for the lock.
        val fresh = secure.get(ACCESS)?.let(::claims)?.takeIf { !it.isExpired(leewaySeconds = 60) }
        if (fresh != null) {
            _user.value = fresh.user
            return
        }
        val refreshToken = secure.get(REFRESH)
        if (refreshToken == null) {
            _user.value = null
            return
        }
        try {
            val response = post("/oauth/refresh", RefreshRequest(appKey, refreshToken), RefreshRequest.serializer())
            val access = response["access_token"]?.jsonPrimitive?.contentOrNull
            if (access == null) {
                signOut()
            } else {
                secure.put(ACCESS, access)
                _user.value = claims(access)?.user
            }
        } catch (error: HttpFailure) {
            // A rejected refresh token means the session is over.
            if (error.status in 400..499) signOut()
        } catch (error: Exception) {
            // Offline: keep the cached identity, the next call retries.
            Log.w(TAG, "Token refresh deferred: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun <T> post(path: String, body: T, serializer: kotlinx.serialization.KSerializer<T>): JsonObject {
        val response = Http.client.post("$authUrl$path") { setBody(Http.jsonBody(serializer, body)) }
        val text = try {
            response.textOrThrow()
        } catch (failure: HttpFailure) {
            val message = runCatching {
                Http.json.parseToJsonElement(failure.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (path == "/oauth/refresh") throw failure
            throw AuthFlowError(message ?: "Sign-in failed (${failure.status}). Please try again.")
        }
        return Http.json.parseToJsonElement(text).jsonObject
    }

    private class Claims(val user: AuthUser, val expiresAt: Long?) {
        fun isExpired(leewaySeconds: Long): Boolean =
            expiresAt != null && expiresAt * 1000 < System.currentTimeMillis() + leewaySeconds * 1000
    }

    /** Reads the claims of a token Rork minted and we stored ourselves. */
    private fun claims(token: String): Claims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = Http.json.parseToJsonElement(payload).jsonObject
            val sub = json["sub"]?.jsonPrimitive?.contentOrNull ?: return null
            Claims(
                AuthUser(
                    id = sub,
                    email = json["email"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = json["name"]?.jsonPrimitive?.contentOrNull,
                    picture = json["picture"]?.jsonPrimitive?.contentOrNull,
                ),
                json["exp"]?.jsonPrimitive?.longOrNull,
            )
        }.getOrNull()
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    @Serializable
    private data class InitiateRequest(
        val app_key: String,
        val provider: String,
        val code_challenge: String,
        val target: String,
        val env: String,
    )

    @Serializable
    private data class TokenRequest(val app_key: String, val code: String, val code_verifier: String)

    @Serializable
    private data class RefreshRequest(val app_key: String, val refresh_token: String)

    private companion object {
        const val TAG = "RideAX.Auth"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
    }
}
