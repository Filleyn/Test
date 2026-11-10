package com.trailguidegps.app.route

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.trailguidegps.app.MainActivity
import com.trailguidegps.app.R
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.SettingsStore
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.hypot

/** Where you are on the route you're following. Distances in metres. */
data class Following(
    val route: SavedRoute,
    val alongM: Double = 0.0,
    val remainingM: Double = route.stats.distanceM,
    val climbLeftM: Double = route.stats.gainM,
    /** How far you are from the route line right now. */
    val offByM: Double? = null,
    val offRoute: Boolean = false,
    /** The nearest point on the route (lat, lon), to point you back to it. */
    val nearest: DoubleArray? = null,
    val arrived: Boolean = false,
    val lastFixAt: Long = 0,
    /** No usable GPS fix for a few minutes: the numbers are stale and off-route alerts can't fire. */
    val gpsQuiet: Boolean = false,
)

/**
 * Follows a saved route: works out how far along you are, what's left, and
 * whether you've wandered off it. Fed by the GPS service, so it keeps working
 * with the screen off. Alerts buzz the phone and post a notification.
 */
object RouteFollower {
    private val _state = MutableStateFlow<Following?>(null)
    val state: StateFlow<Following?> = _state.asStateFlow()

    private var cum = DoubleArray(0)      // distance along the line at each vertex
    private var climbAfter = DoubleArray(0) // climb from each profile sample to the end
    private var profileD = DoubleArray(0)
    private var lastSeg = -1
    private var offCount = 0
    private var lastAlertAt = 0L
    private const val CHANNEL = "route_alerts"
    private const val NOTIFY_ID = 11
    private const val REPEAT_MS = 5 * 60_000L
    private const val QUIET_MS = 3 * 60_000L
    private var lastGoodAt = 0L
    private var quietAlerted = false

    fun isFollowing() = _state.value != null

    fun start(route: SavedRoute) {
        val c = route.coords
        cum = DoubleArray(c.size)
        for (i in 1 until c.size) cum[i] = cum[i - 1] + Geo.distanceM(c[i - 1][0], c[i - 1][1], c[i][0], c[i][1])
        val p = route.stats.profile
        profileD = DoubleArray(p.size) { p[it].first }
        climbAfter = DoubleArray(p.size)
        for (i in p.size - 2 downTo 0) climbAfter[i] = climbAfter[i + 1] + (p[i + 1].second - p[i].second).coerceAtLeast(0.0)
        lastSeg = -1
        offCount = 0
        lastAlertAt = 0
        lastGoodAt = System.currentTimeMillis()
        quietAlerted = false
        _state.value = Following(route)
    }

    /**
     * Called every half minute by the GPS service. If no usable fix has come in for [QUIET_MS], says
     * so once (buzz and notification) and marks the state, because until GPS is back the numbers
     * are stale and an off-route alert can't happen.
     */
    fun checkQuiet(context: Context, now: Long = System.currentTimeMillis()) {
        val st = _state.value ?: return
        if (now - lastGoodAt < QUIET_MS) return
        if (!st.gpsQuiet) _state.value = st.copy(gpsQuiet = true)
        if (!quietAlerted) {
            quietAlerted = true
            if (SettingsStore.get(context).value.value.offRouteAlerts) {
                buzz(context, longArrayOf(0, 250, 150, 250))
                notify(context, "GPS signal lost", "No good GPS fix for ${(now - lastGoodAt) / 60_000} minutes while following ${st.route.name}. " +
                    "Off-route alerts are paused until it's back.", true)
            }
        }
    }

    fun stop(context: Context) {
        _state.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
    }

