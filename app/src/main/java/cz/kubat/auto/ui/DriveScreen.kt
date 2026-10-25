package cz.kubat.auto.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kubat.auto.App
import cz.kubat.auto.data.DriveTile
import cz.kubat.auto.data.TileSize
import cz.kubat.auto.data.AppLog
import cz.kubat.auto.record.Link
import cz.kubat.auto.record.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

object Palette {
    val bg = Color(0xFF0E1116)
    val panel = Color(0xFF171B22)
    val line = Color(0xFF2A303A)
    val text = Color(0xFFE8EBF0)
    val muted = Color(0xFF8A93A3)
    val cold = Color(0xFF4EA1FF)
    val warm = Color(0xFFF2B134)
    val ok = Color(0xFF3CCF7A)
    val hot = Color(0xFFFF5A5A)
}

data class Place(val name: String, val location: String, val lat: Double, val lon: Double)

private const val DROPPED_PIN = "Dropped pin"

private suspend fun reverseGeocode(lat: Double, lon: Double, key: String): Place? = withContext(Dispatchers.IO) {
    val url = "https://api.mapy.com/v1/rgeocode?lon=$lon&lat=$lat&lang=cs&apikey=$key"
    val item = JSONObject(URL(url).readText()).optJSONArray("items")?.optJSONObject(0) ?: return@withContext null
    Place(item.optString("name", DROPPED_PIN), item.optString("location"), lat, lon)
}

@Composable
fun DriveScreen(live: Live, onStart: () -> Unit, onStop: () -> Unit) {
    val app = App.instance
    val compact = LocalConfiguration.current.screenHeightDp < 520 // split-screen with the navigation app
    var destination by remember { mutableStateOf<Place?>(null) }
    var places by remember { mutableStateOf(loadPlaces()) }
    val context = LocalContext.current
    val position = rememberPosition()
    // Name a dropped pin by its street address (Mapy.com reverse geocoding)
    LaunchedEffect(destination) {
        val d = destination ?: return@LaunchedEffect
        if (d.name != DROPPED_PIN || app.settings.mapyKey.isEmpty()) return@LaunchedEffect
        val named = try {
            reverseGeocode(d.lat, d.lon, app.settings.mapyKey)
        } catch (e: Exception) {
            null
        }
        if (named != null && destination == d) destination = named
    }
    val lat = live.lat ?: position?.first
    val lon = live.lon ?: position?.second

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        for (a in live.alerts) {
            Text(
                a.text, color = if (a.critical) Color.White else Color(0xFF1A1200), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(if (a.critical) Palette.hot else Palette.warm).padding(12.dp),
            )
        }
        StatusBar(live, onStart, onStop, compact)
        BigValues(live, compact)
        if (!compact) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                MapPane(
                    mapyKey = app.settings.mapyKey, lat = lat, lon = lon, track = live.track,
                    destination = destination?.let { it.lat to it.lon }, modifier = Modifier.fillMaxSize(),
                    onLongPress = { la, lo ->
                        destination = Place(DROPPED_PIN, "%.5f, %.5f".format(java.util.Locale.US, la, lo), la, lo)
                        AppLog.i("Map: pin dropped at $la, $lo")
                    },
                )
                PlacesColumn(
                    places, onPick = { destination = it; AppLog.i("Place picked: ${it.name}") },
                    onRemove = { p ->
                        places = places - p; savePlaces(places)
                        Toast.makeText(context, "Removed ${p.name}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                SearchBox(
                    lat, lon, destination, onPick = { destination = it },
                    saved = destination != null && places.any { it.lat == destination!!.lat && it.lon == destination!!.lon },
                    onSave = { d -> places = places + d; savePlaces(places); AppLog.i("Place saved: ${d.name}") },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            TripStrip(live, app.settings.fuelPrice)
        }
        Chips(live)
    }
}

@Composable
private fun StatusBar(live: Live, onStart: () -> Unit, onStop: () -> Unit, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when (live.link) {
            Link.LIVE -> Palette.ok
            Link.CONNECTING, Link.WAITING -> Palette.warm
            Link.STOPPED -> Palette.muted
        }
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(live.status, color = Palette.muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (live.link == Link.STOPPED) {
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Palette.ok)) {
                Text("Connect", color = Color(0xFF08130C))
            }
        } else {
            OutlinedButton(onClick = onStop) { Text("Disconnect", color = Palette.text) }
        }
    }
}

