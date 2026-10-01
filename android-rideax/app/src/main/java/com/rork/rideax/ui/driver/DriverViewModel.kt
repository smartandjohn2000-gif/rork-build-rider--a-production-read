package com.rork.rideax.ui.driver

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rork.rideax.AppContainer
import com.rork.rideax.data.Haptics
import com.rork.rideax.data.Persistence
import com.rork.rideax.data.PricingService
import com.rork.rideax.model.DriverApprovalStatus
import com.rork.rideax.model.DriverDocument
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.model.Place
import com.rork.rideax.model.PlaceKind
import com.rork.rideax.model.RideClass
import com.rork.rideax.model.RideOffer
import com.rork.rideax.model.TripRecord
import com.rork.rideax.model.Vehicle
import com.rork.rideax.model.pointAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** The stage of the job a driver is currently working. */
enum class DriverJobStage(val title: String, val actionTitle: String) {
    IDLE("No active job", "Go online"),
    HEADING_TO_PICKUP("Head to pickup", "I've arrived"),
    WAITING_FOR_RIDER("Waiting for rider", "Start trip"),
    ON_TRIP("Trip in progress", "Complete trip"),
    COMPLETED("Trip complete", "Finish"),
}

@Serializable
internal data class DriverSnapshot(
    val approvalStatus: DriverApprovalStatus,
    val documents: List<DriverDocument>,
    val vehicle: Vehicle,
    val acceptedCount: Int,
    val offeredCount: Int,
    val onlineSeconds: Long,
)

data class DriverUiState(
    val isOnline: Boolean = false,
    val approvalStatus: DriverApprovalStatus = DriverApprovalStatus.APPROVED,
    val documents: List<DriverDocument> = DriverDocument.required,
    val vehicle: Vehicle = Vehicle("Toyota", "Camry", 2022, "Grey", "742-KLD"),
    val currentOffer: RideOffer? = null,
    val secondsRemaining: Int = 0,
    val jobStage: DriverJobStage = DriverJobStage.IDLE,
    val activeJob: RideOffer? = null,
    val driverPoint: GeoPoint = GeoPoint(48.8720, 2.3625),
    val routeToRider: List<GeoPoint> = emptyList(),
    val jobProgress: Double = 0.0,
    val acceptedCount: Int = 18,
    val offeredCount: Int = 20,
    val onlineSeconds: Long = 4 * 3600L + 12 * 60L,
    val lastCompletedFare: Double? = null,
) {
    val acceptanceRate: Int get() = if (offeredCount <= 0) 100 else (acceptedCount.toDouble() / offeredCount * 100).roundToInt()
    val canGoOnline: Boolean get() = approvalStatus == DriverApprovalStatus.APPROVED
    val onlineDurationText: String get() = "${onlineSeconds / 3600}h ${(onlineSeconds % 3600) / 60}m today"
}

/** Owns the driver shell: availability, offers, the active job and earnings stats. */
class DriverViewModel(private val container: AppContainer) : ViewModel() {
    private val routes = container.routes
    private val trips = container.trips
    private val backend = container.backend
    private val persistence = container.persistence

    private val _state = MutableStateFlow(DriverUiState())
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    var driverId: String? = null

    private var offerJob: Job? = null
    private var timerJob: Job? = null
    private var jobJob: Job? = null
    private var approvalJob: Job? = null

    init {
        persistence.load<DriverSnapshot>(Persistence.DRIVER)?.let { s ->
            _state.update {
                it.copy(
                    approvalStatus = s.approvalStatus, documents = s.documents, vehicle = s.vehicle,
                    acceptedCount = s.acceptedCount, offeredCount = s.offeredCount, onlineSeconds = s.onlineSeconds,
                )
            }
        }
    }

    // Availability

    fun setOnline(online: Boolean) {
        if (online && !_state.value.canGoOnline) return
        _state.update { it.copy(isOnline = online, currentOffer = if (online) it.currentOffer else null) }
        Haptics.tap()
        if (online) {
            startOnlineTimer()
            scheduleNextOffer(2_000)
        } else {
            offerJob?.cancel()
            timerJob?.cancel()
        }
        persist()
        syncDriver()
    }