    /** Nearest point on segments [from, to): (segment, fraction, distance m). */
    private fun nearestOn(c: List<DoubleArray>, lat: Double, lon: Double, from: Int, to: Int, preferFrom: Int = from): Triple<Int, Double, Double> {
        val kx = cos(Math.toRadians(lat)) * 111_320.0
        val ky = 110_540.0
        var best = Triple(from, 0.0, Double.MAX_VALUE)
        for (i in from until to) {
            val ax = (c[i][1] - lon) * kx
            val ay = (c[i][0] - lat) * ky
            val dx = (c[i + 1][1] - c[i][1]) * kx
            val dy = (c[i + 1][0] - c[i][0]) * ky
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best.third) best = Triple(i, t, d)
        }
        // Where the line runs over itself (an out-and-back, a lollipop), several segments are about as
        // close. Take the first of those at or after where you were: it carries on from there, so you
        // stay on the way in until the turnaround and then move onto the way back.
        if (best.third < Double.MAX_VALUE) {
            for (i in preferFrom.coerceIn(from, to) until to) {
                val ax = (c[i][1] - lon) * kx
                val ay = (c[i][0] - lat) * ky
                val dx = (c[i + 1][1] - c[i][1]) * kx
                val dy = (c[i + 1][0] - c[i][0]) * ky
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d <= best.third + 12.0) return Triple(i, t, d)
            }
        }
        return best
    }

    fun update(context: Context, location: Location) {
        var st = _state.value ?: return
        if (location.hasAccuracy() && location.accuracy > 60f) return
        lastGoodAt = System.currentTimeMillis()
        if (st.gpsQuiet || quietAlerted) {
            if (quietAlerted && SettingsStore.get(context).value.value.offRouteAlerts) {
                notify(context, "GPS is back", "Following ${st.route.name} again.", false)
            }
            quietAlerted = false
            st = st.copy(gpsQuiet = false)
        }
        val c = st.route.coords
        if (c.size < 2) return
        val lat = location.latitude
        val lon = location.longitude
        val segs = c.size - 1
        // Search near where you were, mostly ahead, so an out-and-back or a loop that
        // crosses itself doesn't jump you to the other half. Fall back to the whole line.
        var hit = if (lastSeg < 0) nearestOn(c, lat, lon, 0, segs)
        else nearestOn(c, lat, lon, (lastSeg - 20).coerceAtLeast(0), (lastSeg + 400).coerceAtMost(segs), preferFrom = lastSeg)
        if (lastSeg >= 0 && hit.third > 150) {
            // Rejoining after wandering off: still prefer the part at or after where you were, so on an
            // out-and-back you come back onto the way back, not the way in.
            val all = nearestOn(c, lat, lon, 0, segs, preferFrom = lastSeg)
            if (all.third < hit.third - 50) hit = all
        }
        val (seg, t, dist) = hit
        lastSeg = seg
        val along = cum[seg] + t * (cum[seg + 1] - cum[seg])
        val total = cum.last()
        val remaining = (total - along).coerceAtLeast(0.0)
        // Climb left: from the profile sample at this distance to the end.
        val scale = if (total > 0 && profileD.isNotEmpty()) profileD.last() / total else 1.0
        val pi = profileD.indexOfFirst { it >= along * scale }.let { if (it < 0) profileD.size - 1 else it }
        val climbLeft = if (climbAfter.isEmpty() || pi < 0) 0.0 else climbAfter[pi]
        val nearest = doubleArrayOf(c[seg][0] + t * (c[seg + 1][0] - c[seg][0]), c[seg][1] + t * (c[seg + 1][1] - c[seg][1]))

        val settings = SettingsStore.get(context).value.value
        val threshold = settings.offRouteFt / Geo.FT_PER_M
        // Two fixes in a row past the line, so a single GPS jump doesn't set it off.
        offCount = if (dist > threshold) offCount + 1 else 0
        val wasOff = st.offRoute
        val off = when {
            offCount >= 2 -> true
            dist < threshold * 0.7 -> false
            else -> wasOff
        }
        val arrived = remaining < 40 && !off
        _state.value = st.copy(
            alongM = along, remainingM = remaining, climbLeftM = climbLeft, offByM = dist, offRoute = off,
            nearest = nearest, arrived = arrived || st.arrived, lastFixAt = location.time,
        )

        if (!settings.offRouteAlerts) return
        val now = System.currentTimeMillis()
        val dir = Geo.compassPoint(Geo.bearingDeg(lat, lon, nearest[0], nearest[1]))
        when {
            off && (!wasOff || now - lastAlertAt > REPEAT_MS) -> {
                lastAlertAt = now
                buzz(context, longArrayOf(0, 400, 200, 400, 200, 400))
                notify(context, "Off route", "${Geo.formatDistance(dist)} from ${st.route.name}. The route is to the $dir.", true)
            }
            !off && wasOff -> {
                buzz(context, longArrayOf(0, 150))
                notify(context, "Back on route", "${Geo.formatMiles(remaining)} to go on ${st.route.name}.", false)
            }
            arrived && !st.arrived -> {
                buzz(context, longArrayOf(0, 200, 150, 200))
                notify(context, "End of route", "You've reached the end of ${st.route.name}.", false)
            }
        }
    }

    private fun buzz(context: Context, pattern: LongArray) {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun notify(context: Context, title: String, text: String, urgent: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Route alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Off-route and end-of-route alerts while following a route"
            })
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true).setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }
}
