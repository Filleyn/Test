package com.offerscorer.data

import android.content.Context
import android.content.SharedPreferences
import com.offerscorer.core.ScoreSettings

/** All user settings, stored in the app's private SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun d(key: String, def: Double) = sp.getString(key, null)?.toDoubleOrNull() ?: def
    private fun putD(key: String, v: Double) = sp.edit().putString(key, v.toString()).apply()

    var score: ScoreSettings
        get() {
            val def = ScoreSettings()
            return ScoreSettings(
                gasPrice = d("gas", def.gasPrice),
                mpg = d("mpg", def.mpg),
                maintPerMile = d("maint", def.maintPerMile),
                deprPerMile = d("depr", def.deprPerMile),
                rideTargetHourly = d("tRide", def.rideTargetHourly),
                deliveryTargetHourly = d("tDel", def.deliveryTargetHourly),
                maxPickupMin = d("maxP", def.maxPickupMin),
                yellowBandPct = d("band", def.yellowBandPct),
                restaurantWaitMin = d("wait", def.restaurantWaitMin),
                extraStopMin = d("stop", def.extraStopMin),
            )
        }
        set(s) {
            sp.edit()
                .putString("gas", s.gasPrice.toString()).putString("mpg", s.mpg.toString())
                .putString("maint", s.maintPerMile.toString()).putString("depr", s.deprPerMile.toString())
                .putString("tRide", s.rideTargetHourly.toString()).putString("tDel", s.deliveryTargetHourly.toString())
                .putString("maxP", s.maxPickupMin.toString()).putString("band", s.yellowBandPct.toString())
                .putString("wait", s.restaurantWaitMin.toString()).putString("stop", s.extraStopMin.toString())
                .apply()
        }

    /** Rate used for dates the built-in IRS table doesn't cover. */
    var irsFallbackRate: Double
        get() = d("irsRate", 0.76)
        set(v) = putD("irsRate", v)

    var defaultPurpose: String
        get() = sp.getString("purpose", null) ?: "Rideshare/delivery driving (online with Uber)"
        set(v) = sp.edit().putString("purpose", v).apply()

    var rulesUrl: String
        get() = sp.getString("rulesUrl", "") ?: ""
        set(v) = sp.edit().putString("rulesUrl", v.trim()).apply()

    /** Save every distinct Uber screen until this time (epoch ms), for tuning the reader. */
    var captureUntil: Long
        get() = sp.getLong("captureUntil", 0L)
        set(v) = sp.edit().putLong("captureUntil", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    /** Id of the GPS shift currently being recorded, or -1. */
    var activeShiftId: Long
        get() = sp.getLong("activeShift", -1L)
        set(v) = sp.edit().putLong("activeShift", v).apply()

    fun resetScore() { score = ScoreSettings() }
}
