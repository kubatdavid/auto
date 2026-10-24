package cz.kubat.auto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var selected by remember { mutableStateOf(settings.driveSensors) }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 12.dp)) {
        item {
            Text(
                "Switch on what the Drive tab shows (in this order). Values update while connected.",
                color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        items(SENSORS, key = { it.key }) { s ->
            val v = s.value(live)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, color = Palette.text, fontSize = 15.sp)
                    Text(s.unit, color = Palette.muted, fontSize = 12.sp)
                }
                Text(
                    v?.let(s.format) ?: "–", color = v?.let(s.color) ?: Palette.muted,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp),
                )
                Switch(checked = s.key in selected, onCheckedChange = { on ->
                    // keep catalogue order so the Drive layout is stable
                    selected = SENSORS.map { it.key }.filter { k -> if (k == s.key) on else k in selected }
                    settings.driveSensors = selected
                })
            }
        }
    }
}
