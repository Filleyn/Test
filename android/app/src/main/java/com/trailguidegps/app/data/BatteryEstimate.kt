package com.trailguidegps.app.data

import android.content.Context
import android.os.BatteryManager

/** How long the phone's battery will last at the rate it has dropped since recording started. */
object BatteryEstimate {
    /** The battery level now, 1–100, or null when the phone won't say. */
    fun now(context: Context): Int? = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 1..100 }

    /**
     * "Battery 62% · about 9h left at this rate", from the level when recording started
     * ([startPct]), the level now and the time between. Needs 20 minutes and a 2% drop to say
     * anything about the rate; null when the level isn't known or it's charging.
     */
    fun line(startPct: Int?, nowPct: Int?, elapsedMs: Long): String? {
        if (nowPct == null) return null
        val base = "Battery $nowPct%"
        if (startPct == null || elapsedMs < 20 * 60_000L) return base
        val drop = startPct - nowPct
        if (drop < 0) return null
        if (drop < 2) return if (elapsedMs >= 60 * 60_000L) "$base · barely dropping" else base
        val hoursLeft = nowPct / (drop / (elapsedMs / 3_600_000.0))
        val left = if (hoursLeft >= 10) "${hoursLeft.toInt()}h" else Pace.format((hoursLeft * 60).toInt())
        return "$base · about $left left at this rate" + (if (hoursLeft < 3) ". Dim the screen or turn on battery saver." else "")
    }
}
