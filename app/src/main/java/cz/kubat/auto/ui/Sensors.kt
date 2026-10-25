package cz.kubat.auto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import cz.kubat.auto.data.DriveTile
import cz.kubat.auto.data.TileSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cz.kubat.auto.record.Live

/** One displayable value: raw OBD PID, GPS, or computed from the live state. */
class Sensor(
    val key: String,
    val label: String,
    val unit: String,
    val value: (Live) -> Double?,
    val format: (Double) -> String = { "%.0f".format(it) },
    val color: (Double) -> Color = { Palette.text },
)

private fun obd(key: String) = { l: Live -> l.values[key] }
private val one = { v: Double -> "%.1f".format(v) }
private val signed = { v: Double -> "%+.1f".format(v) }

private fun coolantColor(c: Double) = when {
    c < 70 -> Palette.cold
    c < 85 -> Palette.warm
    c <= 105 -> Palette.ok
    else -> Palette.hot
}

val SENSORS: List<Sensor> = listOf(
    Sensor("speed", "Speed", "km/h", obd("speed")),
    Sensor("rpm", "RPM", "rpm", obd("rpm")),
    Sensor("consumption", "Consumption now", "L/100 km", { l ->
        val lph = l.instantLph
        val v = l.values["speed"] ?: 0.0
        if (lph != null && v >= 5) minOf(99.0, lph / v * 100) else null
    }, one),
    Sensor("fuel_lph", "Fuel flow", "L/h", { it.instantLph }, one),
    Sensor("coolant", "Coolant", "°C", obd("coolant"), color = ::coolantColor),
    Sensor("iat", "Intake air", "°C", obd("iat")),
    Sensor("load", "Engine load", "%", obd("load")),
    Sensor("throttle", "Throttle", "%", obd("throttle")),
    Sensor("maf", "Air flow (MAF)", "g/s", obd("maf"), one),
    Sensor("stft", "Short-term fuel trim", "%", obd("stft"), signed),
    Sensor("ltft", "Long-term fuel trim", "%", obd("ltft"), signed),
    Sensor("trim", "Total fuel trim", "%", { l ->
        val s = l.values["stft"]
        val t = l.values["ltft"]
        if (s != null && t != null) s + t else null
    }, signed, color = { if (kotlin.math.abs(it) > 15) Palette.warm else Palette.text }),
    Sensor("timing", "Ignition timing", "° BTDC", obd("timing"), one),
    Sensor("o2_down", "O2 after catalyst", "V", obd("o2_down"), { "%.2f".format(it) }),
    Sensor("volts", "Battery", "V", obd("volts"), one, color = { if (it < 12.2 || it > 14.8) Palette.warm else Palette.text }),
    Sensor("fuelsys", "Fuel system", "", obd("fuelsys"), {
        when (it.toInt()) {
            1 -> "Cold"
            2 -> "Closed"
            4 -> "Decel"
            8, 16 -> "Fault"
            else -> "–"
        }
    }),
    Sensor("dist_mil", "Driven with check-engine light", "km", obd("dist_mil")),
    Sensor("gps_speed", "GPS speed", "km/h", { it.gpsSpeed }),
    Sensor("alt", "Altitude", "m", { it.altitude }),
    Sensor("trip_km", "Trip distance", "km", { it.distanceKm }, one),
    Sensor("trip_avg", "Trip average", "L/100 km", { l -> if (l.distanceKm > 0.5) l.fuelL / l.distanceKm * 100 else null }, one),
    Sensor("trip_fuel", "Trip fuel", "L", { it.fuelL }, { "%.2f".format(it) }),
)
val SENSOR_BY_KEY = SENSORS.associateBy { it.key }

@Composable
fun SensorsScreen(live: Live) {
    val settings = App.instance.settings
    var tiles by remember { mutableStateOf(settings.driveTiles.filter { it.key in SENSOR_BY_KEY }) }
    fun save(t: List<DriveTile>) { tiles = t; settings.driveTiles = t }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 12.dp)) {
        item { Header("On the Drive screen — ▲▼ order, S/M/L size (third / half / full width)") }
        itemsIndexed(tiles, key = { _, t -> "on-" + t.key }) { i, t ->
            val s = SENSOR_BY_KEY.getValue(t.key)
            SensorRow(s, live) {
                IconButton(enabled = i > 0, onClick = { save(tiles.toMutableList().apply { add(i - 1, removeAt(i)) }) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = if (i > 0) Palette.text else Palette.line)
                }
                IconButton(enabled = i < tiles.size - 1, onClick = { save(tiles.toMutableList().apply { add(i + 1, removeAt(i)) }) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = if (i < tiles.size - 1) Palette.text else Palette.line)
                }
                OutlinedButton(
                    onClick = {
                        val next = TileSize.entries[(t.size.ordinal + 1) % TileSize.entries.size]
                        save(tiles.map { if (it.key == t.key) it.copy(size = next) else it })
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.width(48.dp),
                ) { Text(t.size.name, color = Palette.text) }
                Switch(checked = true, onCheckedChange = { save(tiles.filter { it.key != t.key }) })
            }
        }
        item { Header("Available") }
        items(SENSORS.filter { s -> tiles.none { it.key == s.key } }, key = { "off-" + it.key }) { s ->
            SensorRow(s, live) {
                Switch(checked = false, onCheckedChange = { save(tiles + DriveTile(s.key, TileSize.S)) })
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun SensorRow(s: Sensor, live: Live, controls: @Composable () -> Unit) {
    val v = s.value(live)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.panel, RoundedCornerShape(10.dp))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.label, color = Palette.text, fontSize = 15.sp, maxLines = 1)
            Text(
                (v?.let(s.format) ?: "–") + if (s.unit.isNotEmpty()) " " + s.unit else "",
                color = v?.let(s.color) ?: Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        controls()
    }
}