@Composable
private fun BigValues(live: Live, compact: Boolean) {
    val tiles = App.instance.settings.driveTiles.filter { it.key in SENSOR_BY_KEY }
    if (tiles.isEmpty()) return
    // pack tiles into rows of 6 units, in the user's order
    val rows = ArrayList<MutableList<DriveTile>>()
    for (t in tiles) {
        val last = rows.lastOrNull()
        if (last != null && last.sumOf { it.size.units } + t.size.units <= 6) last += t else rows += mutableListOf(t)
    }
    Column(Modifier.padding(horizontal = 6.dp)) {
        for (row in rows) {
            Row {
                for (t in row) {
                    val s = SENSOR_BY_KEY.getValue(t.key)
                    val v = s.value(live)
                    val size = when (t.size) {
                        TileSize.L -> if (compact) 40.sp else 60.sp
                        TileSize.M -> if (compact) 30.sp else 46.sp
                        TileSize.S -> if (compact) 22.sp else 30.sp
                    }
                    Tile(
                        v?.let(s.format) ?: "–", if (s.unit.isEmpty()) s.label else "${s.unit} · ${s.label}",
                        Modifier.weight(t.size.units.toFloat()), v?.let(s.color) ?: Palette.text, size, compact,
                    )
                }
                val free = 6 - row.sumOf { it.size.units }
                if (free > 0) Spacer(Modifier.weight(free.toFloat()))
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier, color: Color, size: androidx.compose.ui.unit.TextUnit, compact: Boolean) {
    Column(
        modifier.padding(if (compact) 2.dp else 3.dp).background(Palette.panel, RoundedCornerShape(8.dp))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 1.dp else 3.dp)
    ) {
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- saved places
fun loadPlaces(): List<Place> {
    val arr = org.json.JSONArray(App.instance.settings.placesJson)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Place(o.getString("name"), o.optString("location"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

fun savePlaces(places: List<Place>) {
    val arr = org.json.JSONArray()
    for (p in places) arr.put(JSONObject().put("name", p.name).put("location", p.location).put("lat", p.lat).put("lon", p.lon))
    App.instance.settings.placesJson = arr.toString()
}

/** Saved destinations on the right edge of the map: tap = set destination, long-press = remove. */
@Composable
private fun PlacesColumn(places: List<Place>, onPick: (Place) -> Unit, onRemove: (Place) -> Unit, modifier: Modifier) {
    if (places.isEmpty()) return
    Column(modifier.padding(top = 72.dp, end = 8.dp).width(132.dp)) {
        for (p in places) {
            Text(
                p.name, color = Palette.text, fontSize = 14.sp, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .background(Color(0xE6171B22), RoundedCornerShape(10.dp))
                    .pointerInput(p) { detectTapGestures(onTap = { onPick(p) }, onLongPress = { onRemove(p) }) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TripStrip(live: Live, fuelPrice: Double) {
    val avg = if (live.distanceKm > 0.5) "%.1f L/100".format(live.fuelL / live.distanceKm * 100) else "– L/100"
    val s = live.tripSeconds.toInt()
    val time = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("%.1f km".format(live.distanceKm), color = Palette.text, fontSize = 18.sp)
        Text(time, color = Palette.text, fontSize = 18.sp)
        Text(avg, color = Palette.text, fontSize = 18.sp)
        Text("%.2f L · %.0f Kč".format(live.fuelL, live.fuelL * fuelPrice), color = Palette.text, fontSize = 18.sp)
    }
}

@Composable
private fun Chips(live: Live) {
    val v = live.values
    val chips = buildList {
        val c = live.codes
        add(
            when {
                c == null -> Palette.muted to "Codes: –"
                c.stored.isEmpty() && c.pending.isEmpty() -> Palette.ok to "No fault codes"
                else -> Palette.warm to "Codes: " + (c.stored + c.pending.map { "$it?" }).joinToString(" ")
            }
        )
        v["volts"]?.let {
            val running = (v["rpm"] ?: 0.0) > 300
            val bad = if (running) it < 13.2 || it > 14.8 else it < 12.2
            add((if (bad) Palette.warm else Palette.ok) to "Battery %.1f V".format(it))
        }
        when (v["fuelsys"]?.toInt()) {
            1 -> add(Palette.warm to "Mixture: open loop (cold)")
            2 -> add(Palette.ok to "Mixture: closed loop")
            4 -> add(Palette.muted to "Mixture: load/decel")
            8, 16 -> add(Palette.hot to "Mixture: fault")
        }
        val stft = v["stft"]
        val ltft = v["ltft"]
        if (stft != null && ltft != null) {
            val t = stft + ltft
            add((if (kotlin.math.abs(t) > 15) Palette.warm else Palette.ok) to "Trim %+.0f %%".format(t))
        }
        v["iat"]?.let { add(Palette.muted to "Intake %.0f °C".format(it)) }
        if (live.samplesPerSec > 0) add(Palette.muted to "%.1f/s".format(live.samplesPerSec))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
        for ((color, text) in chips) {
            Row(
                Modifier.padding(end = 6.dp).background(Palette.panel, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(text, color = Palette.text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SearchBox(
    lat: Double?, lon: Double?, destination: Place?, onPick: (Place?) -> Unit,
    saved: Boolean, onSave: (Place) -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    val key = App.instance.settings.mapyKey

    LaunchedEffect(query) {
        if (query.length < 3 || key.isEmpty()) {
            results = emptyList(); return@LaunchedEffect
        }
        delay(350) // debounce typing
        results = try {
            suggest(query, key, lat, lon)
        } catch (e: Exception) {
            emptyList()
        }
    }

    Column(modifier.fillMaxWidth().padding(8.dp)) {
        if (destination == null) {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search, or long-press the map") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = TextFieldDefaults.colors(focusedContainerColor = Palette.panel, unfocusedContainerColor = Palette.panel),
                modifier = Modifier.fillMaxWidth(),
            )
            for (p in results) {
                Column(
                    Modifier.fillMaxWidth().background(Palette.panel).clickable {
                        onPick(p); query = ""; results = emptyList()
                    }.padding(12.dp)
                ) {
                    Text(p.name, color = Palette.text, fontSize = 16.sp)
                    Text(p.location, color = Palette.muted, fontSize = 13.sp)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(destination.name, color = Palette.text, fontSize = 16.sp, maxLines = 1)
                    Text(destination.location, color = Palette.muted, fontSize = 12.sp, maxLines = 1)
                }
                Button(
                    onClick = { openWaze(context, destination) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.cold),
                ) { Text("Navigate (Waze)", color = Color.White) }
                IconButton(onClick = { if (!saved) onSave(destination) }) {
                    Icon(Icons.Filled.Star, contentDescription = "Save place", tint = if (saved) Palette.warm else Palette.muted)
                }
                IconButton(onClick = { onPick(null) }) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Palette.muted) }
            }
        }
    }
}

/** Opens the Waze app; with this app in split-screen it lands in the other half (FLAG_ACTIVITY_LAUNCH_ADJACENT). */
fun openWaze(context: Context, p: Place) {
    val flags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK
    val waze = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=${p.lat},${p.lon}&navigate=yes")).setPackage("com.waze").addFlags(flags)
    try {
        context.startActivity(waze)
        AppLog.i("Waze: navigating to ${p.name} (${p.lat}, ${p.lon})")
    } catch (e: ActivityNotFoundException) {
        AppLog.w("Waze app not found, falling back to geo: intent")
        Toast.makeText(context, "Waze is not installed — opening another map app", Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")).addFlags(flags))
        } catch (e2: ActivityNotFoundException) {
            AppLog.e("No navigation app installed", e2)
        }
    }
}

/** Device position for the map, independent of the recorder (GPS + fused provider when available). */
@SuppressLint("MissingPermission")
@Composable
private fun rememberPosition(): Pair<Double, Double>? {
    val context = LocalContext.current
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    DisposableEffect(Unit) {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { pos = it.latitude to it.longitude }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders }
        if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { pos = it.latitude to it.longitude }
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    AppLog.w("Location provider $p unavailable: ${e.message}")
                }
            }
            AppLog.i("Map location providers: $providers, enabled: ${providers.filter { lm.isProviderEnabled(it) }}")
        } else {
            AppLog.w("Map: no location permission")
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return pos
}

private suspend fun suggest(query: String, key: String, lat: Double?, lon: Double?): List<Place> = withContext(Dispatchers.IO) {
    val q = URLEncoder.encode(query, "UTF-8")
    var url = "https://api.mapy.com/v1/suggest?query=$q&lang=cs&limit=6&type=regional&type=poi&apikey=$key"
    if (lat != null && lon != null) url += "&preferNear=$lon,$lat&preferNearPrecision=5000"
    val items = JSONObject(URL(url).readText()).optJSONArray("items") ?: return@withContext emptyList()
    (0 until items.length()).mapNotNull { i ->
        val it = items.getJSONObject(i)
        val pos = it.optJSONObject("position") ?: return@mapNotNull null
        Place(it.optString("name"), it.optString("location"), pos.getDouble("lat"), pos.getDouble("lon"))
    }
}
