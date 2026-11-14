package com.offerscorer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.offerscorer.core.JobType
import com.offerscorer.core.Offer
import com.offerscorer.core.Score

data class OfferRecord(
    val id: Long,
    val ts: Long,
    val type: JobType,
    val fare: Double,
    val miles: Double,
    val minutes: Double,
    val net: Double,
    val hourly: Double,
    val verdict: String,
    val decision: String,
    val source: String,
    val pickupMin: Double,
    val pickupMi: Double,
    val tripMin: Double,
    val tripMi: Double,
    val pickups: Int,
    val dropoffs: Int,
)

data class ShiftRecord(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val miles: Double,
    val source: String, // "gps" or "manual"
    val purpose: String,
    val startOdo: Double?,
    val endOdo: Double?,
    val active: Boolean,
)

object Decision {
    const val SHOWN = "Shown"
    const val ACCEPTED = "Accepted"
    const val NOT_ACCEPTED = "Not accepted"
}

/** Everything is stored on the phone only, in one SQLite file in the app's private storage. */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "offerscorer.db", null, 1) {

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE offers(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, fare REAL NOT NULL,
                pickup_min REAL, pickup_mi REAL, trip_min REAL, trip_mi REAL, total_min REAL, total_mi REAL,
                pickups INTEGER, dropoffs INTEGER, miles REAL, minutes REAL, net REAL, hourly REAL,
                verdict TEXT, decision TEXT, source TEXT)"""
        )
        db.execSQL("CREATE INDEX offers_ts ON offers(ts)")
        db.execSQL(
            """CREATE TABLE shifts(
                id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL,
                miles REAL NOT NULL, source TEXT NOT NULL, purpose TEXT, start_odo REAL, end_odo REAL,
                active INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX shifts_ts ON shifts(start_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- offers ----------

    fun insertOffer(o: Offer, s: Score, source: String, ts: Long = System.currentTimeMillis()): Long {
        val v = ContentValues().apply {
            put("ts", ts); put("type", o.type.name); put("fare", o.fare)
            put("pickup_min", o.pickupMin); put("pickup_mi", o.pickupMi)
            put("trip_min", o.tripMin); put("trip_mi", o.tripMi)
            put("total_min", o.totalMin); put("total_mi", o.totalMi)
            put("pickups", o.pickups); put("dropoffs", o.dropoffs)
            put("miles", s.miles); put("minutes", s.minutes); put("net", s.net); put("hourly", s.hourly)
            put("verdict", s.verdict.name); put("decision", Decision.SHOWN); put("source", source)
        }
        return writableDatabase.insert("offers", null, v)
    }

    fun setDecision(id: Long, decision: String) {
        writableDatabase.update("offers", ContentValues().apply { put("decision", decision) }, "id=?", arrayOf(id.toString()))
    }

    /** Only changes an offer still marked Shown, so a detected accept is never overwritten. */
    fun setDecisionIfShown(id: Long, decision: String) {
        writableDatabase.update("offers", ContentValues().apply { put("decision", decision) },
            "id=? AND decision=?", arrayOf(id.toString(), Decision.SHOWN))
    }

    fun offers(sinceTs: Long = 0L, limit: Int = 100000): List<OfferRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM offers WHERE ts>=? ORDER BY ts DESC LIMIT $limit", arrayOf(sinceTs.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toOffer()) } }

    fun deleteAllOffers() { writableDatabase.delete("offers", null, null) }

    private fun Cursor.toOffer() = OfferRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        ts = getLong(getColumnIndexOrThrow("ts")),
        type = runCatching { JobType.valueOf(getString(getColumnIndexOrThrow("type"))) }.getOrDefault(JobType.RIDE),
        fare = getDouble(getColumnIndexOrThrow("fare")),
        miles = getDouble(getColumnIndexOrThrow("miles")),
        minutes = getDouble(getColumnIndexOrThrow("minutes")),
        net = getDouble(getColumnIndexOrThrow("net")),
        hourly = getDouble(getColumnIndexOrThrow("hourly")),
        verdict = getString(getColumnIndexOrThrow("verdict")) ?: "",
        decision = getString(getColumnIndexOrThrow("decision")) ?: "",
        source = getString(getColumnIndexOrThrow("source")) ?: "",
        pickupMin = getDouble(getColumnIndexOrThrow("pickup_min")),
        pickupMi = getDouble(getColumnIndexOrThrow("pickup_mi")),
        tripMin = getDouble(getColumnIndexOrThrow("trip_min")),
        tripMi = getDouble(getColumnIndexOrThrow("trip_mi")),
        pickups = getInt(getColumnIndexOrThrow("pickups")),
        dropoffs = getInt(getColumnIndexOrThrow("dropoffs")),
    )

    // ---------- shifts (mileage log) ----------

    fun startShift(ts: Long, purpose: String): Long {
        val v = ContentValues().apply {
            put("start_ts", ts); put("end_ts", ts); put("miles", 0.0); put("source", "gps")
            put("purpose", purpose); put("active", 1)
        }
        return writableDatabase.insert("shifts", null, v)
    }

    fun updateShift(id: Long, endTs: Long, miles: Double, active: Boolean) {
        val v = ContentValues().apply { put("end_ts", endTs); put("miles", miles); put("active", if (active) 1 else 0) }
        writableDatabase.update("shifts", v, "id=?", arrayOf(id.toString()))
    }

    fun addManualShift(startTs: Long, endTs: Long, miles: Double, purpose: String, startOdo: Double?, endOdo: Double?): Long {
        val v = ContentValues().apply {
            put("start_ts", startTs); put("end_ts", endTs); put("miles", miles); put("source", "manual")
            put("purpose", purpose); put("active", 0)
            if (startOdo != null) put("start_odo", startOdo)
            if (endOdo != null) put("end_odo", endOdo)
        }
        return writableDatabase.insert("shifts", null, v)
    }

    fun shift(id: Long): ShiftRecord? =
        readableDatabase.rawQuery("SELECT * FROM shifts WHERE id=?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toShift() else null }

    fun shifts(fromTs: Long, toTs: Long): List<ShiftRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM shifts WHERE start_ts>=? AND start_ts<? ORDER BY start_ts DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toShift()) } }

    fun deleteShift(id: Long) { writableDatabase.delete("shifts", "id=?", arrayOf(id.toString())) }

    /** Marks any shift left "active" by a crash as finished, keeping the miles it saved. */
    fun closeStaleShifts(exceptId: Long) {
        writableDatabase.update("shifts", ContentValues().apply { put("active", 0) },
            "active=1 AND id<>?", arrayOf(exceptId.toString()))
    }

    private fun Cursor.toShift(): ShiftRecord {
        val so = getColumnIndexOrThrow("start_odo"); val eo = getColumnIndexOrThrow("end_odo")
        return ShiftRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            startTs = getLong(getColumnIndexOrThrow("start_ts")),
            endTs = getLong(getColumnIndexOrThrow("end_ts")),
            miles = getDouble(getColumnIndexOrThrow("miles")),
            source = getString(getColumnIndexOrThrow("source")) ?: "",
            purpose = getString(getColumnIndexOrThrow("purpose")) ?: "",
            startOdo = if (isNull(so)) null else getDouble(so),
            endOdo = if (isNull(eo)) null else getDouble(eo),
            active = getInt(getColumnIndexOrThrow("active")) == 1,
        )
    }
}