    private fun startOnlineTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (!_state.value.isOnline) return@launch
                _state.update { it.copy(onlineSeconds = it.onlineSeconds + 60) }
            }
        }
    }

    // Offers

    private fun scheduleNextOffer(delayMillis: Long) {
        offerJob?.cancel()
        offerJob = viewModelScope.launch {
            delay(delayMillis)
            val s = _state.value
            if (s.isOnline && s.jobStage == DriverJobStage.IDLE) presentOffer()
        }
    }

    private fun presentOffer() {
        val offer = randomOffer(_state.value.driverPoint)
        _state.update { it.copy(currentOffer = offer, offeredCount = it.offeredCount + 1, secondsRemaining = OFFER_SECONDS) }
        Haptics.warning()
        offerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (_state.value.currentOffer?.id != offer.id) return@launch
                val remaining = _state.value.secondsRemaining - 1
                _state.update { it.copy(secondsRemaining = remaining) }
                if (remaining <= 0) {
                    _state.update { it.copy(currentOffer = null) }
                    scheduleNextOffer(6_000)
                    return@launch
                }
            }
        }
    }

    fun declineOffer() {
        offerJob?.cancel()
        _state.update { it.copy(currentOffer = null) }
        Haptics.tap()
        persist()
        scheduleNextOffer(5_000)
    }

    fun acceptOffer() {
        val offer = _state.value.currentOffer ?: return
        offerJob?.cancel()
        _state.update {
            it.copy(currentOffer = null, activeJob = offer, acceptedCount = it.acceptedCount + 1, jobStage = DriverJobStage.HEADING_TO_PICKUP, jobProgress = 0.0)
        }
        Haptics.success()
        persist()
        val origin = _state.value.driverPoint
        jobJob?.cancel()
        jobJob = viewModelScope.launch {
            val estimate = routes.route(origin, offer.pickup.point)
            if (_state.value.activeJob?.id != offer.id) return@launch
            _state.update { it.copy(routeToRider = estimate.polyline) }
            drive(DriverJobStage.HEADING_TO_PICKUP)
        }
    }

    private suspend fun drive(stage: DriverJobStage) {
        val steps = 40
        for (step in 0..steps) {
            if (_state.value.jobStage != stage) return
            val value = step.toDouble() / steps
            val point = _state.value.routeToRider.pointAt(value)
            _state.update { it.copy(jobProgress = value, driverPoint = point ?: it.driverPoint) }
            delay(220)
        }
    }

    /** Advances the job: heading → waiting → on trip → completed → idle. */
    fun advanceJob() {
        val job = _state.value.activeJob ?: return
        when (_state.value.jobStage) {
            DriverJobStage.IDLE -> Unit
            DriverJobStage.HEADING_TO_PICKUP -> {
                jobJob?.cancel()
                _state.update { it.copy(jobStage = DriverJobStage.WAITING_FOR_RIDER, jobProgress = 1.0, driverPoint = job.pickup.point) }
                Haptics.tap()
            }
            DriverJobStage.WAITING_FOR_RIDER -> {
                _state.update { it.copy(jobStage = DriverJobStage.ON_TRIP, jobProgress = 0.0) }
                Haptics.tap()
                jobJob?.cancel()
                jobJob = viewModelScope.launch {
                    val estimate = routes.route(job.pickup.point, job.dropoff.point)
                    _state.update { it.copy(routeToRider = estimate.polyline) }
                    drive(DriverJobStage.ON_TRIP)
                }
            }
            DriverJobStage.ON_TRIP -> {
                jobJob?.cancel()
                _state.update {
                    it.copy(jobStage = DriverJobStage.COMPLETED, jobProgress = 1.0, lastCompletedFare = job.fare, driverPoint = job.dropoff.point)
                }
                trips.recordDriverTrip(
                    TripRecord(job.id, job.pickup.name, job.dropoff.name, System.currentTimeMillis(), job.fare, job.currencyCode, job.className, job.riderName, 5)
                )
                Haptics.success()
                persist()
            }
            DriverJobStage.COMPLETED -> finishJob()
        }
    }

    fun finishJob() {
        jobJob?.cancel()
        _state.update { it.copy(activeJob = null, jobStage = DriverJobStage.IDLE, jobProgress = 0.0, routeToRider = emptyList(), lastCompletedFare = null) }
        if (_state.value.isOnline) scheduleNextOffer(4_000)
    }

    fun cancelJob() {
        jobJob?.cancel()
        _state.update { it.copy(activeJob = null, jobStage = DriverJobStage.IDLE, jobProgress = 0.0, routeToRider = emptyList()) }
        Haptics.warning()
        if (_state.value.isOnline) scheduleNextOffer(8_000)
    }

    // Documents & approval

    fun uploadDocument(id: String) {
        _state.update { s -> s.copy(documents = s.documents.map { if (it.id == id) it.copy(isUploaded = true) else it }) }
        Haptics.tap()
        persist()
    }

    fun submitForApproval() {
        if (!_state.value.documents.all { it.isUploaded }) return
        _state.update { it.copy(approvalStatus = DriverApprovalStatus.PENDING) }
        Haptics.success()
        persist()
        syncDriver()
        // Admin review is asynchronous; the backend pushes the result.
        approvalJob?.cancel()
        approvalJob = viewModelScope.launch {
            delay(6_000)
            if (_state.value.approvalStatus != DriverApprovalStatus.PENDING) return@launch
            _state.update { it.copy(approvalStatus = DriverApprovalStatus.APPROVED) }
            persist()
            syncDriver()
        }
    }

    fun resetApproval() {
        setOnline(false)
        _state.update { s -> s.copy(approvalStatus = DriverApprovalStatus.NOT_SUBMITTED, documents = DriverDocument.required.map { it.copy(isUploaded = false) }) }
        persist()
        syncDriver()
    }

    /** Mirrors approval, availability and vehicle into `driver_profiles`. */
    private fun syncDriver() {
        val id = driverId ?: return
        if (!backend.isConfigured) return
        val s = _state.value
        viewModelScope.launch {
            try {
                backend.syncDriverProfile(id, s.approvalStatus, s.isOnline, s.vehicle)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Driver sync failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun persist() {
        val s = _state.value
        persistence.save(Persistence.DRIVER, DriverSnapshot(s.approvalStatus, s.documents, s.vehicle, s.acceptedCount, s.offeredCount, s.onlineSeconds))
    }

    /** A plausible nearby job. In production these arrive from the dispatch service. */
    private fun randomOffer(around: GeoPoint): RideOffer {
        val pickups = listOf(
            Triple("Rue Oberkampf", "11th arrondissement", GeoPoint(48.8656, 2.3712)),
            Triple("Place de la République", "3rd arrondissement", GeoPoint(48.8674, 2.3636)),
            Triple("Canal Saint-Martin", "10th arrondissement", GeoPoint(48.8710, 2.3660)),
            Triple("Bastille", "12th arrondissement", GeoPoint(48.8532, 2.3692)),
        )
        val dropoffs = listOf(
            Triple("Gare du Nord", "18 Rue de Dunkerque", GeoPoint(48.8809, 2.3553)),
            Triple("Montmartre", "Sacré-Cœur", GeoPoint(48.8867, 2.3431)),
            Triple("Louvre Museum", "1st arrondissement", GeoPoint(48.8606, 2.3376)),
            Triple("La Défense", "Puteaux", GeoPoint(48.8918, 2.2380)),
        )
        val riders = listOf("Sofia" to 4.8, "Chloé" to 4.9, "Yanis" to 4.7, "Nora" to 5.0)
        val p = pickups.random()
        val d = dropoffs.random()
        val rider = riders.random()
        val pickup = Place(p.first, p.first, p.second, p.third, PlaceKind.PIN)
        val dropoff = Place(d.first, d.first, d.second, d.third, PlaceKind.PIN)
        val pickupDistance = around.distanceTo(pickup.point)
        val tripDistance = pickup.point.distanceTo(dropoff.point) * 1.3
        val quote = PricingService().quote(RideClass.all.first(), tripDistance, tripDistance / 7.5)
        return RideOffer(
            id = "offer_" + UUID.randomUUID().toString().take(6),
            riderName = rider.first,
            riderRating = rider.second,
            pickup = pickup,
            dropoff = dropoff,
            pickupDistanceMeters = max(pickupDistance, 400.0),
            pickupEtaMinutes = max(2, (pickupDistance / 250).roundToInt()),
            tripDistanceMeters = tripDistance,
            fare = quote.total,
            currencyCode = quote.currencyCode,
            className = RideClass.all.first().name,
        )
    }

    companion object {
        private const val TAG = "RideAX.Driver"
        const val OFFER_SECONDS = 24
    }
}
