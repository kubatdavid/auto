package cz.kubat.auto.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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

@Composable
fun DriveScreen(live: Live, onStart: () -> Unit, onStop: () -> Unit) {
    val app = App.instance
    val compact = LocalConfiguration.current.screenHeightDp < 520 // split-screen with the navigation app
    var destination by remember { mutableStateOf<Place?>(null) }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        for (a in live.alerts) {
            Text(
                a.text, color = if (a.critical) Color.White else Color(0xFF1A1200), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(if (a.critical) Palette.hot else Palette.warm).padding(12.dp),
            )
        }
        StatusBar(live, onStart, onStop)
        BigValues(live)
        if (!compact) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                MapPane(
                    mapyKey = app.settings.mapyKey, lat = live.lat, lon = live.lon, track = live.track,
                    destination = destination?.let { it.lat to it.lon }, modifier = Modifier.fillMaxSize(),
                )
                SearchBox(live, destination, onPick = { destination = it }, modifier = Modifier.align(Alignment.TopCenter))
            }
            TripStrip(live, app.settings.fuelPrice)
        }
        Chips(live)
    }
}

@Composable
private fun StatusBar(live: Live, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text("Start", color = Color(0xFF08130C))
            }
        } else {
            OutlinedButton(onClick = onStop) { Text("Stop", color = Palette.text) }
        }
    }
}

@Composable
private fun BigValues(live: Live) {
    val v = live.values
    val speed = v["speed"]
    val lph = live.instantLph
    val (consumption, consumptionUnit) = when {
        lph == null -> "–" to "L/100 km"
        (speed ?: 0.0) >= 5 -> "%.1f".format(minOf(99.0, lph / speed!! * 100)) to "L/100 km"
        else -> "%.1f".format(lph) to "L/h"
    }
    val coolant = v["coolant"]
    val coolantColor = when {
        coolant == null -> Palette.text
        coolant < 70 -> Palette.cold
        coolant < 85 -> Palette.warm
        coolant <= 105 -> Palette.ok
        else -> Palette.hot
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            Big(speed?.toInt()?.toString() ?: "–", "km/h", Modifier.weight(1f))
            Big(v["rpm"]?.toInt()?.toString() ?: "–", "rpm", Modifier.weight(1f))
        }
        Row {
            Big(consumption, consumptionUnit, Modifier.weight(1f))
            Big(coolant?.toInt()?.toString() ?: "–", "°C coolant", Modifier.weight(1f), coolantColor)
        }
    }
}

@Composable
private fun Big(value: String, unit: String, modifier: Modifier, color: Color = Palette.text) {
    Column(modifier.padding(4.dp).background(Palette.panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(value, color = color, fontSize = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(unit, color = Palette.muted, fontSize = 13.sp)
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
private fun SearchBox(live: Live, destination: Place?, onPick: (Place?) -> Unit, modifier: Modifier) {
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
            suggest(query, key, live.lat, live.lon)
        } catch (e: Exception) {
            emptyList()
        }
    }

    Column(modifier.fillMaxWidth().padding(8.dp)) {
        if (destination == null) {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search destination (mapy.com)") },
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
                IconButton(onClick = { onPick(null) }) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Palette.muted) }
            }
        }
    }
}

/** Opens Waze; with the app in split-screen it lands in the other half (FLAG_ACTIVITY_LAUNCH_ADJACENT). */
fun openWaze(context: Context, p: Place) {
    val uri = Uri.parse("https://waze.com/ul?ll=${p.lat},${p.lon}&navigate=yes")
    context.startActivity(
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
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
