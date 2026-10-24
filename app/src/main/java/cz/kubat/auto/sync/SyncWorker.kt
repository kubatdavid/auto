package cz.kubat.auto.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kubat.auto.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class ApiException(val code: Int, msg: String) : IOException(msg)

/** Minimal client for the auto API (bearer token, gzip JSON bodies). */
class Api(private val baseUrl: String, private val token: String) {

    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val gz = ByteArrayOutputStream().also { out ->
                    GZIPOutputStream(out).use { it.write(body.toString().toByteArray()) }
                }.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setFixedLengthStreamingMode(gz.size)
                conn.outputStream.use { it.write(gz) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw ApiException(code, "HTTP $code: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Uploads trips in order: PUT trip → POST samples in batches (idempotent by seq) → DTC
 * snapshots → finish once the trip has ended and every sample is on the server.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val s = app.settings
        if (s.token.isEmpty()) return@withContext Result.success()
        val api = Api(s.serverUrl, s.token)
        try {
            if (s.mapyKey.isEmpty()) fetchConfig(app)
            for (trip in app.db.tripsToSync()) {
                val base = "/api/v1/trips/${trip.uuid}"
                if (!trip.tripSynced) {
                    api.request("PUT", base, JSONObject().put("started_at", trip.startedAt))
                    app.db.markTripSynced(trip.uuid)
                }
                var synced = trip.syncedSeq
                while (true) {
                    val batch = app.db.samplesAfter(trip.uuid, synced, BATCH)
                    if (batch.isEmpty()) break
                    val arr = JSONArray()
                    for ((_, json) in batch) arr.put(JSONObject(json))
                    api.request("POST", "$base/samples", JSONObject().put("samples", arr))
                    synced = batch.last().first
                    app.db.markSyncedSeq(trip.uuid, synced)
                }
                for ((t, json) in app.db.unsyncedDtcs(trip.uuid)) {
                    api.request("POST", "$base/dtc", JSONObject(json))
                    app.db.markDtcSynced(trip.uuid, t)
                }
                val ended = trip.endedAt
                if (ended != null && synced >= app.db.maxSeq(trip.uuid)) {
                    api.request("POST", "$base/finish", JSONObject().put("ended_at", ended))
                    app.db.markFinishSynced(trip.uuid)
                }
            }
            Result.success()
        } catch (e: ApiException) {
            if (e.code == 401) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private const val BATCH = 2000
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun fetchConfig(app: App) {
            val cfg = Api(app.settings.serverUrl, app.settings.token).request("GET", "/api/v1/config")
            app.settings.mapyKey = cfg.optString("mapy_key", "")
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NETWORK).build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(NETWORK).build(),
            )
        }
    }
}
