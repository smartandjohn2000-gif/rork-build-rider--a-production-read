package com.rork.rideax.ui.rider

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rork.rideax.AppContainer
import com.rork.rideax.data.Dates
import com.rork.rideax.data.DemandSnapshot
import com.rork.rideax.data.Haptics
import com.rork.rideax.data.PaymentRow
import com.rork.rideax.data.PricingService
import com.rork.rideax.data.RideUpdate
import com.rork.rideax.data.RouteEstimate
import com.rork.rideax.data.StripeError
import com.rork.rideax.data.StripeIntent
import com.rork.rideax.model.DriverPing
import com.rork.rideax.model.DriverProfile
import com.rork.rideax.model.FareQuote
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.PaymentMethod
import com.rork.rideax.model.Place
import com.rork.rideax.model.PlaceKind
import com.rork.rideax.model.Receipt
import com.rork.rideax.model.Ride
import com.rork.rideax.model.RideClass
import com.rork.rideax.model.RideStatus
import com.rork.rideax.model.ServiceArea
import com.rork.rideax.model.TripRecord
import com.rork.rideax.model.headingAt
import com.rork.rideax.model.lengthMeters
import com.rork.rideax.model.pointAt
import com.rork.rideax.model.round2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Result handed back from Stripe's sheet. */
sealed interface PaymentSheetOutcome {
    data object Completed : PaymentSheetOutcome
    data object Canceled : PaymentSheetOutcome
    data class Failed(val message: String) : PaymentSheetOutcome
}

data class RideUiState(
    val pickup: Place = Place("pickup", "Current location", "Your pickup point", ServiceArea.center, PlaceKind.CURRENT),
    val destination: Place? = null,
    val selectedClassId: String = RideClass.all.first().id,
    val routeEstimate: RouteEstimate? = null,
    val quotes: Map<String, FareQuote> = emptyMap(),
    val isQuoting: Boolean = false,
    val isRepricing: Boolean = false,
    val demand: DemandSnapshot = DemandSnapshot.Normal,
    val quoteUpdatedAt: Long? = null,
    val lastFareChange: Double = 0.0,
    val pickupDriftMeters: Double = 0.0,
    val nearbyDrivers: List<GeoPoint> = emptyList(),
    val ride: Ride? = null,
    val progress: Double = 0.0,
    val etaMinutes: Int = 0,
    val driverPing: DriverPing? = null,
    val driverRoute: List<GeoPoint> = emptyList(),
    val receipt: Receipt? = null,
    val isPaying: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedClass: RideClass get() = RideClass.all.firstOrNull { it.id == selectedClassId } ?: RideClass.all.first()
    val selectedQuote: FareQuote? get() = quotes[selectedClassId]
    val isFareCurrent: Boolean get() = pickupDriftMeters < RideViewModel.SIGNIFICANT_DRIFT_METERS
    val hasActiveRide: Boolean get() = ride?.status?.isActive == true
}

/**
 * Drives the rider side: live fare engine, ride lifecycle and payment.
 * Requested → Matching → Accepted → Arriving → Arrived → Started → In progress → Completed → Paid.
 * Same state machine, pricing and backend contract as the iOS RideStore.
 */
class RideViewModel(private val container: AppContainer) : ViewModel() {
    private val routes = container.routes
    private val backend = container.backend
    private val trips = container.trips
    val pricing = PricingService()

    private val _state = MutableStateFlow(RideUiState())
    val state: StateFlow<RideUiState> = _state.asStateFlow()

    /** Set from the session once signed in; without it rides stay on device. */
    var riderId: String? = null
    private var remoteRideId: String? = null
    private var remoteRideReady: CompletableDeferred<String?>? = null

    private var quoteJob: Job? = null
    private var liveFareJob: Job? = null
    private var lifecycleJob: Job? = null
    private var quotedFrom: GeoPoint? = null
    private var hasLabelledPickup = false

    /** Set by the screen that hosts Stripe's sheet. */
    var presentPaymentSheet: (suspend (StripeIntent) -> PaymentSheetOutcome)? = null

    init {
        seedNearbyDrivers(ServiceArea.center)
        startLiveFareUpdates()
    }

    fun selectedRates(): Pair<Double, Double> = pricing.rates(_state.value.selectedClass)

    /** Place search biased to where the rider is being picked up. */
    suspend fun searchPlaces(query: String): List<Place> = routes.search(query, _state.value.pickup.point)

    // Booking

