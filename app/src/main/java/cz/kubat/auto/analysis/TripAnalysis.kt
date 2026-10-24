package cz.kubat.auto.analysis

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One logged sample (fields as recorded; null = not read). */
class Row(val elapsed: Double, val v: Map<String, Double>) {
    operator fun get(k: String): Double? = v[k]

    companion object {
        fun fromJson(json: JSONObject, t0: Double): Row {
            val m = HashMap<String, Double>()
            for (k in json.keys()) if (k != "seq") json.optDouble(k).takeIf { !it.isNaN() }?.let { m[k] = it }
            return Row((m["t"] ?: t0) - t0, m)
        }
    }
}

enum class Severity { CRIT, WARN, INFO, OK }
class Finding(val severity: Severity, val title: String, val detail: String)
class Tip(val title: String, val detail: String, val good: Boolean)
class Band(val lo: Int, val hi: Int) {
    var km = 0.0
    var fuel = 0.0
    val lp100 get() = if (km > 0.3) fuel / km * 100 else null
    val label get() = if (hi > 300) "$lo+" else "$lo–$hi"
}

/** Port of the prototype dashboard's analyze()/health()/tips() (car/dashboard.html). */
class TripAnalysis(val rows: List<Row>, stored: List<String>, pending: List<String>, mil: Boolean?) {
    var duration = 0.0; var run = 0.0; var moving = 0.0; var dist = 0.0; var fuel = 0.0; var fuelKnown = 0.0
    var idle = 0.0; var idleFuel = 0.0; var cut = 0.0; var accel = 0; var brake = 0; var hiRpm = 0.0; var lug = 0.0
    var olCold = 0.0; var coldD = 0.0; var coldF = 0.0; var warmD = 0.0; var warmF = 0.0
    var cStart: Double? = null; var cEnd: Double? = null; var cMax: Double? = null; var firstWarm: Double? = null
    var vMax = 0.0; var maxMaf: Double? = null; var maxMafRpm = 0.0
    val bands = listOf(1 to 30, 30 to 50, 50 to 70, 70 to 90, 90 to 110, 110 to 130, 130 to 999).map { Band(it.first, it.second) }
    private val runV = ArrayList<Double>(); private val offV = ArrayList<Double>(); private val idleRpm = ArrayList<Double>()
    private val ltft = ArrayList<Double>(); private val trimIdle = ArrayList<Double>(); private val trimCruise = ArrayList<Double>()
    private val o2 = ArrayList<Double>()

    val hasFuel get() = fuelKnown > 0
    val lp100 get() = if (hasFuel && dist > 0.5) fuel / dist * 100 else null
    val eco: Int?
    val findings: List<Finding>
    val tips: List<Tip>

    init {
        if (rows.isNotEmpty()) duration = rows.last().elapsed - rows.first().elapsed
        var inA = false; var inB = false; var lastCut = -1e9
        for (i in rows.indices) {
            val b = rows[i]
            val rpm = b["rpm"] ?: 0.0
            val running = rpm > 300
            b["coolant"]?.let { c ->
                if (cStart == null) cStart = c
                cEnd = c; cMax = max(cMax ?: -99.0, c)
                if (firstWarm == null && c >= 80) firstWarm = b.elapsed - rows[0].elapsed
            }
            b["volts"]?.let { (if (running) runV else offV).add(it) }
            if (i == 0) continue
            val a = rows[i - 1]
            val dt = b.elapsed - a.elapsed
            if (dt <= 0 || dt > 10 || !running) continue
            val v = b["speed"] ?: 0.0
            val d = v * dt / 3600
            val lph = b["fuel_lph"]
            val f = (lph ?: 0.0) * dt / 3600
            run += dt; dist += d; fuel += f; vMax = max(vMax, v)
            if (lph != null) fuelKnown += dt
            val throttle = b["throttle"] ?: 0.0
            if (v < 1) {
                idle += dt; idleFuel += f
                if (run > 120 && throttle < 6) idleRpm += rpm
            } else {
                moving += dt
                bands.firstOrNull { v >= it.lo && v < it.hi }?.let { it.km += d; it.fuel += f }
            }
            if ((b["coolant"] ?: 99.0) < 70) { coldD += d; coldF += f } else { warmD += d; warmF += f }
            if (lph == 0.0 && v > 10) cut += dt
            if (rpm > 3000 && throttle < 60) hiRpm += dt
            if (rpm < 1300 && (b["load"] ?: 0.0) > 85 && v > 15) lug += dt
            val fs = b["fuelsys"]?.toInt()
            if (fs == 1) olCold += dt
            val va = a["speed"]; val vb = b["speed"]
            if (dt <= 3 && va != null && vb != null) {
                val acc = (vb - va) / 3.6 / dt
                if (acc > 2.5 && !inA) accel++
                inA = acc > 2.5
                if (acc < -3 && !inB) brake++
                inB = acc < -3
            }
            val st = b["stft"]; val lt = b["ltft"]
            if (fs == 2 && st != null && lt != null) {
                if (v < 1) trimIdle += st + lt else if (v > 40) trimCruise += st + lt
            }
            lt?.let { ltft += it }
            if (lph == 0.0) lastCut = b.elapsed
            val o = b["o2_down"]
            if (fs == 2 && o != null && v > 30 && b.elapsed - lastCut > 15) o2 += o
            val maf = b["maf"]
            if (throttle > 80 && rpm > 4500 && maf != null && (maxMaf == null || maf > maxMaf!!)) { maxMaf = maf; maxMafRpm = rpm }
        }
        val per10 = if (dist > 1) 10 / dist else 0.0
        val mv = max(moving, 1.0); val rn = max(run, 1.0)
        eco = if (dist >= 1) max(0.0, 100 - min(25.0, accel * per10 * 5) - min(20.0, brake * per10 * 5) -
            min(20.0, hiRpm / mv * 150) - min(15.0, idle / rn * 50) - min(10.0, lug / 6)).roundToInt() else null
        findings = health(stored, pending, mil)
        tips = tips()
    }

