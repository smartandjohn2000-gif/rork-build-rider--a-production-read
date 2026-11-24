package com.rork.rideax.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.rork.rideax.Config
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/** The sign-in providers RideAX accepts. */
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
 * Owns the signed-in identity.
 *
 * People register with email and password, or continue with Apple or Google.
 * All three flow through native Supabase Auth, whose JWT is what Postgres
 * row-level security reads to decide which rides and payments this person may
 * touch. Tokens live in the Keystore-backed [SecureStore].
 */
class AuthManager(private val secure: SecureStore) {
    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshLock = Mutex()

    private val supabaseUrl = Config.EXPO_PUBLIC_SUPABASE_URL.trimEnd('/')
    private val anonKey = Config.EXPO_PUBLIC_SUPABASE_ANON_KEY
    private val callbackScheme = "rork-${Config.EXPO_PUBLIC_PROJECT_ID}"

    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    private var codeVerifier: String? = null
    private var pendingResult: CompletableDeferred<Uri?>? = null
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
     * Email & password sign-in, or account creation when [isSignUp] is set.
     * Throws [AuthFlowError] with a person-readable message on failure.
     */
    suspend fun authenticate(email: String, password: String, fullName: String?, isSignUp: Boolean) {
        val response: JsonObject = if (isSignUp) {
            postJson(
                "/auth/v1/signup",
                SignUpBody(email, password, data = fullName?.takeIf { it.isNotBlank() }?.let { mapOf("full_name" to it) }),
            )
        } else {
            postJson("/auth/v1/token?grant_type=password", PasswordBody(email, password))
        }
        adoptSession(response)
    }

    /**
     * Runs Apple / Google through the system browser. The provider must be
     * configured on the backend first; until then the error says so plainly.
     */
    suspend fun signIn(provider: AuthProvider, context: Context) {
        val verifier = randomUrlSafe(32)
        codeVerifier = verifier

        val authorize = "$supabaseUrl/auth/v1/authorize" +
            "?provider=${provider.wire}" +
            "&redirect_to=${Uri.encode(callbackUri())}" +
            "&code_challenge=${challenge(verifier)}" +
            "&code_challenge_method=s256"

        val deferred = CompletableDeferred<Uri?>()
        pendingResult = deferred
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
            .launchUrl(context, Uri.parse(authorize))

        val callback = deferred.await()
        pendingResult = null
        codeVerifier = null
        if (callback == null) return

        val code = callback.getQueryParameter("code")
        val fragment = callback.fragment
        if (code.isNullOrBlank() && fragment != null && fragment.contains("access_token=")) {
            adoptImplicitTokens(fragment)
        } else if (code.isNullOrBlank()) {
            throw AuthFlowError(callback.getQueryParameter("error_description") ?: authNotSetUpMessage(provider))
        } else {
            val session = postJson("/auth/v1/token?grant_type=pkce", PkceBody(code, verifier))
            adoptSession(session)
        }
    }

