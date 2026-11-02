package com.trailguidegps.app.record

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Some phones (Samsung, Xiaomi, OnePlus and others) stop apps in the background to save
 * battery, even with a recording notification showing, which cuts tracks and route
 * alerts short. Pixels don't. This checks the setting and opens the right screen.
 */
object BatteryHelp {
    private val STRICT_MAKERS = setOf(
        "samsung", "xiaomi", "redmi", "poco", "oneplus", "oppo", "realme", "vivo", "huawei", "honor",
        "motorola", "asus", "nokia", "tecno", "infinix", "meizu", "lenovo", "sony",
    )

    /** True when Android may restrict the app in the background (battery use not "Unrestricted"). */
    fun restricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Phones known to stop background apps more than most. */
    val strictPhone: Boolean get() = Build.MANUFACTURER.lowercase() in STRICT_MAKERS

    /** Ask once, on phones where it matters, before the first recording or route following. */
    fun shouldAsk(context: Context): Boolean =
        strictPhone && restricted(context) && !context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("batteryAsked", false)

    fun markAsked(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).apply()

    /** The app's own settings page, where Battery → Unrestricted is. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    const val HOW_TO = "Tap Open settings, then Battery (or App battery usage), and choose Unrestricted."
}
