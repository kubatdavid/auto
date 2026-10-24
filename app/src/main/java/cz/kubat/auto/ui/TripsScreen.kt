package cz.kubat.auto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kubat.auto.App
import cz.kubat.auto.data.TripRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen() {
    var trips by remember { mutableStateOf<List<TripRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            trips = withContext(Dispatchers.IO) { App.instance.db.trips() }
            delay(5000)
        }
    }
    val fmt = remember { SimpleDateFormat("EEE d.M. HH:mm", Locale("cs")) }
    LazyColumn(Modifier.fillMaxSize().background(Palette.bg).padding(8.dp)) {
        if (trips.isEmpty()) {
            item { Text("No trips yet. Start recording on the Drive tab.", color = Palette.muted, modifier = Modifier.padding(16.dp)) }
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
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(Palette.panel, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt.format(Date((t.startedAt * 1000).toLong())), color = Palette.text, fontWeight = FontWeight.SemiBold)
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
