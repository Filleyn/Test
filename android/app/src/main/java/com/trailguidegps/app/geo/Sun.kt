package com.trailguidegps.app.geo

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin


/**
 * Sunrise, sunset and the end of usable light (civil dusk), worked out offline
 * with the NOAA sunrise equation. Accurate to a minute or two, which is plenty
 * for "will we be off the trail before dark". Times are epoch milliseconds.
 */
object Sun {
    data class Day(val sunrise: Long?, val sunset: Long?, val dusk: Long?)

    /** Sun times for the calendar day (in the phone's time zone) that contains [atMs]. */
    fun day(lat: Double, lon: Double, atMs: Long, zone: TimeZone = TimeZone.getDefault()): Day {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        // Julian day at local noon of that date.
        val noonUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
        }.timeInMillis
        val jd = noonUtc / 86_400_000.0 + 2440587.5
        val n = floor(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372) % 360)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.44)))
        val phi = Math.toRadians(lat)

        fun at(altitudeDeg: Double): Pair<Long?, Long?> {
            val cosW = (sin(Math.toRadians(altitudeDeg)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
            if (cosW < -1 || cosW > 1) return null to null // sun never rises or never sets that far
            val w = Math.toDegrees(acos(cosW))
            fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
            return ms(jTransit - w / 360) to ms(jTransit + w / 360)
        }
        val (rise, set) = at(-0.833)
        val (_, dusk) = at(-6.0)
        return Day(rise, set, dusk)
    }

    /** Minutes of daylight left from [nowMs], or 0 after sunset. */
    fun daylightLeftMin(lat: Double, lon: Double, nowMs: Long): Long? =
        day(lat, lon, nowMs).sunset?.let { ((it - nowMs) / 60_000).coerceAtLeast(0) }

}