    private fun mean(a: List<Double>) = a.sum() / a.size
    private fun median(a: List<Double>) = a.sorted()[a.size / 2]
    private fun std(a: List<Double>): Double { val m = mean(a); return sqrt(mean(a.map { (it - m) * (it - m) })) }
    private fun pct(x: Double) = "${(x * 100).roundToInt()} %"
    private fun sg(x: Double) = "%+.1f".format(x)
    private fun c(x: Double?) = x?.roundToInt()?.toString() ?: "–"

    private fun health(stored: List<String>, pending: List<String>, mil: Boolean?): List<Finding> {
        val f = ArrayList<Finding>()
        for (code in stored) f += Finding(Severity.WARN, "Fault code $code", (DTC[code] ?: "look up this code") + if (mil == true) " — check-engine light on." else ".")
        for (code in pending) f += Finding(Severity.WARN, "Pending code $code", (DTC[code] ?: "look up this code") + " — seen once, not confirmed.")
        if (stored.isEmpty() && pending.isEmpty() && mil != null) f += Finding(Severity.OK, "No fault codes", "No stored or pending faults.")
        val cm = cMax
        if (cm != null) f += when {
            cm >= 110 -> Finding(Severity.CRIT, "Engine overheating", "Coolant reached ${c(cm)} °C. Stop, let it cool, check coolant, fan and water pump.")
            run >= 600 && cm < 75 -> Finding(Severity.WARN, "Engine not reaching operating temperature", "Max ${c(cm)} °C after ${(run / 60).roundToInt()} min (normal 85–95 °C). Thermostat stuck open.")
            run >= 600 && cm < 85 -> Finding(Severity.WARN, "Engine running cool", "Max ${c(cm)} °C (normal 85–95 °C). Thermostat may open too early.")
            run >= 600 -> Finding(Severity.OK, "Coolant temperature normal", "Max ${c(cm)} °C.")
            else -> Finding(Severity.INFO, "Engine warming up", "Coolant ${c(cEnd)} °C — judged after 10 min of running.")
        }
        if (run >= 600 && olCold / run > 0.3) f += Finding(Severity.WARN, "ECU adding extra fuel (cold-engine mode)", "Cold open loop ${pct(olCold / run)} of the time — higher consumption.")
        if (runV.size >= 3) {
            val m = median(runV)
            f += when {
                m < 13.2 -> Finding(Severity.WARN, "Charging voltage low", "%.1f V while running (normal 13.5–14.6 V). Check alternator and belt.".format(m))
                m > 14.8 -> Finding(Severity.WARN, "Charging voltage high", "%.1f V while running — voltage regulator.".format(m))
                else -> Finding(Severity.OK, "Charging system OK", "%.1f V while running.".format(m))
            }
        }
        if (offV.size >= 3 && median(offV) < 12.2) f += Finding(Severity.WARN, "Battery weak at rest", "%.1f V with engine off (healthy ≥ 12.4 V).".format(median(offV)))
        if (ltft.size >= 5) {
            val l = mean(ltft)
            val ti = if (trimIdle.size >= 5) mean(trimIdle) else null
            val tc = if (trimCruise.size >= 5) mean(trimCruise) else null
            f += when {
                ti != null && tc != null && ti > 10 && tc < ti - 6 -> Finding(Severity.WARN, "Lean at idle — likely vacuum leak", "Fuel trim ${sg(ti)} % at idle vs ${sg(tc)} % cruising. Check intake hoses and crankcase breather.")
                l > 10 -> Finding(Severity.WARN, "Mixture lean at all loads", "Long-term trim ${sg(l)} % (fine within ±10 %). Dirty MAF, low fuel pressure or air leak after the MAF.")
                l < -10 -> Finding(Severity.WARN, "Mixture rich", "Long-term trim ${sg(l)} %. Leaking injector, high fuel pressure or faulty O2 sensor.")
                else -> Finding(Severity.OK, "Fuel trims OK", "Long-term ${sg(l)} % (fine within ±10 %).")
            }
        }
        if (idleRpm.size >= 10) {
            val m = mean(idleRpm); val sd = std(idleRpm)
            f += if (sd > 60) Finding(Severity.WARN, "Unstable idle", "${m.roundToInt()} ± ${sd.roundToInt()} rpm. Misfire, vacuum leak or dirty throttle body.")
            else Finding(Severity.OK, "Idle stable", "${m.roundToInt()} ± ${sd.roundToInt()} rpm.")
        }
        maxMaf?.let {
            f += if (it < 60) Finding(Severity.WARN, "Low airflow at full throttle", "%.0f g/s at %.0f rpm (expect 80+). Dirty MAF or air filter.".format(it, maxMafRpm))
            else Finding(Severity.OK, "Full-throttle airflow OK", "%.0f g/s at %.0f rpm.".format(it, maxMafRpm))
        }
        if (o2.size >= 60 && std(o2) > 0.2) f += Finding(Severity.INFO, "Catalyst: worth watching", "Rear O2 varies (σ %.2f V) in steady driving. Only a hint — code P0420 is decisive.".format(std(o2)))
        return f.sortedBy { it.severity.ordinal }
    }

