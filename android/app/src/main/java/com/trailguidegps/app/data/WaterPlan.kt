package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** A water source near a route: how far along it, and how far off it. Metres. */
data class WaterStop(val point: Features.Point, val alongM: Double, val offM: Double)

/** Water sources along a route, and the longest stretches without any. */
data class WaterPlan(
    val stops: List<WaterStop>,
    val totalM: Double,
    /** Longest stretch between water (from, to) along the route, counting every source. */
    val longestDry: Pair<Double, Double>?,
    /** The same, if seasonal sources (intermittent creeks, seasonal springs) are dry. */
    val longestDryIfSeasonalDry: Pair<Double, Double>?,
)

object WaterPlanner {
    /** Water within [maxOffM] of the route. Lakes count from their shore, roughly, not their middle. */
    fun plan(coords: List<DoubleArray>, points: List<Features.Point>, maxOffM: Double): WaterPlan {
        if (coords.size < 2) return WaterPlan(emptyList(), 0.0, null, null)
        val cum = DoubleArray(coords.size)
        for (i in 1 until coords.size) cum[i] = cum[i - 1] + Geo.distanceM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1])
        val total = cum.last()
        val pad = maxOffM / 111_000.0 + 0.02
        val minLat = coords.minOf { it[0] } - pad
        val maxLat = coords.maxOf { it[0] } + pad
        val minLon = coords.minOf { it[1] } - pad * 1.5
        val maxLon = coords.maxOf { it[1] } + pad * 1.5
        val kx = cos(Math.toRadians((minLat + maxLat) / 2)) * 111_320.0
        val ky = 110_540.0
        val stops = ArrayList<WaterStop>()
        for (p in points) {
            if (!(p.isWater || p.kind == "lake")) continue
            if (p.lat < minLat || p.lat > maxLat || p.lon < minLon || p.lon > maxLon) continue
            var best = Double.MAX_VALUE
            var along = 0.0
            for (i in 0 until coords.size - 1) {
                val ax = (coords[i][1] - p.lon) * kx
                val ay = (coords[i][0] - p.lat) * ky
                val dx = (coords[i + 1][1] - coords[i][1]) * kx
                val dy = (coords[i + 1][0] - coords[i][0]) * ky
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < best) { best = d; along = cum[i] + t * (cum[i + 1] - cum[i]) }
            }
            // A lake's point is its middle; take off its rough radius to get to the shore.
            val radius = p.acres?.let { sqrt(it * 4046.86 / PI) } ?: 0.0
            val off = (best - radius).coerceAtLeast(0.0)
            if (off <= maxOffM) stops += WaterStop(p, along, off)
        }
        stops.sortBy { it.alongM }
        fun longest(list: List<WaterStop>): Pair<Double, Double>? {
            val marks = listOf(0.0) + list.map { it.alongM } + listOf(total)
            return marks.zipWithNext().maxByOrNull { it.second - it.first }?.takeIf { it.second - it.first > 0 }
        }
        return WaterPlan(stops, total, longest(stops), longest(stops.filter { !it.point.seasonal }))
    }
}
