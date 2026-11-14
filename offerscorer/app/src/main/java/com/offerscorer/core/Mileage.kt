package com.offerscorer.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

const val METERS_PER_MILE = 1609.344

data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

object Geo {
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * Adds up driven distance from GPS fixes while ignoring the usual GPS noise:
 * low-accuracy fixes, jitter while parked, and impossible jumps.
 */
class DistanceAccumulator(
    startMeters: Double = 0.0,
    private val maxAccuracyM: Float = 35f,
    private val minStepM: Double = 10.0,
    private val maxSpeedMps: Double = 55.0, // about 123 mph
) {
    var meters: Double = startMeters
        private set
    private var last: Fix? = null
    private var rejectedJumps = 0

    val miles: Double get() = meters / METERS_PER_MILE

    /** Returns true if the fix added distance. */
    fun add(fix: Fix): Boolean {
        if (fix.accuracyM > maxAccuracyM) return false
        val prev = last
        if (prev == null) { last = fix; return false }
        val dt = (fix.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0) return false
        val d = Geo.distanceMeters(prev.lat, prev.lon, fix.lat, fix.lon)
        if (d / dt > maxSpeedMps) {
            // A jump. After several in a row, trust the new position without counting the gap.
            if (++rejectedJumps >= 3) { last = fix; rejectedJumps = 0 }
            return false
        }
        rejectedJumps = 0
        val noise = max(minStepM, (prev.accuracyM + fix.accuracyM) / 2.0)
        if (d < noise) return false
        meters += d
        last = fix
        return true
    }
}

/** IRS business standard mileage rates. Update when the IRS announces a new rate. */
object IrsRates {
    private data class Period(val from: LocalDate, val to: LocalDate, val rate: Double)

    private val table = listOf(
        Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 0.70),
        Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 0.725), // Notice 2026-10
        Period(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 0.76), // IRB 2026-29
    )

    fun rateFor(epochMs: Long, fallback: Double, zone: ZoneId = ZoneId.systemDefault()): Double {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return table.firstOrNull { !d.isBefore(it.from) && !d.isAfter(it.to) }?.rate ?: fallback
    }
}

object Csv {
    fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    fun row(vararg v: Any?): String = v.joinToString(",") { cell(it) }
}
