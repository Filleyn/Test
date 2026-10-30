package com.trailguidegps.app.data

import kotlin.math.abs
import kotlin.math.max

/** A night's camp on a route: metres along it, and what's there. */
data class Camp(val alongM: Double, val name: String? = null)

/** A place on or near a route that would make a camp. */
data class CampSpot(
    val point: Features.Point,
    val alongM: Double,
    val offM: Double,
    /** Water near a campsite, and how far it is. Null for a water source itself. */
    val water: Pair<Features.Point, Double>?,
) {
    val isCampsite get() = point.kind == "campsite" || point.kind == "shelter"
    /** 0 campsite with water, 1 campsite, 2 water you could camp near, 3 seasonal water. */
    val tier get() = when {
        isCampsite && water != null -> 0
        isCampsite -> 1
        !point.seasonal -> 2
        else -> 3
    }
    val label: String get() = point.label + when {
        isCampsite && water != null -> " (campsite, water ${com.trailguidegps.app.geo.Geo.formatDistance(water.second)})"
        isCampsite -> " (campsite)"
        else -> " (water" + (if (point.seasonal) ", may be seasonal" else "") + ")"
    }
}

/** One day of a multi-day trip. Metres, minutes. */
data class TripDay(
    val day: Int,
    val fromM: Double,
    val toM: Double,
    val gainM: Double,
    val lossM: Double,
    val minutes: Int,
    /** Where the day ends: the camp's name, or null for the end of the route. */
    val endName: String?,
    val endsAtCamp: Boolean,
) {
    val distanceM get() = toM - fromM
}

/**
 * Splits a route into days. Camps are suggested so each day takes about the same time at
 * your pace, moved to a campsite (best with water near it) or a water source when one is close.
 */
object TripDays {
    /** How far off the route a campsite or water source can be and still count. */
    const val OFF_ROUTE_M = 400.0

    private fun elevAt(prof: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= prof.first().first) return prof.first().second
        for (i in 1 until prof.size) {
            val (d1, e1) = prof[i]
            if (x <= d1) {
                val (d0, e0) = prof[i - 1]
                return if (d1 == d0) e1 else e0 + (e1 - e0) * (x - d0) / (d1 - d0)
            }
        }
        return prof.last().second
    }

    /** Raw climb and descent between [a] and [b] along the profile. */
    private fun gainLoss(prof: List<Pair<Double, Double>>, a: Double, b: Double): Pair<Double, Double> {
        if (prof.size < 2 || b <= a) return 0.0 to 0.0
        val xs = listOf(a) + prof.map { it.first }.filter { it > a && it < b } + listOf(b)
        var g = 0.0
        var l = 0.0
        var prev = elevAt(prof, xs.first())
        for (x in xs.drop(1)) {
            val e = elevAt(prof, x)
            if (e > prev) g += e - prev else l += prev - e
            prev = e
        }
        return g to l
    }

    /** Day-by-day totals for [camps]. Climb is scaled so the days add up to the route's totals. */
    fun days(route: SavedRoute, camps: List<Camp>, pace: Pace): List<TripDay> {
        val total = route.stats.distanceM
        val prof = route.stats.profile
        val sorted = camps.filter { it.alongM > 0 && it.alongM < total }.sortedBy { it.alongM }
        val marks = listOf(0.0) + sorted.map { it.alongM } + listOf(total)
        val raw = marks.zipWithNext().map { (a, b) -> gainLoss(prof, a, b) }
        val rg = raw.sumOf { it.first }
        val rl = raw.sumOf { it.second }
        val gs = if (rg > 0) route.stats.gainM / rg else 0.0
        val ls = if (rl > 0) route.stats.lossM / rl else 0.0
        return marks.zipWithNext().mapIndexed { i, (a, b) ->
            val gain = raw[i].first * gs
            TripDay(i + 1, a, b, gain, raw[i].second * ls, pace.minutes(b - a, gain),
                sorted.getOrNull(i)?.name, i < sorted.size)
        }
    }

    /** Campsites and water sources along the route, in order. */
    fun spots(route: SavedRoute, features: Features): List<CampSpot> =
        RouteNear.project(route.coords, features.allPoints, OFF_ROUTE_M) {
            it.kind == "campsite" || it.kind == "shelter" || it.isWater || it.kind == "lake"
        }.map { (p, along, off) ->
            val camp = p.kind == "campsite" || p.kind == "shelter"
            CampSpot(p, along, off, if (camp) features.waterNear(p.lat, p.lon, Search.CAMP_WATER_M) else null)
        }

    /** Suggested camps for a trip of [days] days. */
    fun suggest(route: SavedRoute, days: Int, pace: Pace, spots: List<CampSpot>): List<Camp> {
        if (days <= 1 || route.stats.distanceM <= 0) return emptyList()
        val total = route.stats.distanceM
        // Time to reach each profile point at your pace.
        val prof = route.stats.profile.ifEmpty { listOf(0.0 to 0.0, total to 0.0) }
        val cum = DoubleArray(prof.size)
        for (i in 1 until prof.size) {
            val d = prof[i].first - prof[i - 1].first
            val g = max(0.0, prof[i].second - prof[i - 1].second)
            cum[i] = cum[i - 1] + pace.minutes(d * 1000, g * 1000) / 1000.0 // keep fractions
        }
        fun distanceAtTime(t: Double): Double {
            for (i in 1 until prof.size) if (cum[i] >= t) {
                val f = if (cum[i] == cum[i - 1]) 0.0 else (t - cum[i - 1]) / (cum[i] - cum[i - 1])
                return prof[i - 1].first + f * (prof[i].first - prof[i - 1].first)
            }
            return total
        }
        val camps = ArrayList<Camp>()
        var last = 0.0
        val dayLen = total / days
        for (k in 1 until days) {
            val target = distanceAtTime(cum.last() * k / days)
            val window = max(1600.0, dayLen * 0.15)
            val pick = spots.filter { abs(it.alongM - target) <= window && it.alongM > last + 1000 && it.alongM < total - 1000 }
                .minWithOrNull(compareBy({ it.tier }, { abs(it.alongM - target) }))
            val camp = pick?.let { Camp(it.alongM, it.point.label) } ?: Camp(target, null)
            camps += camp
            last = camp.alongM
        }
        return camps
    }

    /** Campsites and water within [rangeM] of a spot along the route, closest first. */
    fun nearby(spots: List<CampSpot>, alongM: Double, rangeM: Double = 5000.0): List<CampSpot> =
        spots.filter { abs(it.alongM - alongM) <= rangeM }.sortedWith(compareBy({ it.tier }, { abs(it.alongM - alongM) }))
}
