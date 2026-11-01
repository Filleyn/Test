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
        val total = RouteNear.lengthM(coords)
        val stops = RouteNear.project(coords, points, maxOffM) { it.isWater || it.kind == "lake" }
            .map { (p, along, off) -> WaterStop(p, along, off) }
        fun longest(list: List<WaterStop>): Pair<Double, Double>? {
            val marks = listOf(0.0) + list.map { it.alongM } + listOf(total)
            return marks.zipWithNext().maxByOrNull { it.second - it.first }?.takeIf { it.second - it.first > 0 }
        }
        return WaterPlan(stops, total, longest(stops), longest(stops.filter { !it.point.seasonal }))
    }
}

/** Places near a line, and how far along and off it they are; also simplifying a line. */
object RouteNear {
    /** Fewer points, keeping the shape to within [tolM] metres (Douglas-Peucker). */
    fun simplify(coords: List<DoubleArray>, tolM: Double): List<DoubleArray> {
        if (coords.size < 3) return coords
        val lat0 = coords[0][0]
        val kx = cos(Math.toRadians(lat0)) * 111_320.0
        val ky = 110_540.0
        val keep = BooleanArray(coords.size).also { it[0] = true; it[coords.size - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to coords.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = coords[a][1] * kx; val ay = coords[a][0] * ky
            val bx = coords[b][1] * kx; val by = coords[b][0] * ky
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            var worst = -1.0; var wi = -1
            for (i in a + 1 until b) {
                val px = coords[i][1] * kx; val py = coords[i][0] * ky
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (d > worst) { worst = d; wi = i }
            }
            if (worst > tolM && wi > 0) { keep[wi] = true; stack.add(a to wi); stack.add(wi to b) }
        }
        return coords.filterIndexed { i, _ -> keep[i] }
    }

    /** Points along a line about every [everyM] (at most [max]), first and last included, for editing it like a planned route. */
    fun planningPoints(coords: List<DoubleArray>, everyM: Double = 800.0, max: Int = 60): List<RoutePoint> {
        if (coords.size < 2) return coords.map { RoutePoint(it[0], it[1], true) }
        val total = lengthM(coords)
        val step = maxOf(everyM, total / max)
        val out = arrayListOf(RoutePoint(coords[0][0], coords[0][1], true))
        var since = 0.0
        for (i in 1 until coords.size - 1) {
            since += Geo.distanceM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1])
            if (since >= step) { out += RoutePoint(coords[i][0], coords[i][1], true); since = 0.0 }
        }
        out += RoutePoint(coords.last()[0], coords.last()[1], true)
        return out
    }

    fun lengthM(coords: List<DoubleArray>): Double {
        var t = 0.0
        for (i in 1 until coords.size) t += Geo.distanceM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1])
        return t
    }

    /** Points kept by [keep] within [maxOffM] of the line, as (point, metres along, metres off), in order along it. */
    fun project(
        coords: List<DoubleArray>, points: List<Features.Point>, maxOffM: Double, keep: (Features.Point) -> Boolean,
    ): List<Triple<Features.Point, Double, Double>> {
        if (coords.size < 2) return emptyList()
        val cum = DoubleArray(coords.size)
        for (i in 1 until coords.size) cum[i] = cum[i - 1] + Geo.distanceM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1])
        val pad = maxOffM / 111_000.0 + 0.02
        val minLat = coords.minOf { it[0] } - pad
        val maxLat = coords.maxOf { it[0] } + pad
        val minLon = coords.minOf { it[1] } - pad * 1.5
        val maxLon = coords.maxOf { it[1] } + pad * 1.5
        val kx = cos(Math.toRadians((minLat + maxLat) / 2)) * 111_320.0
        val ky = 110_540.0
        val out = ArrayList<Triple<Features.Point, Double, Double>>()
        for (p in points) {
            if (!keep(p)) continue
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
            if (off <= maxOffM) out += Triple(p, along, off)
        }
        out.sortBy { it.second }
        return out
    }
}
