package com.rork.rideax.data

import com.rork.rideax.model.DailyEarnings
import com.rork.rideax.model.TripRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
internal data class TripSnapshot(val rider: List<TripRecord>, val driver: List<TripRecord>)

/** Trip history shared by the rider Activity tab and the driver earnings screen. */
class TripRepository(private val persistence: Persistence) {
    private val _riderTrips = MutableStateFlow<List<TripRecord>>(emptyList())
    val riderTrips: StateFlow<List<TripRecord>> = _riderTrips.asStateFlow()

    private val _driverTrips = MutableStateFlow<List<TripRecord>>(emptyList())
    val driverTrips: StateFlow<List<TripRecord>> = _driverTrips.asStateFlow()

    init {
        val snapshot = persistence.load<TripSnapshot>(Persistence.TRIPS)
        if (snapshot != null) {
            _riderTrips.value = snapshot.rider
            _driverTrips.value = snapshot.driver
        } else {
            _riderTrips.value = riderSamples()
            _driverTrips.value = driverSamples()
            persist()
        }
    }

    fun recordRiderTrip(trip: TripRecord) {
        _riderTrips.update { listOf(trip) + it.filterNot { existing -> existing.id == trip.id } }
        persist()
    }

    fun recordDriverTrip(trip: TripRecord) {
        _driverTrips.update { listOf(trip) + it }
        persist()
    }

    fun rate(tripId: String, stars: Int) {
        _riderTrips.update { trips -> trips.map { if (it.id == tripId) it.copy(rating = stars) else it } }
        persist()
    }

    /** Earnings grouped Monday to Sunday for the driver chart. */
    fun weeklyEarnings(trips: List<TripRecord>, now: Long = System.currentTimeMillis()): List<DailyEarnings> {
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val offset = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_YEAR, -offset)
        }
        return labels.mapIndexed { index, label ->
            val dayStart = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, index) }.timeInMillis
            val dayEnd = dayStart + DAY_MS
            val day = trips.filter { !it.wasCancelled && it.date in dayStart until dayEnd }
            DailyEarnings(label, day.sumOf { it.fare }, day.size)
        }
    }

    fun todaysTrips(trips: List<TripRecord>): List<TripRecord> {
        val start = startOfToday()
        return trips.filter { !it.wasCancelled && it.date >= start && it.date < start + DAY_MS }
    }

    fun averageRating(trips: List<TripRecord>): Double {
        val rated = trips.mapNotNull { it.rating }
        return if (rated.isEmpty()) 4.93 else rated.average()
    }

    private fun persist() {
        persistence.save(Persistence.TRIPS, TripSnapshot(_riderTrips.value, _driverTrips.value))
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun riderSamples(): List<TripRecord> {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        return listOf(
            TripRecord("t1", "Rue Oberkampf", "Gare du Nord", now - hour * 26, 12.40, "EUR", "RideAX Go", "Ahmed K.", 5),
            TripRecord("t2", "Louvre Museum", "Le Marais", now - hour * 52, 9.80, "EUR", "RideAX Go", "Léa M.", 5),
            TripRecord("t3", "CDG Airport", "Rue Oberkampf", now - hour * 120, 42.10, "EUR", "Comfort", "Marco R.", 4),
            TripRecord("t4", "Bastille", "Montmartre", now - hour * 190, 0.0, "EUR", "RideAX Go", "Sofia D.", null, wasCancelled = true),
        )
    }

    private fun driverSamples(): List<TripRecord> {
        fun day(offset: Int, hour: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 15); set(Calendar.SECOND, 0)
        }.timeInMillis
        return listOf(
            TripRecord("dt1", "Gare du Nord", "Montmartre", day(0, 18), 14.60, "EUR", "RideAX Go", "Chloé B.", 5),
            TripRecord("dt2", "Opéra", "Bastille", day(0, 11), 11.30, "EUR", "RideAX Go", "Yanis T.", 5),
            TripRecord("dt3", "Le Marais", "La Défense", day(0, 9), 22.40, "EUR", "Comfort", "Nora P.", 4),
            TripRecord("dt4", "CDG Airport", "Le Marais", day(-1, 21), 42.10, "EUR", "Comfort", "Tomás L.", 5),
            TripRecord("dt5", "Bastille", "Nation", day(-1, 15), 9.20, "EUR", "RideAX Go", "Alice R.", 5),
            TripRecord("dt6", "Saint-Germain", "Invalides", day(-2, 12), 10.70, "EUR", "RideAX Go", "Hugo V.", 5),
            TripRecord("dt7", "Montparnasse", "Louvre", day(-3, 8), 13.90, "EUR", "RideAX Go", "Emma S.", 4),
            TripRecord("dt8", "Belleville", "Gare de Lyon", day(-4, 19), 16.20, "EUR", "Comfort", "Jonas W.", 5),
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
