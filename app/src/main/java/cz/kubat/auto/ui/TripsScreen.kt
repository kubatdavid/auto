package cz.kubat.auto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kubat.auto.App
import cz.kubat.auto.analysis.Row as Sample
import cz.kubat.auto.analysis.Severity
import cz.kubat.auto.analysis.TripAnalysis
import cz.kubat.auto.data.TripRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen() {
    var selected by remember { mutableStateOf<TripRow?>(null) }
    val trip = selected
    if (trip != null) {
        BackHandler { selected = null }
        TripDetail(trip, onBack = { selected = null })
    } else {
        TripList(onOpen = { selected = it })
    }
}

private val dateFmt = SimpleDateFormat("EEE d.M. HH:mm", Locale("cs"))

@Composable
private fun TripList(onOpen: (TripRow) -> Unit) {
    var trips by remember { mutableStateOf<List<TripRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            trips = withContext(Dispatchers.IO) { App.instance.db.trips() }
            delay(5000)
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(Palette.bg).padding(8.dp)) {
        if (trips.isEmpty()) {
            item { Text("No trips yet. Connect on the Drive tab and drive.", color = Palette.muted, modifier = Modifier.padding(16.dp)) }
        }
        items(trips, key = { it.uuid }) { t ->
            val minutes = ((t.endedAt ?: (System.currentTimeMillis() / 1000.0)) - t.startedAt) / 60
            val avg = if (t.distanceKm > 0.5) "%.1f L/100".format(t.fuelL / t.distanceKm * 100) else "–"
            val sync = when {
                t.finishSynced -> "synced"
                t.endedAt == null -> "recording"
                else -> "waiting to sync"
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(t) }
                    .background(Palette.panel, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dateFmt.format(Date((t.startedAt * 1000).toLong())), color = Palette.text, fontWeight = FontWeight.SemiBold)
                    Text(sync, color = if (t.finishSynced) Palette.ok else Palette.muted, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("%.1f km".format(t.distanceKm), color = Palette.text)
                    Text("%.0f min".format(minutes), color = Palette.text)
                    Text(avg, color = Palette.text)
                    Text("%.2f L".format(t.fuelL), color = Palette.text)
                    Text(t.coolantMax?.let { "max $it °C" } ?: "", color = Palette.muted)
                }
            }
        }
    }
}

private class Detail(val analysis: TripAnalysis, val track: List<Pair<Double, Double>>)

@Composable
private fun TripDetail(trip: TripRow, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(trip.uuid) {
        detail = withContext(Dispatchers.IO) {
            val db = App.instance.db
            val raw = db.sampleJson(trip.uuid)
            val t0 = raw.firstOrNull()?.optDouble("t", trip.startedAt) ?: trip.startedAt
            val rows = raw.map { Sample.fromJson(it, t0) }
            val codes = db.latestDtc(trip.uuid)
            fun list(k: String) = codes?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val mil = codes?.let { if (it.isNull("mil")) null else it.optBoolean("mil") }
            Detail(TripAnalysis(rows, list("stored"), list("pending"), mil), db.track(trip.uuid))
        }
    }
    val price = App.instance.settings.fuelPrice
    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Palette.text) }
            Text(dateFmt.format(Date((trip.startedAt * 1000).toLong())), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        val d = detail
        if (d == null) Text("Loading…", color = Palette.muted, modifier = Modifier.padding(16.dp))
        else TripBody(d, price)
    }
}

@Composable
private fun TripBody(d: Detail, price: Double) {
    val a = d.analysis
    run {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            item {
                val tiles = listOf(
                    "Distance" to "%.1f km".format(a.dist),
                    "Drive time" to TripAnalysis.fmtTime(a.duration),
                    "Average" to (a.lp100?.let { "%.1f L/100".format(it) } ?: "–"),
                    "Fuel" to (if (a.hasFuel) "%.2f L · %.0f Kč".format(a.fuel, a.fuel * price) else "–"),
                    "Avg speed (moving)" to (if (a.moving > 0) "%.0f km/h".format(a.dist / a.moving * 3600) else "–"),
                    "Top speed" to "%.0f km/h".format(a.vMax),
                    "Idling" to TripAnalysis.fmtTime(a.idle),
                    "Coolant" to (a.cMax?.let { "%.0f→%.0f · max %.0f °C".format(a.cStart, a.cEnd, it) } ?: "–"),
                    "Eco score" to (a.eco?.toString() ?: "–"),
                )
                for (pair in tiles.chunked(2)) {
                    Row {
                        for ((k, v) in pair) {
                            Column(Modifier.weight(1f).padding(3.dp).background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
                                Text(k, color = Palette.muted, fontSize = 12.sp)
                                Text(v, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (d.track.size >= 2) item {
                MapPane(
                    mapyKey = App.instance.settings.mapyKey, lat = null, lon = null, track = d.track, destination = null,
                    fitTrack = true, modifier = Modifier.fillMaxWidth().height(260.dp).padding(vertical = 4.dp),
                )
            }
            item { Heading("Health check") }
            items(a.findings) { f ->
                Item(
                    when (f.severity) { Severity.CRIT -> Palette.hot; Severity.WARN -> Palette.warm; Severity.INFO -> Palette.muted; Severity.OK -> Palette.ok },
                    f.title, f.detail,
                )
            }
            item { Heading("What to improve") }
            items(a.tips) { t -> Item(if (t.good) Palette.ok else Palette.warm, t.title, t.detail) }
            item {
                Heading("Charts")
                LineChart(a.rows, listOf(Series("speed", "Speed km/h", Palette.cold), Series("rpm", "RPM ÷ 50", Color(0xFFC58CFF), 50.0)), 200.0)
                LineChart(a.rows, listOf(Series("fuel_lph", "Fuel flow L/h", Palette.warm)), maxOf(20.0, (a.rows.maxOfOrNull { it["fuel_lph"] ?: 0.0 } ?: 0.0)))
                LineChart(a.rows, listOf(Series("iat", "Intake °C", Palette.muted), Series("coolant", "Coolant °C", Color(0xFFFF8A3D))), 120.0, 90.0)
                LineChart(a.rows, listOf(Series("stft", "Short trim %", Palette.cold), Series("ltft", "Long trim %", Palette.warm)), 25.0, 0.0, yMin = -25.0)
                BandsChart(a.bands)
                Box(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Item(color: Color, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(Modifier.padding(top = 6.dp, end = 10.dp).size(9.dp).background(color, CircleShape))
        Column {
            Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (detail.isNotEmpty()) Text(detail, color = Palette.muted, fontSize = 13.sp)
        }
    }
}
