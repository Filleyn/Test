package com.trailguidegps.app.route

import com.trailguidegps.app.geo.Geo

/**
 * A route trail by trail: where it starts, and each place it moves onto a differently named
 * trail or road, with the mile and which way to turn. Worked out by matching the route's line
 * to the trail network, so it also works for imported routes.
 */
object TurnSheet {
    data class Step(
        val atM: Double,
        /** "Start on", "Left onto", "Right onto", "Continue onto" or "Turn back onto". */
        val turn: String,
        val name: String,
        val lengthM: Double,
        val lat: Double,
        val lon: Double,
    ) {
        fun line(): String = "%.1f mi: ".format(atM / Geo.M_PER_MI) + "$turn $name" + " (%.1f mi)".format(lengthM / Geo.M_PER_MI)
    }

    /** A spot on the route: how far along, where, and what it's on there. */
    class Sample(val atM: Double, val lat: Double, val lon: Double, val name: String)

    const val OFF_TRAIL = "off trail (no mapped trail)"
    private const val STEP_M = 60.0
    /** A stretch shorter than this is a junction wobble or a short connector, not a turn worth listing. */
    private const val MIN_RUN_M = 150.0

    fun build(coords: List<DoubleArray>, graphs: List<RouteGraph>): List<Step> {
        if (coords.size < 2 || graphs.isEmpty()) return emptyList()
        val samples = ArrayList<Sample>()
        fun name(lat: Double, lon: Double): String {
            for (g in graphs) {
                val s = g.snap(lat, lon, 35.0) ?: continue
                return g.nameOf(s.edge)?.takeIf { it.isNotBlank() } ?: when (g.kindOf(s.edge)) { 1 -> "an unnamed track"; 2 -> "an unnamed road"; else -> "an unnamed trail" }
            }
            return OFF_TRAIL
        }
        var along = 0.0
        var next = 0.0
        for (i in 0 until coords.size - 1) {
            val a = coords[i]; val b = coords[i + 1]
            val d = Geo.distanceM(a[0], a[1], b[0], b[1])
            while (next <= along + d && d > 0) {
                val t = ((next - along) / d).coerceIn(0.0, 1.0)
                val la = a[0] + t * (b[0] - a[0]); val lo = a[1] + t * (b[1] - a[1])
                samples += Sample(next, la, lo, name(la, lo))
                next += STEP_M
            }
            along += d
        }
        return steps(samples, along)
    }

    /** The samples collapsed into stretches on one name, short wobbles folded into their neighbours. */
    fun steps(samples: List<Sample>, totalM: Double): List<Step> {
        if (samples.isEmpty()) return emptyList()
        // Runs of the same name: (index of first sample, name).
        val runs = ArrayList<Pair<Int, String>>()
        samples.forEachIndexed { i, s -> if (runs.isEmpty() || runs.last().second != s.name) runs += i to s.name }
        fun startM(r: Int) = samples[runs[r].first].atM
        fun endM(r: Int) = if (r + 1 < runs.size) startM(r + 1) else totalM
        // Drop short runs (never the first), then join neighbours that now share a name.
        var changed = true
        while (changed) {
            changed = false
            for (r in 1 until runs.size) {
                if (endM(r) - startM(r) < MIN_RUN_M) { runs.removeAt(r); changed = true; break }
            }
            for (r in runs.size - 1 downTo 1) if (runs[r].second == runs[r - 1].second) { runs.removeAt(r); changed = true }
        }
        return runs.indices.map { r ->
            val i = runs[r].first
            val s = samples[i]
            val turn = if (r == 0) "Start on" else {
                // The heading over the stretch just before the change against the stretch just after it.
                val last = samples.size - 1
                val b0 = samples[(i - 4).coerceAtLeast(0)]; val b1 = samples[(i - 1).coerceAtLeast(0)]
                val a0 = samples[(i + 1).coerceAtMost(last)]; val a1 = samples[(i + 4).coerceAtMost(last)]
                val inB = Geo.bearingDeg(b0.lat, b0.lon, b1.lat, b1.lon)
                val outB = Geo.bearingDeg(a0.lat, a0.lon, a1.lat, a1.lon)
                val diff = ((outB - inB + 540) % 360) - 180
                when {
                    i < 2 || i + 2 >= samples.size -> "Continue onto"
                    diff > 150 || diff < -150 -> "Turn back onto"
                    diff > 35 -> "Right onto"
                    diff < -35 -> "Left onto"
                    else -> "Continue onto"
                }
            }
            Step(s.atM, turn, runs[r].second, endM(r) - startM(r), s.lat, s.lon)
        }
    }
}
