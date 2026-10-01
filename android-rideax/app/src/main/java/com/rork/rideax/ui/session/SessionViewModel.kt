package com.rork.rideax.ui.session

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rork.rideax.AppContainer
import com.rork.rideax.data.AuthFlowError
import com.rork.rideax.data.AuthProvider
import com.rork.rideax.data.AuthUser
import com.rork.rideax.data.Dates
import com.rork.rideax.data.Haptics
import com.rork.rideax.data.Persistence
import com.rork.rideax.model.AppRole
import com.rork.rideax.model.PaymentMethod
import com.rork.rideax.model.Place
import com.rork.rideax.model.UserAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
internal data class SessionSnapshot(
    val hasCompletedOnboarding: Boolean,
    val account: UserAccount?,
    val role: AppRole,
    val savedPlaces: List<Place>,
    val paymentMethods: List<PaymentMethod>,
    val notificationsEnabled: Boolean,
)

data class SessionUiState(
    val isRestoring: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
    val account: UserAccount? = null,
    val role: AppRole = AppRole.RIDER,
    val savedPlaces: List<Place> = Place.defaultSaved,
    val paymentMethods: List<PaymentMethod> = PaymentMethod.defaults,
    val notificationsEnabled: Boolean = true,
    val isAuthenticating: Boolean = false,
    val activeProvider: AuthProvider? = null,
    val authError: String? = null,
) {
    val isSignedIn: Boolean get() = account != null
    val defaultPaymentMethod: PaymentMethod? get() = paymentMethods.firstOrNull { it.isDefault } ?: paymentMethods.firstOrNull()
}

/**
 * Owns the signed-in identity, the active role and account preferences.
 *
 * Identity comes from Rork Auth (Apple / Google); the resulting JWT is what
 * Postgres row-level security reads, so the profile here and the rides and
 * payments elsewhere hang off the same user id as on iOS.
 */
class SessionViewModel(private val container: AppContainer) : ViewModel() {
    private val auth = container.auth
    private val backend = container.backend
    private val persistence = container.persistence

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init {
        persistence.load<SessionSnapshot>(Persistence.SESSION)?.let { snapshot ->
            _state.update {
                it.copy(
                    hasCompletedOnboarding = snapshot.hasCompletedOnboarding,
                    account = snapshot.account,
                    role = snapshot.role,
                    savedPlaces = snapshot.savedPlaces.ifEmpty { Place.defaultSaved },
                    paymentMethods = snapshot.paymentMethods.ifEmpty { PaymentMethod.defaults },
                    notificationsEnabled = snapshot.notificationsEnabled,
                )
            }
        }
        viewModelScope.launch { bootstrap() }
    }

    /** Restores tokens on launch and re-syncs the profile row. */
    private suspend fun bootstrap() {
        runCatching { auth.restore() }
        val user = auth.user.value
        if (user != null) {
            adopt(user)
        } else {
            _state.update { it.copy(account = null) }
            persist()
        }
        _state.update { it.copy(isRestoring = false) }
    }

