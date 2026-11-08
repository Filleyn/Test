package com.trailguidegps.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Planned trip dates on a route: defaults, the date of each day, and how they read. */
object TripDates {
    private const val DAY_MS = 86_400_000L

    /** Tomorrow at 8:00 AM, a sensible first guess. */
    fun defaultStart(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * When you'd finish if you start at [start]: for a trip, the last day's walking from the same
     * time of day on the last day; for a day hike, the estimate from the start.
     */
    fun defaultEnd(route: SavedRoute, start: Long, pace: Pace): Long {
        if (route.camps.isEmpty()) return start + pace.minutes(route.stats.distanceM, route.stats.gainM) * 60_000L
        val days = TripDays.days(route, route.camps, pace)
        return dayDate(start, days.size) + (days.lastOrNull()?.minutes ?: 0) * 60_000L
    }

    /** The same time of day as [start], on day [day] of the trip (day 1 is [start]). */
    fun dayDate(start: Long, day: Int): Long = Calendar.getInstance().apply {
        timeInMillis = start; add(Calendar.DAY_OF_MONTH, day - 1)
    }.timeInMillis

    /** How many calendar days [start]..[end] touch. */
    fun calendarDays(start: Long, end: Long): Int {
        fun midnight(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (((midnight(maxOf(end, start)) - midnight(start)) + DAY_MS / 2) / DAY_MS).toInt() + 1
    }

    private fun fmt(p: String, t: Long) = SimpleDateFormat(p, Locale.US).format(Date(t))

    /** "Sat, Jul 18 – Sun, Jul 19", or "Sat, Jul 18, 8:00 AM – 2:40 PM" for one day. */
    fun range(start: Long, end: Long): String =
        if (end <= start) fmt("EEE, MMM d, h:mm a", start)
        else if (calendarDays(start, end) == 1) fmt("EEE, MMM d, h:mm a", start) + " – " + fmt("h:mm a", end)
        else fmt("EEE, MMM d", start) + " – " + fmt("EEE, MMM d", end)

    /** "Jul 18", "Jul 18–19" or "Jul 30 – Aug 1", for lists. */
    fun short(start: Long, end: Long): String {
        if (end <= start || calendarDays(start, end) == 1) return fmt("EEE, MMM d", start)
        return if (fmt("MMM", start) == fmt("MMM", end)) fmt("MMM d", start) + "–" + fmt("d", end)
        else fmt("MMM d", start) + " – " + fmt("MMM d", end)
    }

    fun dayLabel(start: Long, day: Int): String = fmt("EEE, MMM d", dayDate(start, day))
    fun startLabel(t: Long): String = fmt("EEE, MMM d 'at' h:mm a", t)
}
