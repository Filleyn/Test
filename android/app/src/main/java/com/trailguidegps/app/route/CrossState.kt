package com.trailguidegps.app.route

import kotlin.math.floor

/**
 * Routes that cross a state line. Each downloaded state has its own trail network, and
 * near the border they overlap: a trail that leaves one state's data ends where it carries
 * on in the neighbour's. Those ends are the crossings. The route goes along the first
 * state's trails to the best crossing, then along the second state's trails from there.
 */
object CrossState {
    private class Crossing(val inA: Snap, val inB: Snap, val gapM: Double, val detourM: Double)

    /** Best route from [sa] on [ga]'s trails to [sb] on [gb]'s, or null if none is found. */
    fun route(ga: RouteGraph, sa: Snap, gb: RouteGraph, sb: Snap): RoutedLeg? {
        val found = crossings(ga, gb, sa, sb, gIsA = true) + crossings(gb, ga, sb, sa, gIsA = false)
        if (found.isEmpty()) return null
        // One crossing per ~300 m, the closest-fitting ones first, so a cluster of dead ends
        // at one road junction doesn't crowd out the trail crossing a little further on.
        val cands = found.groupBy { (floor(it.inA.lat / 0.003).toLong() shl 32) + floor(it.inA.lon / 0.003).toLong() }
            .values.map { group -> group.minBy { it.gapM } }
            .sortedBy { it.detourM }.take(80)
        val dA = ga.distances(sa, cands.map { it.inA })
        val dB = gb.distances(sb, cands.map { it.inB })
        val order = cands.indices.filter { dA[it].isFinite() && dB[it].isFinite() }
            .sortedBy { dA[it] + dB[it] + cands[it].gapM }
        for (i in order.take(3)) {
            val c = cands[i]
            val legA = ga.route(sa, c.inA) ?: continue
            val legB = gb.route(c.inB, sb) ?: continue
            return join(legA, legB, c.gapM)
        }
        return null
    }

    /** Dead ends of [g]'s network that sit on [other]'s network, within reach of this trip. */
    private fun crossings(g: RouteGraph, other: RouteGraph, from: Snap, to: Snap, gIsA: Boolean): List<Crossing> {
        val ob = other.bounds
        val straight = RouteGraph.distanceM(from.lat, from.lon, to.lat, to.lon)
        val limit = straight * 1.5 + 20_000
        val out = ArrayList<Crossing>()
        for (v in 0 until g.vertexCount) {
            if (g.degree(v) != 1) continue
            val lat = g.vertexLat(v)
            val lon = g.vertexLon(v)
            if (lat < ob[0] || lat > ob[2] || lon < ob[1] || lon > ob[3]) continue
            val detour = RouteGraph.distanceM(from.lat, from.lon, lat, lon) + RouteGraph.distanceM(lat, lon, to.lat, to.lon)
            if (detour > limit) continue
            val onOther = other.snap(lat, lon, 10.0) ?: continue
            val here = g.snapAtVertex(v) ?: continue
            out += if (gIsA) Crossing(here, onOther, onOther.distM, detour) else Crossing(onOther, here, onOther.distM, detour)
        }
        return out
    }

    private fun join(a: RoutedLeg, b: RoutedLeg, gapM: Double): RoutedLeg {
        val coords = ArrayList(a.coords)
        for (p in b.coords) {
            val last = coords.lastOrNull()
            if (last == null || last[0] != p[0] || last[1] != p[1]) coords += p
        }
        val names = ArrayList(a.names)
        for (n in b.names) if (names.lastOrNull() != n) names += n
        return RoutedLeg(
            coords, a.lengthM + gapM + b.lengthM, names,
            DoubleArray(3) { a.kindM[it] + b.kindM[it] }, maxOf(a.sacMax, b.sacMax), a.sacRatedM + b.sacRatedM,
        )
    }
}
