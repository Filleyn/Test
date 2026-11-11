package com.trailguidegps.app.route

import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.RouteStats
import com.trailguidegps.app.data.Terrain
import com.trailguidegps.app.geo.Geo

/** One way off the trail: a trailhead or the nearest road, with the walk to it. */
class WayOut(
    val name: String,
    /** True for the nearest road rather than a mapped trailhead. */
    val road: Boolean,
    val coords: List<DoubleArray>,
    val leg: RoutedLeg,
    val stats: RouteStats,
    val minutes: Int,
) {
    val endLat get() = coords.last()[0]
    val endLon get() = coords.last()[1]
}

/**
 * The quickest ways out from a spot: the nearest trailheads and the nearest road, measured
 * along trails (not in a straight line) and ordered by walking time at your pace.
 */
object WaysOut {
    class Result(val ways: List<WayOut>, val note: String?)

    /** Trailheads further than this in a straight line aren't considered. */
    private val MAX_STRAIGHT_M = 25 * Geo.M_PER_MI
    /** Off-trail by more than this, there's no trail to follow out. */
    private const val MAX_OFF_TRAIL_M = 800.0
    private const val SAME_PLACE_M = 300.0
    private const val SEARCH_LIMIT = 600_000

    fun find(graphs: List<RouteGraph>, features: Features?, terrain: Terrain?, pace: Pace, lat: Double, lon: Double, count: Int = 4): Result {
        val heads = features?.allPoints.orEmpty().asSequence().filter { it.kind == "trailhead" }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }.filter { it.second < MAX_STRAIGHT_M }
            .sortedBy { it.second }.take(40).toList()
        val found = ArrayList<WayOut>()
        var onNetwork = false
        var onRoad: String? = null
        for (g in graphs) {
            val a = g.snap(lat, lon, MAX_OFF_TRAIL_M) ?: continue
            onNetwork = true
            fun add(name: String, road: Boolean, b: Snap) {
                val leg = runCatching { g.route(a, b, SEARCH_LIMIT) }.getOrNull() ?: return
                // From the spot itself, when it's a little way off the trail.
                val coords = if (a.distM > 15) listOf(doubleArrayOf(lat, lon)) + leg.coords else leg.coords
                if (coords.size < 2) return
                val len = leg.lengthM + (if (a.distM > 15) a.distM else 0.0)
                val stats = RouteStats.compute(coords, len, terrain)
                found += WayOut(name, road, coords, leg, stats, pace.minutes(len, stats.gainM))
            }
            val snaps = heads.mapNotNull { (p, _) -> g.snap(p.lat, p.lon, 250.0)?.let { p to it } }
            if (snaps.isNotEmpty()) {
                val d = runCatching { g.distances(a, snaps.map { it.second }, SEARCH_LIMIT) }.getOrNull()
                if (d != null) snaps.indices.filter { d[it].isFinite() }.sortedBy { d[it] }.take(count + 2)
                    .forEach { i -> add(snaps[i].first.name?.takeIf { it.isNotBlank() } ?: "Trailhead", false, snaps[i].second) }
            }
            if (g.kindOf(a.edge) == 2) onRoad = g.nameOf(a.edge) ?: "a road"
            else runCatching { g.nearestOfKind(a, 2, maxSettled = SEARCH_LIMIT) }.getOrNull()?.let { (s, name) -> add(name ?: "Nearest road", true, s) }
        }
        // Fastest first; a second way out that ends at the same place as a faster one adds nothing.
        val ways = ArrayList<WayOut>()
        for (w in found.sortedBy { it.minutes }) {
            if (ways.none { Geo.distanceM(it.endLat, it.endLon, w.endLat, w.endLon) < SAME_PLACE_M }) ways += w
            if (ways.size == count) break
        }
        val note = when {
            !onNetwork -> {
                val near = heads.firstOrNull()
                "There's no mapped trail within half a mile of this spot, so there's no way out to follow from here." +
                    (near?.let { (p, d) -> " The nearest trailhead in a straight line is ${p.name ?: "unnamed"}, ${Geo.formatDistance(d)} " +
                        "${Geo.compassPoint(Geo.bearingDeg(lat, lon, p.lat, p.lon))}." } ?: "")
            }
            onRoad != null -> "This spot is on $onRoad."
            ways.isEmpty() -> "No trailhead or road was found within reach by trail from here."
            else -> null
        }
        return Result(ways, note)
    }
}
