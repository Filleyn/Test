package com.trailguidegps.app.data

import android.content.Context
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Water along a route and how much to carry: your settings (drinking rate, heat per route, on or
 * off), which mapped water counts as reliable, and the day-by-day estimate for the Days screen
 * and the trip plan. Split out of the view model; it reads what it needs through [features],
 * [pace] and [savedWeather].
 */
class WaterTools(
    context: Context,
    private val features: () -> Features?,
    private val pace: () -> Pace,
    private val savedWeather: (String) -> List<Weather.Forecast>,
) {
    private val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)
    private val _rate = MutableStateFlow(runCatching { WaterCarry.Rate.valueOf(prefs.getString("rate", "NORMAL")!!) }.getOrDefault(WaterCarry.Rate.NORMAL))
    val rate: StateFlow<WaterCarry.Rate> = _rate.asStateFlow()
    fun setRate(r: WaterCarry.Rate) { _rate.value = r; prefs.edit().putString("rate", r.name).apply() }

    private val _hidden = MutableStateFlow(prefs.getBoolean("hidden", false))
    /** Water estimates can be turned off; they're then left off the Days screen and the trip plan. */
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()
    fun setHidden(v: Boolean) { _hidden.value = v; prefs.edit().putBoolean("hidden", v).apply() }

    /** Water sources within [maxOffM] of a line, in order along it. */
    fun plan(coords: List<DoubleArray>, maxOffM: Double): WaterPlan = WaterPlanner.plan(coords, features()?.allPoints.orEmpty(), maxOffM)

    /** The heat to plan for and why: your choice for this route, the saved forecast, or a guess from the month and elevation. */
    fun heat(route: SavedRoute): Pair<WaterCarry.Heat, String> {
        prefs.getString("heat_${route.id}", null)?.let { n ->
            runCatching { WaterCarry.Heat.valueOf(n) }.getOrNull()?.let { return it to "your choice" }
        }
        val fresh = savedWeather("route:${route.id}").filter { System.currentTimeMillis() - it.fetchedAt < 7 * DAY_MS }
        // Temperatures shifted for the route's height against the forecast grid's, as the Weather screen shows them.
        val periods = fresh.flatMap { f -> f.periods.filter { it.isDay && it.tempF != null }.map { p -> p.copy(tempF = p.tempF!! + (f.tempAdjustF ?: 0)) } }
        // With trip dates, only the forecast for those days counts (when it reaches that far).
        val forTrip = if (route.tripStart > 0) periods.filter { it.start > 0 && it.start >= route.tripStart - DAY_MS / 2 &&
            it.start <= maxOf(route.tripEnd, route.tripStart) + DAY_MS / 2 } else periods
        forTrip.mapNotNull { it.tempF }.maxOrNull()?.let { t ->
            return WaterCarry.Heat.of(t) to (if (route.tripStart > 0) "the forecast for your dates" else "saved forecast") + ", highs up to $t°F"
        }
        val month = java.util.Calendar.getInstance().apply { if (route.tripStart > 0) timeInMillis = route.tripStart }.get(java.util.Calendar.MONTH) + 1
        val highFt = (route.stats.profile.maxOfOrNull { it.second } ?: 0.0) * Geo.FT_PER_M
        val monthName = java.text.DateFormatSymbols.getInstance().months[month - 1]
        return WaterCarry.guess(month, highFt) to "a guess for $monthName up to %,d ft; open Weather for a forecast".format(highFt.toInt())
    }

    fun setHeat(routeId: String, heat: WaterCarry.Heat?) {
        prefs.edit().apply { if (heat == null) remove("heat_$routeId") else putString("heat_$routeId", heat.name) }.apply()
    }

    /**
     * Water day by day along [route] with [camps] (one day for a day hike). With [reliableOnly],
     * seasonal sources and small unnamed streams don't count, at camp or on the way.
     */
    fun days(route: SavedRoute, camps: List<Camp>, heat: WaterCarry.Heat, reliableOnly: Boolean): List<WaterCarry.Day> {
        val stops = plan(route.coords, 0.25 * Geo.M_PER_MI).stops.filter { !reliableOnly || !unreliable(it.point) }.map { it.alongM }
        val f = features()
        val days = TripDays.days(route, camps, pace())
        val campWater = days.dropLast(1).map { d ->
            val p = RouteStore.pointAt(route.coords, d.toM)
            val w = p?.let { f?.waterNear(it[0], it[1], Search.CAMP_WATER_M) }
            w != null && (!reliableOnly || !unreliable(w.first))
        }
        return WaterCarry.days(route.stats, stops, days.map { it.fromM to it.toM }, campWater, pace(), heat, _rate.value)
    }

    /** For the trip plan: a water line per day (or one for a day hike), or nothing when turned off. */
    fun forTripPlan(route: SavedRoute): Pair<Map<Int, String>, String?> {
        if (_hidden.value) return emptyMap<Int, String>() to null
        val (heat, _) = heat(route)
        val days = days(route, route.camps, heat, reliableOnly = true)
        val mi = Geo.M_PER_MI
        fun line(d: WaterCarry.Day): String? {
            val parts = listOfNotNull(
                d.walk?.let { g -> "Water: longest stretch without mapped water %.1f mi (miles %.1f–%.1f), carry about ${WaterCarry.liters(g.carryL)}"
                    .format(g.lengthM / mi, g.fromM / mi, g.toM / mi) },
                d.campFill?.let { g -> "No reliable water at camp: fill up with about ${WaterCarry.liters(g.carryL)} at mile %.1f".format(g.fromM / mi) +
                    " (to camp, the night and on to water at mile %.1f)".format(g.toM / mi) },
                d.total?.takeIf { t -> d.walk == null || t.lengthM > d.walk.lengthM + 1 }
                    ?.let { t -> "about ${WaterCarry.liters(t.carryL)} to drink over the whole day's walk" },
            )
            return if (parts.isEmpty()) null else parts.joinToString("; ") + " (${heat.label.lowercase()} weather)."
        }
        if (route.camps.isEmpty()) return emptyMap<Int, String>() to days.firstOrNull()?.let(::line)
        return days.mapNotNull { d -> line(d)?.let { d.day to it } }.toMap() to null
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        /**
         * Water not to count on when working out what to carry: anything marked seasonal, and small
         * unnamed stream crossings, which the map often doesn't mark either way and which are often
         * dry by late summer.
         */
        fun unreliable(p: Features.Point) = p.seasonal || (p.kind == "stream" && p.name.isNullOrBlank())
    }
}
