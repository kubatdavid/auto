package cz.kubat.auto.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class TripRow(
    val uuid: String,
    val startedAt: Double,
    val endedAt: Double?,
    val distanceKm: Double,
    val fuelL: Double,
    val coolantMax: Int?,
    val speedMax: Int,
    val samples: Int,
    val syncedSeq: Int,
    val tripSynced: Boolean,
    val finishSynced: Boolean,
)

/** Local store: everything is written here first; [cz.kubat.auto.sync.SyncWorker] uploads later. */
class Db(context: Context) : SQLiteOpenHelper(context, "auto.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trip (
                uuid TEXT PRIMARY KEY, started_at REAL NOT NULL, ended_at REAL,
                distance_km REAL NOT NULL DEFAULT 0, fuel_l REAL NOT NULL DEFAULT 0,
                coolant_max INTEGER, speed_max INTEGER NOT NULL DEFAULT 0, samples INTEGER NOT NULL DEFAULT 0,
                synced_seq INTEGER NOT NULL DEFAULT -1, trip_synced INTEGER NOT NULL DEFAULT 0,
                finish_synced INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE TABLE sample (trip_uuid TEXT NOT NULL, seq INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY (trip_uuid, seq))")
        db.execSQL("CREATE TABLE dtc (trip_uuid TEXT NOT NULL, t REAL NOT NULL, json TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (trip_uuid, t))")
        createLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLog(db)
    }

    private fun createLog(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, t REAL NOT NULL, level TEXT NOT NULL, msg TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
    }

    fun addLog(line: AppLog.Line) {
        writableDatabase.insert("log", null, ContentValues().apply {
            put("t", line.t)
            put("level", line.level)
            put("msg", line.msg)
        })
    }

    /** (id, line) pairs not yet on the server, oldest first. */
    fun unsyncedLogs(limit: Int): List<Pair<Long, AppLog.Line>> =
        readableDatabase.rawQuery("SELECT id, t, level, msg FROM log WHERE synced = 0 ORDER BY id LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to AppLog.Line(c.getDouble(1), c.getString(2), c.getString(3))) }
        }

    fun markLogsSynced(upToId: Long) {
        writableDatabase.execSQL("UPDATE log SET synced = 1 WHERE id <= ?", arrayOf<Any>(upToId))
        writableDatabase.execSQL("DELETE FROM log WHERE synced = 1 AND id < ?", arrayOf<Any>(upToId - 5000))
    }

    fun startTrip(uuid: String, startedAt: Double) {
        writableDatabase.insert("trip", null, ContentValues().apply {
            put("uuid", uuid)
            put("started_at", startedAt)
        })
    }

    fun addSample(uuid: String, seq: Int, sample: JSONObject) {
        writableDatabase.insertWithOnConflict("sample", null, ContentValues().apply {
            put("trip_uuid", uuid)
            put("seq", seq)
            put("json", sample.toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateTripStats(uuid: String, distanceKm: Double, fuelL: Double, coolantMax: Int?, speedMax: Int, samples: Int) {
        writableDatabase.update("trip", ContentValues().apply {
            put("distance_km", distanceKm)
            put("fuel_l", fuelL)
            if (coolantMax != null) put("coolant_max", coolantMax)
            put("speed_max", speedMax)
            put("samples", samples)
        }, "uuid = ?", arrayOf(uuid))
    }

    fun endTrip(uuid: String, endedAt: Double) {
        writableDatabase.update("trip", ContentValues().apply { put("ended_at", endedAt) }, "uuid = ?", arrayOf(uuid))
    }

    /** Trips left open by a crash or a killed service: end them at their last sample. */
    fun closeDanglingTrips(exceptUuid: String?) {
        val open = readableDatabase.rawQuery("SELECT uuid, started_at FROM trip WHERE ended_at IS NULL", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) } }
        for ((uuid, started) in open) {
            if (uuid == exceptUuid) continue
            val lastT = readableDatabase.rawQuery(
                "SELECT json FROM sample WHERE trip_uuid = ? ORDER BY seq DESC LIMIT 1", arrayOf(uuid),
            ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)).optDouble("t", started) else started }
            endTrip(uuid, lastT)
        }
    }

    fun addDtc(uuid: String, t: Double, json: JSONObject) {
        writableDatabase.insertWithOnConflict("dtc", null, ContentValues().apply {
            put("trip_uuid", uuid)
            put("t", t)
            put("json", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun trips(limit: Int = 200): List<TripRow> =
        readableDatabase.rawQuery("SELECT * FROM trip ORDER BY started_at DESC LIMIT $limit", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TripRow(
                            uuid = c.getString(c.getColumnIndexOrThrow("uuid")),
                            startedAt = c.getDouble(c.getColumnIndexOrThrow("started_at")),
                            endedAt = c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getDouble(it) },
                            distanceKm = c.getDouble(c.getColumnIndexOrThrow("distance_km")),
                            fuelL = c.getDouble(c.getColumnIndexOrThrow("fuel_l")),
                            coolantMax = c.getColumnIndexOrThrow("coolant_max").let { if (c.isNull(it)) null else c.getInt(it) },
                            speedMax = c.getInt(c.getColumnIndexOrThrow("speed_max")),
                            samples = c.getInt(c.getColumnIndexOrThrow("samples")),
                            syncedSeq = c.getInt(c.getColumnIndexOrThrow("synced_seq")),
                            tripSynced = c.getInt(c.getColumnIndexOrThrow("trip_synced")) == 1,
                            finishSynced = c.getInt(c.getColumnIndexOrThrow("finish_synced")) == 1,
                        )
                    )
                }
            }
        }

    fun tripsToSync(): List<TripRow> = trips(10_000).filter { !it.finishSynced }.reversed()

    fun maxSeq(uuid: String): Int =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), -1) FROM sample WHERE trip_uuid = ?", arrayOf(uuid))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    fun samplesAfter(uuid: String, afterSeq: Int, limit: Int): List<Pair<Int, String>> =
        readableDatabase.rawQuery(
            "SELECT seq, json FROM sample WHERE trip_uuid = ? AND seq > ? ORDER BY seq LIMIT $limit",
            arrayOf(uuid, afterSeq.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) } }

    fun unsyncedDtcs(uuid: String): List<Pair<Double, String>> =
        readableDatabase.rawQuery("SELECT t, json FROM dtc WHERE trip_uuid = ? AND synced = 0", arrayOf(uuid))
            .use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getString(1)) } }

    fun markDtcSynced(uuid: String, t: Double) {
        writableDatabase.execSQL("UPDATE dtc SET synced = 1 WHERE trip_uuid = ? AND t = ?", arrayOf<Any>(uuid, t))
    }

    fun markTripSynced(uuid: String) = setFlag(uuid, "trip_synced")
    fun markFinishSynced(uuid: String) = setFlag(uuid, "finish_synced")

    fun markSyncedSeq(uuid: String, seq: Int) {
        writableDatabase.execSQL("UPDATE trip SET synced_seq = ? WHERE uuid = ?", arrayOf<Any>(seq, uuid))
    }

    /** Track (lat, lon) of a trip for the map. */
    fun track(uuid: String): List<Pair<Double, Double>> =
        readableDatabase.rawQuery("SELECT json FROM sample WHERE trip_uuid = ? ORDER BY seq", arrayOf(uuid)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val j = JSONObject(c.getString(0))
                    if (j.has("lat") && j.has("lon")) add(j.getDouble("lat") to j.getDouble("lon"))
                }
            }
        }

    private fun setFlag(uuid: String, column: String) {
        writableDatabase.execSQL("UPDATE trip SET $column = 1 WHERE uuid = ?", arrayOf(uuid))
    }
}
