package cz.kubat.auto.data

import android.util.Log
import cz.kubat.auto.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug log: shown in Settings, stored in the local DB and uploaded to the server
 * (POST /api/v1/logs) by the sync worker so problems on the phone can be diagnosed remotely.
 */
object AppLog {
    data class Line(val t: Double, val level: String, val msg: String)

    private const val KEEP = 300
    private val buffer = ArrayDeque<Line>()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String, t: Throwable? = null) =
        add("E", if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = Line(System.currentTimeMillis() / 1000.0, level, msg)
        buffer.addLast(line)
        while (buffer.size > KEEP) buffer.removeFirst()
        _lines.value = buffer.toList()
        Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, "auto", msg)
        try {
            App.instance.db.addLog(line)
        } catch (_: Exception) {
            // DB not ready yet (very early startup): the line stays in memory only
        }
    }
}