    fun selectClass(id: String) {
        if (id == _state.value.selectedClassId) return
        _state.update { it.copy(selectedClassId = id, lastFareChange = 0.0) }
        Haptics.tap()
    }

    fun setDestination(place: Place?) {
        _state.update { it.copy(destination = place, lastFareChange = 0.0) }
        refreshQuote()
    }

    fun repriceNow() {
        if (_state.value.destination == null) return
        refreshQuote(silently = _state.value.routeEstimate != null)
    }

    /**
     * Called on every GPS update. The pin follows the rider immediately; a
     * re-route (and a new fare) only fires once they have moved far enough.
     * Fixes outside the service area keep the pickup in the city.
     */
    fun syncPickupWithLocation(point: GeoPoint) {
        if (_state.value.ride != null || !ServiceArea.contains(point)) return
        _state.update { it.copy(pickup = it.pickup.copy(point = point)) }
        if (!hasLabelledPickup) {
            hasLabelledPickup = true
            viewModelScope.launch {
                routes.placeName(point)?.let { name -> _state.update { it.copy(pickup = it.pickup.copy(name = name)) } }
            }
        }
        if (_state.value.destination == null) return
        val origin = quotedFrom
        if (origin != null) {
            val drift = origin.distanceTo(point)
            _state.update { it.copy(pickupDriftMeters = drift) }
            if (drift < SIGNIFICANT_DRIFT_METERS) return
        }
        refreshQuote(silently = _state.value.routeEstimate != null)
    }

    /** Recomputes the route and every tier's fare; `silently` keeps prices on screen. */
    private fun refreshQuote(silently: Boolean = false) {
        quoteJob?.cancel()
        val destination = _state.value.destination
        if (destination == null) {
            quotedFrom = null
            _state.update {
                it.copy(routeEstimate = null, quotes = emptyMap(), quoteUpdatedAt = null, pickupDriftMeters = 0.0, isQuoting = false, isRepricing = false)
            }
            return
        }
        _state.update { if (silently) it.copy(isRepricing = true) else it.copy(isQuoting = true) }
        val origin = _state.value.pickup.point
        quoteJob = viewModelScope.launch {
            val estimate = routes.route(origin, destination.point)
            quotedFrom = origin
            _state.update { it.copy(routeEstimate = estimate, pickupDriftMeters = 0.0) }
            applyPricing(estimate)
            _state.update { it.copy(isQuoting = false, isRepricing = false) }
        }
    }

