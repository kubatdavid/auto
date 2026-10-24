package cz.kubat.auto.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import cz.kubat.auto.App
import cz.kubat.auto.data.AppLog
import cz.kubat.auto.obd.Elm
import cz.kubat.auto.obd.Pids
import cz.kubat.auto.sync.SyncWorker
import cz.kubat.auto.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Foreground service: keeps the ELM327 session alive, polls PIDs, attaches GPS, writes samples
 * to the local DB and publishes [Live]. A trip starts when the ECU answers with the engine
 * running and ends after [TRIP_END_AFTER_S] without ECU data.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var location: Location? = null
    private val app get() = application as App

    private val locationListener = LocationListener { location = it }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (job == null) {
            AppLog.i("Recorder: started")
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "auto:recorder").apply { acquire() }
            startGps()
            job = scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        wakeLock?.release()
        endTrip()
        AppLog.i("Recorder: stopped")
        app.live.update { it.copy(link = Link.STOPPED, status = "Not recording", alerts = emptyList()) }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Exception) {
            AppLog.e("GPS unavailable", e)
            app.live.update { it.copy(status = "GPS unavailable: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ trip state
    private var tripUuid: String? = null
    private var tripStart = 0.0
    private var seq = 0
    private var distance = 0.0
    private var fuel = 0.0
    private var coolantMax: Int? = null
    private var speedMax = 0
    private var lastT: Double? = null
    private var lastEcuData = 0.0
    private val track = ArrayList<Pair<Double, Double>>()
    private val alerted = HashSet<String>()
    private var initialCodes: Set<String>? = null

    private fun beginTrip(now: Double) {
        tripUuid = UUID.randomUUID().toString()
        tripStart = now
        seq = 0; distance = 0.0; fuel = 0.0; coolantMax = null; speedMax = 0; lastT = null
        track.clear(); alerted.clear()
        app.db.closeDanglingTrips(tripUuid)
        app.db.startTrip(tripUuid!!, now)
        AppLog.i("Trip ${tripUuid!!.take(8)} started")
    }

    private fun endTrip() {
        val uuid = tripUuid ?: return
        app.db.endTrip(uuid, lastT ?: tripStart)
        AppLog.i("Trip ${uuid.take(8)} ended: %.1f km, %.2f L, $seq samples".format(distance, fuel))
        tripUuid = null
        initialCodes = null
        SyncWorker.runNow(this)
        app.live.update { it.copy(tripUuid = null) }
    }

    // ------------------------------------------------------------------ main loop
    private suspend fun loop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        while (scope.isActive) {
            val address = app.settings.adapterAddress
            if (address.isEmpty()) {
                publish(Link.WAITING, "Pick the OBD adapter in Settings")
                delay(3000); continue
            }
            val elm = Elm(adapter, address)
            try {
                publish(Link.CONNECTING, "Connecting to adapter…")
                elm.connect()
                publish(Link.LIVE, "Live")
                poll(elm)
            } catch (e: Exception) {
                AppLog.w("Link lost/failed: ${e.javaClass.simpleName}: ${e.message}")
                publish(Link.WAITING, "Waiting for car: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                elm.close()
            }
            if (tripUuid != null && now() - lastEcuData > TRIP_END_AFTER_S) endTrip()
            delay(3000)
        }
    }

    private suspend fun poll(elm: Elm) {
        val cur = HashMap<String, Double?>()
        var n = 0
        var slowI = 0
        var empty = 0
        val rate = ArrayDeque<Double>()
        readCodes(elm)
        while (scope.isActive) {
            for (p in Pids.FAST) cur[Pids.ALL.getValue(p).field] = elm.read(p)
            repeat(Pids.SLOW_PER_CYCLE) {
                val p = Pids.SLOW[slowI++ % Pids.SLOW.size]
                cur[Pids.ALL.getValue(p).field] = elm.read(p)
            }
            val t = now()
            val ecuData = Pids.FAST.any { cur[Pids.ALL.getValue(it).field] != null }
            empty = if (ecuData) 0 else empty + 1
            if (empty >= 5) throw IllegalStateException("ECU not responding (ignition off?)")
            if (!ecuData) continue
            lastEcuData = t
            if (n % 10 == 0) cur["volts"] = elm.volts()
            if (n % 300 == 299) readCodes(elm)
            n++

            val running = (cur["rpm"] ?: 0.0) > 300
            if (tripUuid == null && running) {
                beginTrip(t)
                readCodes(elm)
            }
            val uuid = tripUuid ?: continue
            cur["fuel_lph"] = Pids.fuelLph(cur)
            record(uuid, t, cur)

            rate.addLast(t)
            while (rate.size > 20) rate.removeFirst()
            val sps = if (rate.size > 1) (rate.size - 1) / (rate.last() - rate.first()) else 0.0
            app.live.update {
                it.copy(
                    link = Link.LIVE, status = "Live", values = HashMap(cur), lat = location?.latitude,
                    lon = location?.longitude, tripUuid = uuid, tripStartedAt = tripStart, tripSeconds = t - tripStart,
                    distanceKm = distance, fuelL = fuel, coolantMax = coolantMax, samplesPerSec = sps,
                    instantLph = cur["fuel_lph"], alerts = alerts(cur), track = ArrayList(track),
                )
            }
        }
    }

    private fun record(uuid: String, t: Double, cur: Map<String, Double?>) {
        val prev = lastT
        if (prev != null && (cur["rpm"] ?: 0.0) > 300) {
            val dt = t - prev
            if (dt in 0.0..10.0) {
                distance += (cur["speed"] ?: 0.0) * dt / 3600
                fuel += (cur["fuel_lph"] ?: 0.0) * dt / 3600
            }
        }
        lastT = t
        cur["coolant"]?.let { coolantMax = maxOf(coolantMax ?: -99, it.toInt()) }
        speedMax = maxOf(speedMax, (cur["speed"] ?: 0.0).toInt())

        val j = JSONObject().put("seq", seq).put("t", t)
        for ((k, v) in cur) if (v != null) j.put(k, round(v))
        val loc = location
        if (loc != null && System.currentTimeMillis() - loc.time < 5000) {
            j.put("lat", loc.latitude).put("lon", loc.longitude)
            if (loc.hasSpeed()) j.put("gps_speed", round(loc.speed * 3.6))
            if (loc.hasAltitude()) j.put("alt", round(loc.altitude))
            if (loc.hasAccuracy()) j.put("accuracy", round(loc.accuracy.toDouble()))
            if (track.lastOrNull() != loc.latitude to loc.longitude) track += loc.latitude to loc.longitude
        }
        app.db.addSample(uuid, seq, j)
        seq++
        if (seq % 10 == 0) app.db.updateTripStats(uuid, distance, fuel, coolantMax, speedMax, seq)
    }

    private fun readCodes(elm: Elm) {
        val c = elm.codes()
        AppLog.i("Codes: MIL ${c.mil}, stored ${c.stored}, pending ${c.pending}")
        if (initialCodes == null) initialCodes = c.stored.toSet()
        app.live.update { it.copy(codes = c) }
        val uuid = tripUuid ?: return
        app.db.addDtc(
            uuid, c.t, JSONObject().put("t", c.t).put("mil", c.mil).put("stored", JSONArray(c.stored))
                .put("pending", JSONArray(c.pending)).put("freeze", JSONObject(c.freeze)),
        )
    }

    private fun alerts(cur: Map<String, Double?>): List<Alert> {
        val out = ArrayList<Alert>()
        val coolant = cur["coolant"]
        if (coolant != null && coolant >= 110) out += Alert("overheat", true, "ENGINE OVERHEATING ${coolant.toInt()} °C — stop safely")
        val volts = cur["volts"]
        if (volts != null && volts < 12.8 && (cur["rpm"] ?: 0.0) > 300 && now() - tripStart > 60) {
            out += Alert("charging", true, "BATTERY NOT CHARGING (${"%.1f".format(volts)} V)")
        }
        val codes = app.live.state.value.codes
        val known = initialCodes ?: emptySet()
        codes?.stored?.filter { it !in known }?.forEach { out += Alert("dtc-$it", false, "New fault code $it") }
        for (a in out) if (alerted.add(a.key)) signal(a.critical)
        return out
    }

    private fun signal(critical: Boolean) {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, if (critical) 1500 else 400)
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(if (critical) 1000 else 300, 255))
        } catch (_: Exception) {
        }
    }

    private var lastStatus = ""

    private fun publish(link: Link, status: String) {
        if (status != lastStatus) AppLog.i("Status: $status")
        lastStatus = status
        app.live.update { it.copy(link = link, status = status) }
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Auto — recording")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val TRIP_END_AFTER_S = 120

        private fun now() = System.currentTimeMillis() / 1000.0
        private fun round(v: Double) = Math.round(v * 1000) / 1000.0

        fun start(context: Context) = context.startForegroundService(Intent(context, RecorderService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
    }
}
