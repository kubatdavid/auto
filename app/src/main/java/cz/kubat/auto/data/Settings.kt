package cz.kubat.auto.data

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    /** Fetched from the server (GET /api/v1/config) so it never lives in the public repo. */
    var mapyKey: String
        get() = prefs.getString("mapy_key", "") ?: ""
        set(v) = prefs.edit().putString("mapy_key", v).apply()

    var adapterAddress: String
        get() = prefs.getString("adapter", "") ?: ""
        set(v) = prefs.edit().putString("adapter", v).apply()

    var fuelPrice: Double
        get() = prefs.getFloat("fuel_price", 38f).toDouble()
        set(v) = prefs.edit().putFloat("fuel_price", v.toFloat()).apply()

    /** Sensor keys shown as big tiles on the Drive tab, in order. */
    var driveSensors: List<String>
        get() = (prefs.getString("drive_sensors", null) ?: DEFAULT_DRIVE_SENSORS).split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("drive_sensors", v.joinToString(",")).apply()

    /** Saved destinations as a JSON array of {name, location, lat, lon}. */
    var placesJson: String
        get() = prefs.getString("places", "[]") ?: "[]"
        set(v) = prefs.edit().putString("places", v).apply()

    companion object {
        const val DEFAULT_DRIVE_SENSORS = "speed,rpm,consumption,coolant"
        const val DEFAULT_SERVER = "https://auto.abhishekam.cz"
    }
}