    /** Keeps the fare honest while the rider decides: supply drifts, demand re-evaluates, traffic re-times. */
    private fun startLiveFareUpdates() {
        if (liveFareJob != null) return
        liveFareJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(6_000)
                if (_state.value.ride != null) continue
                tick++
                driftNearbyDrivers()
                val estimate = _state.value.routeEstimate ?: continue
                if (_state.value.destination == null) continue
                if (tick % REROUTE_EVERY_TICKS == 0) refreshQuote(silently = true) else applyPricing(estimate)
            }
        }
    }

    private fun applyPricing(estimate: RouteEstimate) {
        val current = _state.value
        val previousTotal = current.quotes[current.selectedClassId]?.total
        val demand = pricing.demand(freeDriversNearby = current.nearbyDrivers.size, congestionRatio = congestionRatio(estimate))
        val quotes = pricing.quotes(estimate.distanceMeters, estimate.durationSeconds, demand.multiplier)
        val newTotal = quotes[current.selectedClassId]?.total
        val change = if (previousTotal != null && newTotal != null) round2(newTotal - previousTotal).takeIf { abs(it) >= 0.01 } ?: 0.0 else 0.0
        if (change != 0.0) Haptics.tap()
        _state.update { it.copy(demand = demand, quotes = quotes, quoteUpdatedAt = System.currentTimeMillis(), lastFareChange = change) }
    }

    /** Actual travel time vs. a free-flowing 50 km/h city road. */
    private fun congestionRatio(estimate: RouteEstimate): Double {
        if (estimate.distanceMeters <= 0) return 1.0
        val freeFlow = estimate.distanceMeters / 13.9
        return if (freeFlow <= 0) 1.0 else (estimate.durationSeconds / freeFlow).coerceIn(0.5, 3.0)
    }

    private fun seedNearbyDrivers(around: GeoPoint) {
        val offsets = listOf(0.006 to 0.004, -0.005 to 0.008, 0.009 to -0.006, -0.008 to -0.005, 0.003 to 0.011, -0.011 to 0.002)
        _state.update { s -> s.copy(nearbyDrivers = offsets.map { GeoPoint(around.latitude + it.first, around.longitude + it.second) }) }
    }

    private fun driftNearbyDrivers() {
        _state.update { s ->
            s.copy(nearbyDrivers = s.nearbyDrivers.map {
                GeoPoint(it.latitude + Random.nextDouble(-0.0016, 0.0016), it.longitude + Random.nextDouble(-0.0016, 0.0016))
            })
        }
    }

    // Ride lifecycle

    fun requestRide(paymentMethod: PaymentMethod?) {
        val s = _state.value
        val destination = s.destination
        val estimate = s.routeEstimate
        val quote = s.selectedQuote
        if (destination == null || estimate == null || quote == null) {
            _state.update { it.copy(errorMessage = "Choose a destination to request a ride.") }
            return
        }
        if (paymentMethod == null) {
            _state.update { it.copy(errorMessage = "Add a payment method to complete this trip.") }
            return
        }
        val ride = Ride(
            id = "ride_" + UUID.randomUUID().toString().take(8),
            status = RideStatus.REQUESTED,
            pickup = s.pickup,
            dropoff = destination,
            rideClass = s.selectedClass,
            quote = quote,
            requestedAt = System.currentTimeMillis(),
            routeToDropoff = estimate.polyline,
            paymentMethodId = paymentMethod.id,
        )
        remoteRideId = null
        _state.update {
            it.copy(ride = ride, receipt = null, progress = 0.0, etaMinutes = s.selectedClass.etaMinutes, errorMessage = null, driverPing = null, driverRoute = emptyList())
        }
        Haptics.tap()
        persistNewRide(ride, quote)
        startLifecycle()
    }

    /**
     * Writes the ride to Postgres. That row is what the payment function reads
     * to price the charge, so the fare can never be set by the client at checkout.
     */
    private fun persistNewRide(ride: Ride, quote: FareQuote) {
        val rider = riderId
        if (!backend.isConfigured || rider == null) {
            remoteRideReady = null
            return
        }
        val ready = CompletableDeferred<String?>()
        remoteRideReady = ready
        viewModelScope.launch {
            val id = try {
                backend.createRide(rider, ride, quote)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not persist ride: ${error.javaClass.simpleName}")
                null
            }
            remoteRideId = id
            ready.complete(id)
        }
    }

    /** Fire-and-forget status mirroring; a failed sync never interrupts the trip on screen. */
    private fun publish(update: RideUpdate) {
        if (!backend.isConfigured) return
        val ready = remoteRideReady ?: return
        viewModelScope.launch {
            val id = remoteRideId ?: ready.await() ?: return@launch
            try {
                backend.updateRide(id, update)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Ride sync failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun cancelRide(reason: String = "Changed my mind") {
        val ride = _state.value.ride ?: return
        if (!ride.status.allowsCancellation) return
        lifecycleJob?.cancel()
        _state.update { it.copy(ride = ride.copy(status = RideStatus.CANCELLED, cancellationReason = reason), driverPing = null, driverRoute = emptyList()) }
        publish(RideUpdate(status = RideStatus.CANCELLED.wire, cancelledAt = Dates.isoNow()))
        Haptics.warning()
    }

    fun dismissCompletedRide() {
        lifecycleJob?.cancel()
        remoteRideId = null
        remoteRideReady = null
        quotedFrom = null
        _state.update {
            it.copy(
                ride = null, receipt = null, progress = 0.0, driverPing = null, driverRoute = emptyList(),
                destination = null, routeEstimate = null, quotes = emptyMap(), quoteUpdatedAt = null,
                lastFareChange = 0.0, pickupDriftMeters = 0.0, errorMessage = null,
            )
        }
    }

    fun rateDriver(stars: Int) {
        val ride = _state.value.ride ?: return
        _state.update { it.copy(ride = ride.copy(riderRatingForDriver = stars)) }
        trips.rate(ride.id, stars)
        Haptics.success()
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private fun startLifecycle() {
        lifecycleJob?.cancel()
        lifecycleJob = viewModelScope.launch {
            try {
                runLifecycle()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = "Something went wrong with this ride. Please try again.") }
            }
        }
    }

    /** Advances the ride through every state, as a realtime backend would push it. */
    private suspend fun runLifecycle() {
        delay(800)
        updateStatus(RideStatus.MATCHING)
        delay(2_000)
        val matching = _state.value.ride ?: return

        val driver = DriverProfile.samples.random()
        val driverStart = GeoPoint(matching.pickup.point.latitude - 0.012, matching.pickup.point.longitude + 0.014)
        val pickupRoute = routes.route(driverStart, matching.pickup.point)

        val refreshed = _state.value.ride?.takeIf { it.status == RideStatus.MATCHING } ?: return
        val accepted = refreshed.copy(driver = driver, routeToPickup = pickupRoute.polyline, driverPoint = driverStart, status = RideStatus.ACCEPTED)
        _state.update { it.copy(ride = accepted, progress = 0.0, etaMinutes = pickupRoute.minutes) }
        publish(RideUpdate(status = RideStatus.ACCEPTED.wire, driverName = driver.name, driverPlate = driver.plate, driverVehicle = driver.vehicle, acceptedAt = Dates.isoNow()))
        beginLeg(pickupRoute.polyline)
        Haptics.success()

        delay(900)
        updateStatus(RideStatus.ARRIVING)
        animate(accepted.routeToPickup, durationSeconds = 9.0, totalMinutes = pickupRoute.minutes)

        updateStatus(RideStatus.ARRIVED)
        _state.update { it.copy(etaMinutes = 0) }
        Haptics.tap()
        delay(3_000)

        updateStatus(RideStatus.STARTED)
        val tripMinutes = _state.value.routeEstimate?.minutes ?: 12
        _state.update { it.copy(etaMinutes = tripMinutes) }
        beginLeg(accepted.routeToDropoff)
        delay(700)

        updateStatus(RideStatus.IN_PROGRESS)
        animate(accepted.routeToDropoff, durationSeconds = 12.0, totalMinutes = tripMinutes)

        updateStatus(RideStatus.COMPLETED)
        _state.update { it.copy(etaMinutes = 0) }
        Haptics.success()
    }

    private fun updateStatus(status: RideStatus) {
        val ride = _state.value.ride
        if (ride == null || ride.status == RideStatus.CANCELLED) throw CancellationException("Ride ended")
        _state.update {
            it.copy(ride = ride.copy(status = status, completedAt = if (status == RideStatus.COMPLETED) System.currentTimeMillis() else ride.completedAt))
        }
        when (status) {
            RideStatus.STARTED -> publish(RideUpdate(status = status.wire, startedAt = Dates.isoNow()))
            RideStatus.COMPLETED -> publish(RideUpdate(status = status.wire, completedAt = Dates.isoNow()))
            RideStatus.PAID -> Unit // Only Stripe's webhook may mark a ride paid server-side.
            else -> publish(RideUpdate(status = status.wire))
        }
    }

    /** Resets the interpolator for a new leg so the car snaps to the new road. */
    private fun beginLeg(route: List<GeoPoint>) {
        val start = route.firstOrNull()
        _state.update {
            it.copy(
                driverRoute = route,
                progress = 0.0,
                driverPing = start?.let { point ->
                    DriverPing(0, point, 0.0, route.headingAt(0.0), 0.0, System.currentTimeMillis(), TELEMETRY_INTERVAL)
                },
            )
        }
    }

    /**
     * Streams the driver along a leg like a realtime feed: one ping every
     * ~1.5 s with slight traffic jitter. The map eases between pings.
     */
    private suspend fun animate(route: List<GeoPoint>, durationSeconds: Double, totalMinutes: Int) {
        if (route.size < 2) return
        val totalMeters = route.lengthMeters()
        val pings = max(4, Math.round(durationSeconds / TELEMETRY_INTERVAL).toInt())
        val interval = durationSeconds / pings
        var previous = 0.0
        var sequence = _state.value.driverPing?.sequence ?: 0

        for (step in 1..pings) {
            if (_state.value.ride?.status == RideStatus.CANCELLED) throw CancellationException("Ride cancelled")
            delay((interval * 1000).toLong())
            if (_state.value.ride?.status == RideStatus.CANCELLED) throw CancellationException("Ride cancelled")

            val even = step.toDouble() / pings
            val jitter = if (step == pings) 0.0 else Random.nextDouble(-0.3, 0.3) / pings
            val value = min(max(even + jitter, previous + 0.004), 1.0)
            sequence++
            val point = route.pointAt(value) ?: continue
            val speed = if (interval > 0) totalMeters * (value - previous) / interval else 0.0
            _state.update { s ->
                s.copy(
                    progress = value,
                    ride = s.ride?.copy(driverPoint = point),
                    etaMinutes = max(0, ceil(totalMinutes * (1 - value)).toInt()),
                    driverPing = DriverPing(sequence, point, value, route.headingAt(value), speed, System.currentTimeMillis(), interval),
                )
            }
            previous = value
        }
    }

    // Payment

    /**
     * Charges the completed trip through Stripe. The amount is never sent from
     * here: the backend prices the PaymentIntent from the ride row, Stripe's
     * sheet collects the card, and Stripe's webhook marks the ride paid. The
     * receipt is read back from the settled payment.
     */
    fun payForRide(method: PaymentMethod?) {
        val ride = _state.value.ride ?: return
        if (ride.status != RideStatus.COMPLETED || _state.value.isPaying) return
        _state.update { it.copy(isPaying = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val rideId = remoteRideId ?: remoteRideReady?.await()
                val present = presentPaymentSheet
                if (!backend.isConfigured || rideId == null || present == null) {
                    settleWithoutStripe(ride, method)
                    return@launch
                }
                val intent = backend.createRidePayment(rideId)
                when (val outcome = present(intent)) {
                    PaymentSheetOutcome.Canceled -> Unit
                    is PaymentSheetOutcome.Failed -> {
                        Haptics.warning()
                        _state.update { it.copy(errorMessage = outcome.message) }
                    }
                    PaymentSheetOutcome.Completed -> {
                        val settled = backend.awaitSettledPayment(rideId)
                        finish(ride, liveReceipt(ride, settled, method, intent))
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (alreadyPaid: StripeError.AlreadyPaid) {
                // The webhook beat us to it — the trip is already settled.
                val rideId = remoteRideId
                val settled = rideId?.let { runCatching { backend.fetchPayment(it) }.getOrNull() }
                finish(ride, liveReceipt(ride, settled, method, null))
            } catch (notConfigured: StripeError.NotConfigured) {
                settleWithoutStripe(ride, method)
            } catch (error: StripeError.Server) {
                Haptics.warning()
                _state.update { it.copy(errorMessage = error.message) }
            } catch (error: Exception) {
                Log.w(TAG, "Payment failed: ${error.javaClass.simpleName}")
                Haptics.warning()
                _state.update { it.copy(errorMessage = "We couldn't reach the payment service. Please try again.") }
            } finally {
                _state.update { it.copy(isPaying = false) }
            }
        }
    }

    private fun liveReceipt(ride: Ride, settled: PaymentRow?, method: PaymentMethod?, intent: StripeIntent?): Receipt = Receipt(
        id = settled?.id ?: intent?.paymentId ?: ride.id,
        rideId = ride.id,
        total = settled?.amount ?: intent?.let { it.amountCents / 100.0 } ?: ride.quote.total,
        currencyCode = (settled?.currency ?: intent?.currency ?: ride.quote.currencyCode).uppercase(),
        paymentMethodLabel = settled?.paymentMethodLabel ?: method?.displayName ?: "Card",
        paidAt = Dates.parse(settled?.createdAt) ?: System.currentTimeMillis(),
        breakdown = ride.quote,
        receiptUrl = settled?.receiptUrl,
        isLiveCharge = true,
    )

    /**
     * Closes the trip when card payments aren't switched on. The receipt is
     * flagged so the rider is never told a charge happened when it didn't.
     */
    private suspend fun settleWithoutStripe(ride: Ride, method: PaymentMethod?) {
        delay(600)
        finish(
            ride,
            Receipt(
                id = "rcpt_" + UUID.randomUUID().toString().take(8),
                rideId = ride.id,
                total = ride.quote.total,
                currencyCode = ride.quote.currencyCode,
                paymentMethodLabel = method?.displayName ?: "Pending card payment",
                paidAt = System.currentTimeMillis(),
                breakdown = ride.quote,
                isLiveCharge = false,
            ),
        )
    }

    private fun finish(ride: Ride, receipt: Receipt) {
        _state.update { it.copy(receipt = receipt, ride = ride.copy(status = RideStatus.PAID)) }
        trips.recordRiderTrip(
            TripRecord(
                id = ride.id,
                pickupName = ride.pickup.name,
                dropoffName = ride.dropoff.name,
                date = System.currentTimeMillis(),
                fare = receipt.total,
                currencyCode = receipt.currencyCode,
                className = ride.rideClass.name,
                counterpartName = ride.driver?.name ?: "RideAX driver",
            )
        )
        Haptics.success()
    }

    companion object {
        private const val TAG = "RideAX.Ride"
        /** A re-route is only worth it once the rider has actually moved. */
        const val SIGNIFICANT_DRIFT_METERS = 60.0
        private const val REROUTE_EVERY_TICKS = 8
        private const val TELEMETRY_INTERVAL = 1.5
    }
}
