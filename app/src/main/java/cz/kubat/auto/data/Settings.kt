package cz.kubat.auto.data

import android.content.Context

/** Tile width on the Drive tab: S = third, M = half, L = full row (units of 6). */
enum class TileSize(val units: Int) { S(2), M(3), L(6) }

data class DriveTile(val key: String, val size: TileSize)

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

    /** Tiles on the Drive tab, in order; stored as "key:S|M|L,...". */
    var driveTiles: List<DriveTile>
        get() = (prefs.getString("drive_tiles", null) ?: DEFAULT_DRIVE_TILES).split(',').filter { it.isNotBlank() }.map {
            val (k, size) = (it.split(':') + "M").let { p -> p[0] to p[1] }
            DriveTile(k, TileSize.entries.firstOrNull { e -> e.name == size } ?: TileSize.M)
        }
        set(v) = prefs.edit().putString("drive_tiles", v.joinToString(",") { "${it.key}:${it.size.name}" }).apply()

    /** Saved destinations as a JSON array of {name, location, lat, lon}. */
    var placesJson: String
        get() = prefs.getString("places", "[]") ?: "[]"
        set(v) = prefs.edit().putString("places", v).apply()

    companion object {
        const val DEFAULT_DRIVE_TILES = "coolant:M,consumption:M,trip_avg:S,volts:S,trim:S"
        const val DEFAULT_SERVER = "https://auto.abhishekam.cz"
    }
}
