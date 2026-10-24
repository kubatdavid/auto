package cz.kubat.auto.record

import cz.kubat.auto.obd.Codes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Link { STOPPED, CONNECTING, LIVE, WAITING }

data class Alert(val key: String, val critical: Boolean, val text: String)

data class Live(
    val link: Link = Link.STOPPED,
    val status: String = "Not recording",
    val values: Map<String, Double?> = emptyMap(),
    val lat: Double? = null,
    val lon: Double? = null,
    val codes: Codes? = null,
    val tripUuid: String? = null,
    val tripStartedAt: Double? = null,
    val tripSeconds: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val coolantMax: Int? = null,
    val samplesPerSec: Double = 0.0,
    val instantLph: Double? = null,
    val alerts: List<Alert> = emptyList(),
    val track: List<Pair<Double, Double>> = emptyList(),
)

class LiveState {
    private val _state = MutableStateFlow(Live())
    val state: StateFlow<Live> = _state

    fun update(f: (Live) -> Live) = _state.update(f)
}
