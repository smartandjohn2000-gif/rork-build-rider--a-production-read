package com.rork.rideax.data

import com.rork.rideax.Config
import com.rork.rideax.model.AppRole
import com.rork.rideax.model.DriverApprovalStatus
import com.rork.rideax.model.FareQuote
import com.rork.rideax.model.Ride
import com.rork.rideax.model.Vehicle
import com.rork.rideax.model.round2
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Row shapes for the Postgres tables — the same columns the iOS app reads and
// writes, kept separate from the domain models so schema changes stay contained.

@Serializable
data class ProfileRow(
    val id: String,
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String = "rider",
    val rating: Double = 5.0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class ProfileUpsert(
    val id: String,
    val email: String?,
    @SerialName("full_name") val fullName: String?,
    val phone: String?,
    @SerialName("avatar_url") val avatarUrl: String?,
    val role: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RoleUpdate(val role: String, @SerialName("updated_at") val updatedAt: String)

@Serializable
private data class DriverProfileUpsert(
    val id: String,
    @SerialName("approval_status") val approvalStatus: String,
    @SerialName("is_online") val isOnline: Boolean,
    @SerialName("vehicle_make") val vehicleMake: String? = null,
    @SerialName("vehicle_model") val vehicleModel: String? = null,
    @SerialName("vehicle_year") val vehicleYear: Int? = null,
    @SerialName("vehicle_color") val vehicleColor: String? = null,
    @SerialName("vehicle_plate") val vehiclePlate: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RideInsert(
    @SerialName("rider_id") val riderId: String,
    val status: String,
    @SerialName("ride_class") val rideClass: String,
    @SerialName("pickup_name") val pickupName: String,
    @SerialName("pickup_subtitle") val pickupSubtitle: String?,
    @SerialName("pickup_lat") val pickupLat: Double,
    @SerialName("pickup_lng") val pickupLng: Double,
    @SerialName("dropoff_name") val dropoffName: String,
    @SerialName("dropoff_subtitle") val dropoffSubtitle: String?,
    @SerialName("dropoff_lat") val dropoffLat: Double,
    @SerialName("dropoff_lng") val dropoffLng: Double,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("duration_min") val durationMin: Int,
    val currency: String,
    @SerialName("fare_total") val fareTotal: Double,
    @SerialName("surge_multiplier") val surgeMultiplier: Double,
    @SerialName("fare_breakdown") val fareBreakdown: FareQuote,
)

/** Null fields are omitted from the payload, so one type serves every partial update. */
@Serializable
data class RideUpdate(
    val status: String? = null,
    @SerialName("driver_name") val driverName: String? = null,
    @SerialName("driver_plate") val driverPlate: String? = null,
    @SerialName("driver_vehicle") val driverVehicle: String? = null,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = Dates.isoNow(),
)

@Serializable
private data class IdRow(val id: String)

@Serializable
data class PaymentRow(
    val id: String,
    @SerialName("ride_id") val rideId: String,
    @SerialName("amount_cents") val amountCents: Int,
    val currency: String,
    val status: String,
    @SerialName("payment_method_label") val paymentMethodLabel: String? = null,
    @SerialName("receipt_url") val receiptUrl: String? = null,
    @SerialName("failure_message") val failureMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val amount: Double get() = amountCents / 100.0
}

@Serializable
private data class IntentRequest(val rideId: String, val stripeApiVersion: String)

/** What `create-ride-payment` returns — everything Stripe's sheet needs. */
@Serializable
data class StripeIntent(
    val paymentId: String,
    val paymentIntentClientSecret: String,
    val ephemeralKeySecret: String? = null,
    val customerId: String? = null,
    val publishableKey: String,
    val amountCents: Int,
    val currency: String,
)

/** Payment failures the ride screen can act on. */
sealed class StripeError(message: String) : Exception(message) {
    data object NotConfigured : StripeError("Card payments aren't switched on yet for this account.")
    data object AlreadyPaid : StripeError("This trip has already been paid.")
    class Server(message: String) : StripeError(message)
}

/**
 * Typed access to the shared Postgres tables and the payment edge function.
 *
 * Every request carries the signed-in person's Rork JWT, so row-level
 * security applies exactly as it does for the iOS app. Nothing here is
 * optimistic: callers decide whether a failure is shown or just logged.
 */
class BackendService(private val auth: AuthManager) {
    private val baseUrl = Config.EXPO_PUBLIC_SUPABASE_URL.trimEnd('/')
    private val anonKey = Config.EXPO_PUBLIC_SUPABASE_ANON_KEY

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    /** The signed-in person's JWT, so Postgres row-level security applies. */
    private suspend fun bearer(): String = auth.accessToken() ?: anonKey

    private fun HttpRequestBuilder.authorize(bearer: String, prefer: String? = null) {
        header("apikey", anonKey)
        header("Authorization", "Bearer $bearer")
        prefer?.let { header("Prefer", it) }
    }

    private fun <T> list(serializer: KSerializer<T>, text: String): List<T> =
        Http.json.decodeFromString(ListSerializer(serializer), text)

    // Profiles

    /** Writes the signed-in person into `profiles`, which every other table references. */
    suspend fun syncProfile(id: String, email: String?, fullName: String?, phone: String?, avatarUrl: String?, role: AppRole): ProfileRow? {
        val token = bearer()
        Http.client.post("$baseUrl/rest/v1/profiles") {
            authorize(token, "resolution=merge-duplicates,return=minimal")
            setBody(Http.jsonBody(ProfileUpsert.serializer(), ProfileUpsert(id, email, fullName, phone, avatarUrl, role.wire, Dates.isoNow())))
        }.textOrThrow()
        return fetchProfile(id)
    }

    suspend fun fetchProfile(id: String): ProfileRow? {
        val token = bearer()
        val text = Http.client.get("$baseUrl/rest/v1/profiles?id=eq.$id&select=id,email,full_name,phone,avatar_url,role,rating,created_at&limit=1") {
            authorize(token)
        }.textOrThrow()
        return list(ProfileRow.serializer(), text).firstOrNull()
    }

    suspend fun updateRole(id: String, role: AppRole) {
        val token = bearer()
        Http.client.patch("$baseUrl/rest/v1/profiles?id=eq.$id") {
            authorize(token, "return=minimal")
            setBody(Http.jsonBody(RoleUpdate.serializer(), RoleUpdate(role.wire, Dates.isoNow())))
        }.textOrThrow()
    }

    /** Creates the driver row on first use and never resets an existing approval. */
    suspend fun ensureDriverProfile(id: String) {
        val token = bearer()
        Http.client.post("$baseUrl/rest/v1/driver_profiles?on_conflict=id") {
            authorize(token, "resolution=ignore-duplicates,return=minimal")
            setBody(
                Http.jsonBody(
                    DriverProfileUpsert.serializer(),
                    DriverProfileUpsert(id, DriverApprovalStatus.NOT_SUBMITTED.wire, false, updatedAt = Dates.isoNow()),
                )
            )
        }.textOrThrow()
    }

    /** Pushes the driver's live state: approval, availability and vehicle. */
    suspend fun syncDriverProfile(id: String, approval: DriverApprovalStatus, isOnline: Boolean, vehicle: Vehicle?) {
        val token = bearer()
        Http.client.post("$baseUrl/rest/v1/driver_profiles?on_conflict=id") {
            authorize(token, "resolution=merge-duplicates,return=minimal")
            setBody(
                Http.jsonBody(
                    DriverProfileUpsert.serializer(),
                    DriverProfileUpsert(
                        id, approval.wire, isOnline,
                        vehicle?.make, vehicle?.model, vehicle?.year, vehicle?.color, vehicle?.plate,
                        Dates.isoNow(),
                    ),
                )
            )
        }.textOrThrow()
    }

    // Rides

    /** Persists a newly requested ride; its id is what Stripe later charges against. */
    suspend fun createRide(riderId: String, ride: Ride, quote: FareQuote): String {
        val insert = RideInsert(
            riderId = riderId,
            status = ride.status.wire,
            rideClass = ride.rideClass.id,
            pickupName = ride.pickup.name,
            pickupSubtitle = ride.pickup.subtitle,
            pickupLat = ride.pickup.point.latitude,
            pickupLng = ride.pickup.point.longitude,
            dropoffName = ride.dropoff.name,
            dropoffSubtitle = ride.dropoff.subtitle,
            dropoffLat = ride.dropoff.point.latitude,
            dropoffLng = ride.dropoff.point.longitude,
            distanceKm = round2(quote.kilometres),
            durationMin = quote.minutes,
            currency = quote.currencyCode,
            fareTotal = quote.total,
            surgeMultiplier = quote.surgeMultiplier,
            fareBreakdown = quote,
        )
        val token = bearer()
        val text = Http.client.post("$baseUrl/rest/v1/rides?select=id") {
            authorize(token, "return=representation")
            setBody(Http.jsonBody(RideInsert.serializer(), insert))
        }.textOrThrow()
        return list(IdRow.serializer(), text).first().id
    }

    suspend fun updateRide(id: String, update: RideUpdate) {
        val token = bearer()
        Http.client.patch("$baseUrl/rest/v1/rides?id=eq.$id") {
            authorize(token, "return=minimal")
            setBody(Http.jsonBody(RideUpdate.serializer(), update))
        }.textOrThrow()
    }

    // Payments

    suspend fun fetchPayment(rideId: String): PaymentRow? {
        val token = bearer()
        val text = Http.client.get(
            "$baseUrl/rest/v1/payments?ride_id=eq.$rideId&select=id,ride_id,amount_cents,currency,status,payment_method_label,receipt_url,failure_message,created_at&limit=1"
        ) { authorize(token) }.textOrThrow()
        return list(PaymentRow.serializer(), text).firstOrNull()
    }

    /**
     * Stripe confirms through the webhook, so after the sheet closes give the
     * backend a moment before reading the authoritative payment for the receipt.
     */
    suspend fun awaitSettledPayment(rideId: String, attempts: Int = 5): PaymentRow? {
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(700)
            val payment = runCatching { fetchPayment(rideId) }.getOrNull()
            if (payment != null && (payment.status == "succeeded" || payment.status == "failed")) return payment
        }
        return runCatching { fetchPayment(rideId) }.getOrNull()
    }

    /** Asks the edge function for a PaymentIntent priced from the stored ride. */
    suspend fun createRidePayment(rideId: String): StripeIntent {
        val token = bearer()
        val response = Http.client.post("$baseUrl/functions/v1/create-ride-payment") {
            authorize(token)
            setBody(Http.jsonBody(IntentRequest.serializer(), IntentRequest(rideId, STRIPE_ANDROID_API_VERSION)))
        }
        val text = try {
            response.textOrThrow()
        } catch (failure: HttpFailure) {
            val code = runCatching {
                Http.json.parseToJsonElement(failure.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw when {
                failure.status == 503 || code == "stripe_not_configured" -> StripeError.NotConfigured
                failure.status == 409 || code == "already_paid" -> StripeError.AlreadyPaid
                failure.status == 401 -> StripeError.Server("Please sign in again to pay for this trip.")
                else -> StripeError.Server("Payment couldn't be started (${failure.status}).")
            }
        }
        return Http.json.decodeFromString(StripeIntent.serializer(), text)
    }

    private companion object {
        /**
         * The Stripe API version the Android SDK talks to. Ephemeral keys must
         * be minted for the SDK's version or saved cards fail to load.
         */
        const val STRIPE_ANDROID_API_VERSION = "2020-03-02"
    }
}