    /** Called by the activity for every incoming deep link. */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != callbackScheme || uri.host != "auth") return false
        val deferred = pendingResult ?: return true
        deferred.complete(uri)
        return true
    }

    /**
     * When the person closes the browser without finishing, no redirect ever
     * arrives. Returning to the app with sign-in still pending means cancelled.
     */
    fun onHostResumed() {
        val deferred = pendingResult ?: return
        if (System.currentTimeMillis() - launchedAt < 700) return
        scope.launch {
            delay(900)
            if (pendingResult === deferred && !deferred.isCompleted) deferred.complete(null)
        }
    }

    fun signOut() {
        val token = secure.get(ACCESS)
        secure.remove(ACCESS)
        secure.remove(REFRESH)
        _user.value = null
        if (token != null) scope.launch {
            runCatching {
                Http.client.post("$supabaseUrl/auth/v1/logout?scope=global") {
                    header("apikey", anonKey)
                    header("Authorization", "Bearer $token")
                }
            }
        }
    }

    /**
     * Deletes the account and its data server-side, then clears the session.
     * The caller confirms intent first; failures must be surfaced, not absorbed.
     */
    suspend fun deleteAccount() {
        val token = accessToken() ?: throw AuthFlowError("Your session has expired. Please sign in again.")
        try {
            Http.client.post("$supabaseUrl/functions/v1/delete-account") {
                header("apikey", anonKey)
                header("Authorization", "Bearer $token")
            }.textOrThrow()
        } catch (failure: HttpFailure) {
            throw AuthFlowError("Account deletion failed. Please try again.")
        }
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

    // MARK: - Session plumbing

    private fun callbackUri(): String = "$callbackScheme://auth/callback"

    private fun authNotSetUpMessage(provider: AuthProvider): String =
        "${provider.wire.replaceFirstChar { it.uppercase() }} sign-in isn't set up yet. Use email and password for now."

    /** Stores the tokens of a sign-up / sign-in response and exposes the user. */
    private suspend fun adoptSession(response: JsonObject) {
        val access = response["access_token"]?.jsonPrimitive?.contentOrNull
        if (access == null) {
            // Without auto-confirmed email the response carries a user but no
            // session; the account exists but cannot sign in yet.
            throw AuthFlowError("Check your inbox to confirm your email, then sign in.")
        }
        val refresh = response["refresh_token"]?.jsonPrimitive?.contentOrNull
        secure.put(ACCESS, access)
        refresh?.let { secure.put(REFRESH, it) }
        _user.value = claims(access)?.user ?: response["user"]?.let(::decodeUser)
    }

    private fun adoptImplicitTokens(fragment: String) {
        val params = fragment.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to Uri.decode(parts[1]) else null
        }.toMap()
        val access = params["access_token"]
        if (access.isNullOrBlank()) throw AuthFlowError("We didn't get a response from your provider. Please try again.")
        secure.put(ACCESS, access)
        params["refresh_token"]?.let { secure.put(REFRESH, it) }
        _user.value = claims(access)?.user
    }

    private fun decodeUser(element: JsonElement): AuthUser? = runCatching {
        Http.json.decodeFromJsonElement(AuthUser.serializer(), element)
    }.getOrNull()

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
            val response = postJson("/auth/v1/token?grant_type=refresh_token", RefreshBody(refreshToken))
            val access = response["access_token"]?.jsonPrimitive?.contentOrNull
            if (access == null) {
                signOut()
            } else {
                secure.put(ACCESS, access)
                response["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { secure.put(REFRESH, it) }
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

    private suspend fun postJson(path: String, body: Any): JsonObject {
        val response = Http.client.post("$supabaseUrl$path") {
            header("apikey", anonKey)
            setBody(Http.jsonBody(body))
        }
        val text = try {
            response.textOrThrow()
        } catch (failure: HttpFailure) {
            throw AuthFlowError(describeAuthFailure(failure))
        }
        return Http.json.parseToJsonElement(text).jsonObject
    }

    private fun describeAuthFailure(failure: HttpFailure): String = runCatching {
        val error = Http.json.parseToJsonElement(failure.body).jsonObject
        error["error_description"]?.jsonPrimitive?.contentOrNull
            ?: error["msg"]?.jsonPrimitive?.contentOrNull
            ?: error["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.let { message ->
        when {
            message.contains("Invalid login credentials") -> "Email or password is incorrect."
            message.contains("already registered") -> "An account with this email already exists. Sign in instead."
            message.contains("Password should be") -> "Your password needs at least 8 characters."
            message.contains("not enabled", ignoreCase = true) || message.contains("provider", ignoreCase = true) ->
                "This sign-in provider isn't set up yet. Use email and password for now."
            message.contains("rate limit", ignoreCase = true) -> "Too many attempts. Please wait a minute and try again."
            else -> message
        }
    } ?: when {
        failure.status == 401 -> "Email or password is incorrect."
        failure.status in 500..599 -> "The sign-in service is temporarily unavailable. Please try again."
        else -> "Sign-in failed (${failure.status}). Please try again."
    }

    private class Claims(val user: AuthUser, val expiresAt: Long?) {
        fun isExpired(leewaySeconds: Long): Boolean =
            expiresAt != null && expiresAt * 1000 < System.currentTimeMillis() + leewaySeconds * 1000
    }

    /** Reads the claims of a Supabase session token we stored ourselves. */
    private fun claims(token: String): Claims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = Http.json.parseToJsonElement(payload).jsonObject
            val sub = json["sub"]?.jsonPrimitive?.contentOrNull ?: return null
            val metadata = json["user_metadata"]?.jsonObject
            Claims(
                AuthUser(
                    id = sub,
                    email = json["email"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = metadata?.get("full_name")?.jsonPrimitive?.contentOrNull
                        ?: metadata?.get("name")?.jsonPrimitive?.contentOrNull,
                    picture = metadata?.get("picture")?.jsonPrimitive?.contentOrNull
                        ?: metadata?.get("avatar_url")?.jsonPrimitive?.contentOrNull,
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
    private data class SignUpBody(val email: String, val password: String, val data: Map<String, String>? = null)

    @Serializable
    private data class PasswordBody(val email: String, val password: String)

    @Serializable
    private data class PkceBody(val auth_code: String, val code_verifier: String)

    @Serializable
    private data class RefreshBody(val refresh_token: String)

    private companion object {
        const val TAG = "RideAX.Auth"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
    }
}
