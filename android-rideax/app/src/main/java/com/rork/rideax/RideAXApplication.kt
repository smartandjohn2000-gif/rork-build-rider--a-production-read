package com.rork.rideax

import android.app.Application
import android.content.Context
import com.rork.rideax.data.AuthManager
import com.rork.rideax.data.BackendService
import com.rork.rideax.data.Haptics
import com.rork.rideax.data.LocationService
import com.rork.rideax.data.Persistence
import com.rork.rideax.data.RouteService
import com.rork.rideax.data.SecureStore
import com.rork.rideax.data.TripRepository
import org.osmdroid.config.Configuration

/** Process-wide services shared by every screen's ViewModel. */
class AppContainer(context: Context) {
    val persistence = Persistence(context)
    val auth = AuthManager(SecureStore(context))
    val backend = BackendService(auth)
    val routes = RouteService()
    val location = LocationService(context)
    val trips = TripRepository(persistence)
}

class RideAXApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Haptics.init(this)
        Configuration.getInstance().apply {
            load(this@RideAXApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "RideAX/1.0 ($packageName)"
            osmdroidTileCache = cacheDir.resolve("osmdroid-tiles")
        }
    }
}