    /**
     * Opens Apple or Google sign-in. On success the role picked during
     * onboarding is kept, so the person lands straight in the right shell.
     */
    fun signIn(provider: AuthProvider, role: AppRole, context: Context) {
        if (_state.value.isAuthenticating) return
        // Carry the role picked during onboarding so the profile syncs with it.
        _state.update { it.copy(isAuthenticating = true, activeProvider = provider, authError = null, role = role) }
        viewModelScope.launch {
            try {
                auth.signIn(provider, context)
                val user = auth.user.value
                if (user != null) {
                    adopt(user)
                    completeOnboarding(role)
                    Haptics.success()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: AuthFlowError) {
                _state.update { it.copy(authError = error.message) }
            } catch (error: Exception) {
                Log.w(TAG, "Sign-in failed: ${error.javaClass.simpleName}")
                _state.update { it.copy(authError = "We couldn't reach the sign-in service. Check your connection and try again.") }
            } finally {
                _state.update { it.copy(isAuthenticating = false, activeProvider = null) }
            }
        }
    }

    fun clearAuthError() = _state.update { it.copy(authError = null) }

    fun signOut() {
        auth.signOut()
        _state.update { it.copy(account = null, hasCompletedOnboarding = false, authError = null) }
        persist()
    }

    fun completeOnboarding(role: AppRole) {
        _state.update { it.copy(role = role, hasCompletedOnboarding = true, account = it.account?.copy(role = role)) }
        persist()
        pushRole(role)
    }

    fun switchRole(role: AppRole) {
        if (role == _state.value.role) return
        _state.update { it.copy(role = role, account = it.account?.copy(role = role)) }
        persist()
        pushRole(role)
        Haptics.success()
    }

    fun makeDefault(method: PaymentMethod) {
        _state.update { state -> state.copy(paymentMethods = state.paymentMethods.map { it.copy(isDefault = it.id == method.id) }) }
        persist()
    }

    fun setNotifications(enabled: Boolean) {
        _state.update { it.copy(notificationsEnabled = enabled) }
        persist()
    }

    fun removeSavedPlace(place: Place) {
        _state.update { state -> state.copy(savedPlaces = state.savedPlaces.filterNot { it.id == place.id }) }
        persist()
    }

    /**
     * Mirrors the authenticated user locally first (so the UI never waits on
     * the network), then reconciles with the `profiles` row.
     */
    private suspend fun adopt(user: AuthUser) {
        val previous = _state.value.account?.takeIf { it.id == user.id }
        var resolved = UserAccount(
            id = user.id,
            fullName = previous?.fullName ?: user.name ?: displayName(user.email),
            email = user.email,
            phone = previous?.phone ?: "",
            role = _state.value.role,
            rating = previous?.rating ?: 5.0,
            memberSince = previous?.memberSince ?: System.currentTimeMillis(),
            avatarUrl = user.picture,
        )
        _state.update { it.copy(account = resolved) }
        persist()

        if (!backend.isConfigured) return
        try {
            val profile = backend.syncProfile(
                id = user.id,
                email = user.email.ifBlank { null },
                fullName = resolved.fullName,
                phone = resolved.phone.ifBlank { null },
                avatarUrl = user.picture,
                role = _state.value.role,
            )
            if (profile != null) {
                resolved = resolved.copy(
                    fullName = profile.fullName ?: resolved.fullName,
                    phone = profile.phone ?: resolved.phone,
                    rating = profile.rating,
                    memberSince = Dates.parse(profile.createdAt) ?: resolved.memberSince,
                )
                _state.update { it.copy(account = resolved) }
                persist()
            }
            if (_state.value.role == AppRole.DRIVER) backend.ensureDriverProfile(user.id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Never block sign-in on a sync failure; it re-syncs next launch.
            Log.w(TAG, "Profile sync failed: ${error.javaClass.simpleName}")
        }
    }

    /** Keeps the server-side role in step with the shell being used. */
    private fun pushRole(role: AppRole) {
        val id = _state.value.account?.id ?: return
        if (!backend.isConfigured) return
        viewModelScope.launch {
            try {
                backend.updateRole(id, role)
                if (role == AppRole.DRIVER) backend.ensureDriverProfile(id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Role sync failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun persist() {
        val s = _state.value
        persistence.save(
            Persistence.SESSION,
            SessionSnapshot(s.hasCompletedOnboarding, s.account, s.role, s.savedPlaces, s.paymentMethods, s.notificationsEnabled),
        )
    }

    private fun displayName(email: String): String {
        val handle = email.substringBefore("@").replace(".", " ").replace("_", " ")
        val name = handle.split(" ").filter { it.isNotBlank() }.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return name.ifBlank { "Rider" }
    }

    private companion object {
        const val TAG = "RideAX.Session"
    }
}
