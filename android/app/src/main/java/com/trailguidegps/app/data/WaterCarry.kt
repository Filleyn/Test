package com.trailguidegps.app.data

import kotlin.math.ceil

/**
 * How much water to carry between sources: a planning estimate from mapped water,
 * your pace (which counts climbing) and how hot it'll be. Not a guarantee: mapped
 * water can be missing or dry.
 */
object WaterCarry {
    /** Drinking rate by the day's expected high. Common hiking guidance, rounded. */
    enum class Heat(val label: String, val short: String, val litersPerHour: Double) {
        COOL("Cool", "under 60°F", 0.4),
        MILD("Mild", "60–75°F", 0.5),
        WARM("Warm", "75–85°F", 0.75),
        HOT("Hot", "over 85°F", 1.0);

        companion object {
            fun of(tempF: Int) = when { tempF < 60 -> COOL; tempF < 75 -> MILD; tempF < 85 -> WARM; else -> HOT }
        }
    }

    /** Your own adjustment: some people drink a lot less or more. */
    enum class Rate(val label: String, val factor: Double) { LESS("Less", 0.75), NORMAL("Normal", 1.0), MORE("More", 1.3) }

    /** A night at a camp with no water: dinner, breakfast and drinking. */
    const val CAMP_L = 3.0
    /** Always a little extra. */
    const val EXTRA_L = 0.5

    /** A first guess when there's no forecast: from the month and how high the route goes. */
    fun guess(month: Int, highFt: Double): Heat {
        var level = when (month) { 12, 1, 2, 3 -> 0; 4, 5, 10, 11 -> 1; 6, 9 -> 2; else -> 3 }
        if (highFt > 7000) level -= 2 else if (highFt > 4000) level -= 1
        return Heat.entries[level.coerceIn(0, Heat.entries.size - 1)]
    }

    /** A stretch from one water source (or the trailhead) to the next (or the end). Metres, minutes, litres. */
    data class Gap(
        val fromM: Double, val toM: Double, val gainM: Double, val minutes: Int,
        val dryCamps: Int, val walkL: Double, val carryL: Double,
    ) {
        val lengthM get() = toM - fromM
    }

    /**
     * The stretches between water along a route. [sources] are distances along it with water;
     * [camps] are (distance along, has water). A camp with water counts as a source; a dry
     * camp adds a night's water to the stretch it's in, since you carry that in too.
     */
    fun gaps(stats: RouteStats, sources: List<Double>, camps: List<Pair<Double, Boolean>>, pace: Pace, heat: Heat, rate: Rate): List<Gap> {
        val total = stats.distanceM
        if (total <= 0) return emptyList()
        val marks = (listOf(0.0, total) + sources.filter { it > 0 && it < total } + camps.filter { it.second }.map { it.first })
            .distinct().sorted()
        val raw = TripDays.gainLoss(stats.profile, 0.0, total).first
        val scale = if (raw > 0) stats.gainM / raw else 0.0
        return marks.zipWithNext().filter { (a, b) -> b - a > 1 }.map { (a, b) ->
            val gain = TripDays.gainLoss(stats.profile, a, b).first * scale
            val minutes = pace.minutes(b - a, gain)
            val dry = camps.count { !it.second && it.first > a && it.first < b }
            val walk = minutes / 60.0 * heat.litersPerHour * rate.factor
            Gap(a, b, gain, minutes, dry, walk, roundUp(walk + dry * CAMP_L + EXTRA_L))
        }
    }

    /**
     * One day's water. [walk] is the longest stretch without water inside the day's own miles,
     * leaving out the part to or from a dry camp (that's in [campFill]). [campFill], when the day
     * ends at a camp with no water, is what to fill up with at the last water before camp: the
     * rest of the way in, the night, and the morning walk to the next water.
     */
    data class Day(val day: Int, val walk: Gap?, val campFill: Gap?)

    /**
     * Water day by day. [days] are each day's (from, to) along the route, in order; the camps sit
     * between them, with [campHasWater] saying which have water.
     */
    fun days(stats: RouteStats, sources: List<Double>, days: List<Pair<Double, Double>>, campHasWater: List<Boolean>,
             pace: Pace, heat: Heat, rate: Rate): List<Day> {
        val total = stats.distanceM
        if (total <= 0 || days.isEmpty()) return emptyList()
        val camps = days.dropLast(1).mapIndexed { i, d -> d.second to (campHasWater.getOrNull(i) ?: false) }
        val whole = gaps(stats, sources, camps, pace, heat, rate)
        val marks = (listOf(0.0, total) + sources.filter { it > 0 && it < total } + camps.filter { it.second }.map { it.first })
            .distinct().sorted()
        val raw = TripDays.gainLoss(stats.profile, 0.0, total).first
        val scale = if (raw > 0) stats.gainM / raw else 0.0
        fun gap(a: Double, b: Double): Gap {
            val gain = TripDays.gainLoss(stats.profile, a, b).first * scale
            val minutes = pace.minutes(b - a, gain)
            val walk = minutes / 60.0 * heat.litersPerHour * rate.factor
            return Gap(a, b, gain, minutes, 0, walk, roundUp(walk + EXTRA_L))
        }
        return days.mapIndexed { i, (from, to) ->
            val cuts = listOf(from) + marks.filter { it > from + 1 && it < to - 1 } + listOf(to)
            var pieces = cuts.zipWithNext()
            val startsDry = i > 0 && !camps[i - 1].second
            val endsDry = i < camps.size && !camps[i].second
            if (startsDry && pieces.isNotEmpty()) pieces = pieces.drop(1)   // carried in to last night's camp
            if (endsDry && pieces.isNotEmpty()) pieces = pieces.dropLast(1) // part of the fill-up for tonight
            val walk = pieces.filter { (a, b) -> b - a > 1 }.map { (a, b) -> gap(a, b) }.maxByOrNull { it.minutes }
            val fill = if (endsDry) whole.firstOrNull { it.fromM < to && it.toM > to } else null
            Day(i + 1, walk, fill)
        }
    }

    /** The stretch that takes the most water among those touching [fromM]..[toM] (one day). */
    fun worst(gaps: List<Gap>, fromM: Double = 0.0, toM: Double = Double.MAX_VALUE): Gap? =
        gaps.filter { it.toM > fromM + 1 && it.fromM < toM - 1 }.maxWithOrNull(compareBy({ it.carryL }, { it.lengthM }))

    /** Up to the next half litre. */
    fun roundUp(l: Double) = ceil(l * 2 - 1e-9) / 2

    fun liters(l: Double): String = if (l == Math.floor(l)) "%.0f L".format(l) else "%.1f L".format(l)
}
