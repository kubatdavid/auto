package cz.kubat.auto.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

class ObdException(msg: String) : IOException(msg)

/** Snapshot of fault codes; freeze frame holds conditions when the first stored DTC was set. */
data class Codes(
    val t: Double,
    val mil: Boolean?,
    val stored: List<String>,
    val pending: List<String>,
    val freeze: Map<String, Any?>,
)

/**
 * ELM327 over Bluetooth SPP. Strictly read-only: [cmd] refuses anything outside the
 * allowlist, and service 04 (clear DTCs) can never be sent.
 */
class Elm(private val adapter: BluetoothAdapter, private val address: String) {
    private var socket: BluetoothSocket? = null
    private var supported: Set<String> = emptySet()
    private var suffix = ""

    @SuppressLint("MissingPermission")
    fun connect() {
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(SPP)
        s.connect()
        socket = s
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0", "ATAT2")) cmd(c)
        val b = rawPid("0100", timeoutMs = 20_000) ?: throw ObdException("ECU not responding (ignition off?)")
        supported = (0 until 32).filter { i -> (b[i / 8].toInt() shr (7 - i % 8)) and 1 == 1 }
            .map { "01%02X".format(it + 1) }.toSet()
        // "010C1" = expect one reply: the adapter answers immediately instead of waiting for its timeout
        suffix = if (rawPid("010C", suffixOverride = "1") != null) "1" else ""
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    fun cmd(c: String, timeoutMs: Long = 5_000): List<String> {
        val u = c.uppercase()
        require(!u.startsWith("04") && ALLOWED.any { u.startsWith(it) }) { "refused non-read command $c" }
        val s = socket ?: throw ObdException("not connected")
        val input = s.inputStream
        while (input.available() > 0) input.read() // drop stale bytes
        s.outputStream.write((c + "\r").toByteArray())
        s.outputStream.flush()
        val buf = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (input.available() > 0) {
                val ch = input.read()
                if (ch < 0) throw ObdException("adapter closed connection")
                if (ch == '>'.code) {
                    return buf.toString().replace("\n", "\r").split("\r").map { it.trim() }.filter { it.isNotEmpty() }
                }
                buf.append(ch.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        throw ObdException("no reply to $c")
    }

    /** Data bytes of a mode 01/02 reply, or null for NO DATA / unsupported. */
    fun rawPid(p: String, timeoutMs: Long = 5_000, suffixOverride: String? = null): ByteArray? {
        val want = "4${p[1]} ${p.substring(2, 4)}" // "0105" -> "41 05", "020500" -> "42 05"
        for (line in cmd(p + (suffixOverride ?: suffix), timeoutMs)) {
            if (!line.startsWith(want)) continue
            val hex = line.substring(want.length).replace(" ", "")
            if (hex.length % 2 != 0 || !hex.all { it.isDigit() || it in 'A'..'F' }) return null
            val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return if (p.startsWith("02")) b.copyOfRange(minOf(1, b.size), b.size) else b // drop frame number
        }
        return null
    }

    fun read(pid: String): Double? {
        if (supported.isNotEmpty() && pid !in supported) return null
        val b = rawPid(pid) ?: return null
        return try {
            Pids.ALL.getValue(pid).decode(b)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun volts(): Double? = cmd("ATRV").lastOrNull()?.trimEnd('V', 'v')?.toDoubleOrNull()

    private fun dtcs(service: String): List<String> {
        val prefix = "4${service[1]}"
        val out = mutableListOf<String>()
        for (line in cmd(service, 10_000)) {
            if (!line.startsWith(prefix)) continue
            val hex = line.substring(2).replace(" ", "")
            val b = (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            for (i in 0 until b.size - 1 step 2) {
                val v = (b[i] shl 8) or b[i + 1]
                if (v != 0) out += Pids.dtcName(v)
            }
        }
        return out
    }

    fun codes(): Codes {
        val status = rawPid("0101")
        val freeze = mutableMapOf<String, Any?>()
        rawPid("020200")?.takeIf { it.size >= 2 }?.let {
            val v = ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
            freeze["dtc"] = if (v != 0) Pids.dtcName(v) else null
        }
        for ((pid, field) in listOf("0205" to "coolant", "020C" to "rpm", "020D" to "speed", "0204" to "load")) {
            val b = rawPid(pid + "00") ?: continue
            freeze[field] = Pids.ALL.getValue("01" + pid.substring(2)).decode(b)
        }
        return Codes(
            t = System.currentTimeMillis() / 1000.0,
            mil = status?.let { (it[0].toInt() and 0x80) != 0 },
            stored = dtcs("03"),
            pending = dtcs("07"),
            freeze = freeze,
        )
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Adapter setup + OBD read services only. Service 04 (clear DTCs) is never sent.
        private val ALLOWED = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0", "ATAT2", "ATRV", "01", "02", "03", "07")
    }
}
