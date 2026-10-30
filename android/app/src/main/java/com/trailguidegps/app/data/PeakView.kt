package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo
import kotlin.math.max

/**
 * Which named peaks can be seen from a spot, worked out offline from the terrain data:
 * a sight line to each peak within [PeakView.MAX_M] is checked against the ground in between,
 * allowing for the curve of the earth and the usual bending of light near the ground.
 *
 * The terrain is about 90 ft per pixel (300 ft for far samples) and has no trees, so
 * a peak that only just clears a ridge is marked as such.
 */
object PeakView {
    /** About 60 miles. */
    const val MAX_M = 97_000.0
    private const val EARTH_R = 6_371_000.0
    /** Standard refraction coefficient: light bends with the earth by about 1/7. */
    private const val REFRACTION = 0.13
    private const val EYE_M = 1.8

    data class Seen(
        val name: String,
        val lat: Double,
        val lon: Double,
        val eleM: Double,
        val distanceM: Double,
        val bearingDeg: Double,
        /** How far the summit clears the highest thing in front of it, in metres; negative is hidden. */
        val clearanceM: Double,
        /** Angle above (or below) level from your eye, in degrees. */
        val elevationAngleDeg: Double,
    ) {
        val visible get() = clearanceM >= 0
        /** Close enough that the 90 ft terrain could be wrong either way. */
        val marginal get() = clearanceM in -15.0..15.0
    }

    data class Result(val fromEleM: Double, val visible: List<Seen>, val hiddenCount: Int, val checked: Int)

    /** Drop of a distant point below level due to the earth's curve, less refraction. */
    private fun dropM(d: Double) = d * d / (2 * EARTH_R) * (1 - REFRACTION)

    /**
     * [elevation] gives metres at (lat, lon, zoom). [maxZoom] is the terrain's most detailed zoom;
     * sample points farther than about 8 miles use two zoom levels coarser.
     */
    fun compute(
        lat: Double, lon: Double, peaks: List<Features.Point>, maxZoom: Int,
        elevation: (Double, Double, Int) -> Double?,
    ): Result? {
        val ground = elevation(lat, lon, maxZoom) ?: return null
        val eye = ground + EYE_M
        val seen = ArrayList<Seen>()
        var hidden = 0
        var checked = 0
        for (p in peaks) {
            val d = Geo.distanceM(lat, lon, p.lat, p.lon)
            if (d < 200 || d > MAX_M) continue
            // The summit: the map's elevation, or the highest terrain right around the point.
            val top = p.eleFt?.let { it / Geo.FT_PER_M } ?: run {
                var best: Double? = null
                for (dy in -1..1) for (dx in -1..1) {
                    elevation(p.lat + dy * 0.0003, p.lon + dx * 0.0004, maxZoom)?.let { e -> if (best == null || e > best!!) best = e }
                }
                best
            } ?: continue
            checked++
            val target = (top - dropM(d) - eye) / d
            var maxAngle = Double.NEGATIVE_INFINITY
            var s = 30.0
            // Stop short of the summit itself, whose own slopes would block it.
            val end = d - max(90.0, d * 0.01)
            while (s < end) {
                val t = s / d
                val z = if (s < 13_000) maxZoom else maxZoom - 2
                val e = elevation(lat + (p.lat - lat) * t, lon + (p.lon - lon) * t, z)
                if (e != null) {
                    val a = (e - dropM(s) - eye) / s
                    if (a > maxAngle) maxAngle = a
                }
                s += max(30.0, s * 0.012)
            }
            val clearance = if (maxAngle == Double.NEGATIVE_INFINITY) 1000.0 else (target - maxAngle) * d
            if (clearance >= -15) {
                seen += Seen(p.name ?: p.label, p.lat, p.lon, top, d, Geo.bearingDeg(lat, lon, p.lat, p.lon),
                    clearance, Math.toDegrees(kotlin.math.atan(target)))
            } else hidden++
        }
        // Hidden ones that only just miss are listed too (marked), since the terrain is approximate.
        return Result(ground, seen.sortedBy { it.bearingDeg }, hidden, checked)
    }

    /** The visible peak nearest to [headingDeg], within 20°. */
    fun facing(list: List<Seen>, headingDeg: Float?): Seen? {
        val h = headingDeg?.toDouble() ?: return null
        return list.filter { it.visible || it.marginal }
            .map { it to Math.abs(((it.bearingDeg - h + 540) % 360) - 180) }
            .filter { it.second <= 20 }
            .minWithOrNull(compareBy({ it.second }, { it.first.distanceM }))?.first
    }
}
