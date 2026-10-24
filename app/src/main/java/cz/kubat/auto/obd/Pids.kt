package cz.kubat.auto.obd

/** Mode 01 PIDs used for logging: pid -> (field name, decoder of the data bytes). */
object Pids {
    class Pid(val field: String, val decode: (ByteArray) -> Double)

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    val ALL: Map<String, Pid> = mapOf(
        "0103" to Pid("fuelsys") { u(it, 0).toDouble() }, // 1 OL cold, 2 CL, 4 OL load/decel, 8 OL fault
        "0104" to Pid("load") { u(it, 0) * 100.0 / 255 },
        "0105" to Pid("coolant") { u(it, 0) - 40.0 },
        "0106" to Pid("stft") { (u(it, 0) - 128) * 100.0 / 128 },
        "0107" to Pid("ltft") { (u(it, 0) - 128) * 100.0 / 128 },
        "010C" to Pid("rpm") { (u(it, 0) * 256 + u(it, 1)) / 4.0 },
        "010D" to Pid("speed") { u(it, 0).toDouble() },
        "010E" to Pid("timing") { u(it, 0) / 2.0 - 64 },
        "010F" to Pid("iat") { u(it, 0) - 40.0 },
        "0110" to Pid("maf") { (u(it, 0) * 256 + u(it, 1)) / 100.0 },
        "0111" to Pid("throttle") { u(it, 0) * 100.0 / 255 },
        "0115" to Pid("o2_down") { u(it, 0) / 200.0 }, // O2 bank 1 sensor 2 (after catalyst), volts
        "0121" to Pid("dist_mil") { (u(it, 0) * 256 + u(it, 1)).toDouble() }, // km driven with check-engine light on
    )

    /** Every cycle: needed for distance and fuel integration. */
    val FAST = listOf("010D", "010C", "0110", "0111")

    /** Rotated, [SLOW_PER_CYCLE] per cycle. */
    val SLOW = listOf("0105", "0104", "0106", "0107", "0103", "010F", "010E", "0115", "0121")
    const val SLOW_PER_CYCLE = 2

    private const val AFR = 14.7 // stoichiometric, petrol
    private const val FUEL_DENSITY = 745.0 // g/L, petrol

    /** Fuel flow estimate (L/h) from mass airflow; 0 while the injectors are cut on overrun. */
    fun fuelLph(v: Map<String, Double?>): Double? {
        val maf = v["maf"] ?: return null
        val throttle = v["throttle"] ?: 0.0
        if (throttle < 5 && (v["rpm"] ?: 0.0) > 1300 && (v["speed"] ?: 0.0) > 10) return 0.0
        val trim = 1 + ((v["stft"] ?: 0.0) + (v["ltft"] ?: 0.0)) / 100
        val enrich = when (v["fuelsys"]?.toInt()) {
            1 -> 1.08
            4 -> 1.12
            else -> 1.0
        }
        return maf * 3600 / (AFR * FUEL_DENSITY) * trim * enrich
    }

    fun dtcName(v: Int): String =
        "PCBU"[v shr 14].toString() + ((v shr 12) and 3) + "%03X".format(v and 0xFFF)
}
