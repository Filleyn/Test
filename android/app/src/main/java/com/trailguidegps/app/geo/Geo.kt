package com.trailguidegps.app.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Distance, direction and coordinate formatting. Distances are miles and feet. */
object Geo {
    const val FT_PER_M = 3.28084
    const val M_PER_MI = 1609.344
    private const val EARTH_R = 6371008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(a))
    }

    /** Initial bearing in degrees, 0 = north, clockwise. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun compassPoint(deg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((deg + 22.5) / 45).toInt() % 8]
    }

    /** "450 ft" under a tenth of a mile, otherwise "0.3 mi" or "12 mi". */
    fun formatDistance(meters: Double): String {
        val miles = meters / M_PER_MI
        return when {
            miles < 0.1 -> "${roundTo(meters * FT_PER_M, 10)} ft"
            miles < 10 -> String.format("%.1f mi", miles)
            else -> "${miles.roundToInt()} mi"
        }
    }

    fun formatFeet(meters: Double): String = "%,d ft".format((meters * FT_PER_M).roundToInt())

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step)

    fun decimal(lat: Double, lon: Double): Pair<String, String> {
        val la = String.format("%.4f° %s", abs(lat), if (lat >= 0) "N" else "S")
        val lo = String.format("%.4f° %s", abs(lon), if (lon >= 0) "E" else "W")
        return la to lo
    }

    fun dms(lat: Double, lon: Double): String {
        fun part(v: Double, pos: String, neg: String): String {
            val a = abs(v)
            var d = floor(a).toInt()
            val mFull = (a - d) * 60
            var m = floor(mFull).toInt()
            var s = ((mFull - m) * 60).roundToInt()
            if (s == 60) { s = 0; m += 1 }
            if (m == 60) { m = 0; d += 1 }
            return "$d° $m′ $s″ ${if (v >= 0) pos else neg}"
        }
        return "${part(lat, "N", "S")}, ${part(lon, "E", "W")}"
    }

    data class Utm(val zone: Int, val band: Char, val easting: Int, val northing: Int) {
        val line1 get() = "$zone$band $easting E"
        val line2 get() = "$northing N"
    }

    /** WGS84 to UTM (standard transverse Mercator series). */
    fun utm(lat: Double, lon: Double): Utm {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val zone = floor((lon + 180) / 6).toInt() + 1
        val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val phi = Math.toRadians(lat)
        val n = a / sqrt(1 - e2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = ep2 * cos(phi).pow(2)
        val aa = cos(phi) * (Math.toRadians(lon) - lon0)
        val m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256) * phi -
            (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2.pow(3) / 1024) * sin(2 * phi) +
            (15 * e2 * e2 / 256 + 45 * e2.pow(3) / 1024) * sin(4 * phi) -
            (35 * e2.pow(3) / 3072) * sin(6 * phi))
        val x = k0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120) + 500000.0
        var y = k0 * (m + n * tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720))
        if (lat < 0) y += 10000000.0
        val bands = "CDEFGHJKLMNPQRSTUVWX"
        val bandIndex = ((lat + 80) / 8).toInt().coerceIn(0, bands.length - 1)
        return Utm(zone, bands[bandIndex], x.roundToInt(), y.roundToInt())
    }

    /** Distance in metres from a point to a polyline, using a local flat projection. */
    fun distanceToLineM(lat: Double, lon: Double, line: List<DoubleArray>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        val kx = cos(Math.toRadians(lat)) * 111320.0
        val ky = 110540.0
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = (line[i][0] - lon) * kx
            val ay = (line[i][1] - lat) * ky
            val bx = (line[i + 1][0] - lon) * kx
            val by = (line[i + 1][1] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        if (line.size == 1) {
            best = distanceM(lat, lon, line[0][1], line[0][0])
        }
        return best
    }
}
