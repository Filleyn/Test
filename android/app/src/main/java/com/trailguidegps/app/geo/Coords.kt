package com.trailguidegps.app.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Reads coordinates typed into search:
 * "44.0582, -121.3153", "44.0582 N 121.3153 W", "N44° 03.492' W121° 18.918'",
 * "44°3'29.5"N 121°18'55.1"W", "44 03 29.5 N 121 18 55.1 W", and UTM "10T 600000 4900000".
 * With no sign or N/S/E/W on the longitude, West is assumed (the maps are all U.S.).
 */
object Coords {
    data class Parsed(val lat: Double, val lon: Double, val assumedWest: Boolean = false, val fromUtm: Boolean = false)

    fun parse(input: String): Parsed? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("geo:").removePrefix("GEO:")
        utm(s)?.let { return it }
        // Only digits, signs, separators, degree/minute/second marks and N/S/E/W letters.
        s = s.replace('′', '\'').replace('’', '\'').replace('″', '"').replace('”', '"').replace("''", "\"")
        if (!Regex("""^[\d\s.,;:+\-°º'"NSEWnsew]+$""").matches(s)) return null

        class Group(val nums: MutableList<String> = mutableListOf(), var hemi: Char? = null)
        val groups = mutableListOf<Group>()
        var cur = Group()
        val hasLetters = s.any { it.uppercaseChar() in "NSEW" }
        val hasComma = ',' in s || ';' in s
        for (m in Regex("""[NSEWnsew]|[+\-]?\d+(?:\.\d+)?|[,;]""").findAll(s)) {
            val t = m.value
            when {
                t == "," || t == ";" -> if (!hasLetters && cur.nums.isNotEmpty()) { groups += cur; cur = Group() }
                t[0].isLetter() -> {
                    val h = t[0].uppercaseChar()
                    when {
                        cur.nums.isNotEmpty() && cur.hemi == null -> { cur.hemi = h; groups += cur; cur = Group() }
                        cur.nums.isNotEmpty() -> { groups += cur; cur = Group(hemi = h) }
                        else -> { if (cur.hemi != null) return null; cur.hemi = h }
                    }
                }
                else -> cur.nums += t
            }
        }
        if (cur.nums.isNotEmpty()) groups += cur else if (cur.hemi != null) return null

        // Plain numbers with no comma: split them evenly ("44 3 29 121 18 55").
        if (!hasLetters && !hasComma && groups.size == 1) {
            val n = groups[0].nums
            if (n.size % 2 != 0 || n.size > 6) return null
            groups.clear()
            groups += Group(n.subList(0, n.size / 2).toMutableList())
            groups += Group(n.subList(n.size / 2, n.size).toMutableList())
        }
        if (groups.size != 2 || groups.any { it.nums.isEmpty() || it.nums.size > 3 }) return null

        fun value(g: Group): Double? {
            val d = g.nums[0].toDoubleOrNull() ?: return null
            val m = g.nums.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val sec = g.nums.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            if (g.nums.size > 1 && (m < 0 || m >= 60 || g.nums[1].startsWith("-"))) return null
            if (g.nums.size > 2 && (sec < 0 || sec >= 60 || g.nums[2].startsWith("-"))) return null
            if (g.nums.size > 1 && d != Math.floor(d)) return null // "44.5 30" makes no sense
            var v = abs(d) + m / 60 + sec / 3600
            if (d < 0 || g.nums[0].startsWith("-")) v = -v
            if (g.hemi == 'S' || g.hemi == 'W') { if (v < 0) return null; v = -v }
            return v
        }
        var (a, b) = groups
        // Letters decide which is which; "W 121 N 44" works too.
        if (a.hemi in setOf('E', 'W') && b.hemi in setOf('N', 'S', null)) { val t = a; a = b; b = t }
        if (a.hemi in setOf('E', 'W') || b.hemi in setOf('N', 'S')) return null
        var lat = value(a) ?: return null
        var lon = value(b) ?: return null
        // No letters and the first number can't be a latitude: typed longitude first.
        if (!hasLetters && abs(lat) > 90 && abs(lon) <= 90) { val t = lat; lat = lon; lon = t }
        var assumedWest = false
        if (b.hemi == null && lon > 0 && !b.nums[0].startsWith("+")) { lon = -lon; assumedWest = true }
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return Parsed(lat, lon, assumedWest)
    }

    /** "10T 600000 4900000", "10 T 600000E 4900000N", "10N 600000 4900000" (N = northern hemisphere). */
    private fun utm(s: String): Parsed? {
        val m = Regex("""^(\d{1,2})\s*([C-HJ-NP-Xc-hj-np-x])?\s+(\d{5,7}(?:\.\d+)?)\s*(?:m\s*)?E?\s*[, ]\s*(\d{6,8}(?:\.\d+)?)\s*(?:m\s*)?N?$""",
            RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2].uppercase().firstOrNull()
        val east = m.groupValues[3].toDouble()
        val north = m.groupValues[4].toDouble()
        if (east < 100_000 || east > 900_000) return null
        val southern = band != null && band < 'N'
        val (lat, lon) = fromUtm(zone, southern, east, north) ?: return null
        return Parsed(lat, lon, fromUtm = true)
    }

    /** UTM to WGS84 (inverse of [Geo.utm]). */
    fun fromUtm(zone: Int, southern: Boolean, easting: Double, northing: Double): Pair<Double, Double>? {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val x = easting - 500000.0
        val y = if (southern) northing - 10000000.0 else northing
        val mu = y / k0 / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = a / sqrt(1 - e2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = ep2 * cos(phi1).pow(2)
        val r1 = a * (1 - e2) / (1 - e2 * sin(phi1).pow(2)).pow(1.5)
        val d = x / (n1 * k0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val lon = lon0 + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(phi1)
        val la = Math.toDegrees(lat)
        val lo = Math.toDegrees(lon)
        if (la.isNaN() || lo.isNaN() || abs(la) > 84.5) return null
        return la to lo
    }
}
