package com.rork.rideax.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.serializer

/**
 * Small JSON-in-SharedPreferences store for preferences and history. Anything
 * that must survive a reinstall lives in Postgres; tokens live in [SecureStore].
 */
class Persistence(context: Context) {
    @PublishedApi
    internal val prefs: SharedPreferences = context.getSharedPreferences("rideax_state", Context.MODE_PRIVATE)

    inline fun <reified T> load(key: String): T? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { Http.json.decodeFromString(serializer<T>(), raw) }.getOrNull()
    }

    inline fun <reified T> save(key: String, value: T) {
        prefs.edit().putString(key, Http.json.encodeToString(serializer<T>(), value)).apply()
    }

    companion object {
        const val SESSION = "session.v1"
        const val DRIVER = "driver.v1"
        const val TRIPS = "trips.v1"
    }
}