    private fun tips(): List<Tip> {
        val t = ArrayList<Tip>()
        if (dist < 1) return listOf(Tip("Not enough driving", "Tips appear after the first kilometre.", true))
        val per10 = 10 / dist; val mv = max(moving, 1.0)
        if (idle > 180 && idle / run > 0.15) t += Tip("Idling ${fmtTime(idle)} (${pct(idle / run)})", (if (hasFuel) "Burned %.2f L standing still. ".format(idleFuel) else "") + "Switch off for waits over a minute.", false)
        if (accel * per10 > 1.5) t += Tip("$accel hard accelerations", "Accelerate progressively — hard throttle switches to a rich mixture.", false)
        if (brake * per10 > 1.5) t += Tip("$brake hard braking events", "Look further ahead and lift off early: rolling in gear uses no fuel.", false)
        if (hiRpm / mv > 0.08) t += Tip("High revs ${pct(hiRpm / mv)} of driving", "Above 3000 rpm at part throttle. Shift up around 2000–2500 rpm.", false)
        if (lug > 15) t += Tip("Labouring the engine ${lug.roundToInt()} s", "Heavy load below 1300 rpm — shift down.", false)
        if (hasFuel) {
            val road = bands.filter { it.lo >= 50 && it.lp100 != null && it.km >= 1 }
            val best = road.minByOrNull { it.lp100!! }
            val fast = road.lastOrNull { it.lo >= 110 }
            if (best != null && fast != null && fast !== best && fast.lp100!! > best.lp100!! * 1.2)
                t += Tip("Speed costs: %.1f L/100 at ${fast.label} km/h".format(fast.lp100), "vs %.1f at ${best.label} km/h on this drive.".format(best.lp100), false)
            if (coldD > 2 && warmD > 2 && coldF / coldD > warmF / warmD * 1.1)
                t += Tip("Cold engine: %.1f vs %.1f L/100 warm".format(coldF / coldD * 100, warmF / warmD * 100), "Short trips with a cold engine cost the most.", false)
        }
        if (cut / mv > 0.05) t += Tip("Good: fuel cut-off ${pct(cut / mv)} of the time", "Lifting off early in gear is the cheapest way to slow down.", true)
        if (t.none { !it.good }) t += Tip("Smooth driving", "Nothing to improve in driving style on this drive.", true)
        return t
    }

    companion object {
        val DTC = mapOf(
            "P0116" to "Coolant temperature circuit range/performance", "P0117" to "Coolant temperature circuit low",
            "P0118" to "Coolant temperature circuit high", "P0125" to "Insufficient coolant temperature for closed loop",
            "P0128" to "Thermostat — coolant below regulating temperature", "P0171" to "System too lean (bank 1)",
            "P0172" to "System too rich (bank 1)", "P0300" to "Random/multiple misfire", "P0420" to "Catalyst efficiency below threshold",
        )

        fun fmtTime(s: Double): String {
            val x = s.roundToInt()
            return if (x >= 3600) "%d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60) else "%d:%02d".format(x / 60, x % 60)
        }

        @Suppress("unused")
        private fun absTrim(a: Double) = abs(a)
    }
}
