package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings, kept between launches. Pace has its own store (see [Pace]). */
data class Settings(
    /** Buzz and notify when you stray from a route you're following. */
    val offRouteAlerts: Boolean = true,
    /** How far off the route counts as off route, in feet. */
    val offRouteFt: Int = 250,
    /** Keep the screen on while following a route. */
    val keepScreenOn: Boolean = false,
    /** Look for new map files when the app opens (needs a connection; does nothing offline). */
    val checkUpdatesOnLaunch: Boolean = true,
    /** Fewer GPS fixes while recording or following (every 15 s instead of 5 s). */
    val batterySaver: Boolean = false,
    /** Warn at 20% and 10% battery while recording or following. */
    val lowBatteryWarning: Boolean = true,
    /** Switch to battery saver by itself at 20%. */
    val autoSaverAtLow: Boolean = true,
    /** "light", "dark" or "system" (follow the phone). */
    val look: String = "light",
    /** High contrast and bigger text for bright sunlight. */
    val sunlight: Boolean = false,
    /** The first-run tips have been shown. */
    val introSeen: Boolean = false,
    /** Show states you haven't downloaded on the map while online. */
    val onlineStates: Boolean = true,
) {
    companion object {
        val OFF_ROUTE_CHOICES = listOf(100, 250, 500, 1000)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _value = MutableStateFlow(load())
    val value: StateFlow<Settings> = _value.asStateFlow()

    private fun load() = Settings(
        offRouteAlerts = prefs.getBoolean("offRouteAlerts", true),
        offRouteFt = prefs.getInt("offRouteFt", 250),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
        checkUpdatesOnLaunch = prefs.getBoolean("checkUpdatesOnLaunch", true),
        batterySaver = prefs.getBoolean("batterySaver", false),
        lowBatteryWarning = prefs.getBoolean("lowBatteryWarning", true),
        autoSaverAtLow = prefs.getBoolean("autoSaverAtLow", true),
        look = prefs.getString("look", "light") ?: "light",
        sunlight = prefs.getBoolean("sunlight", false),
        introSeen = prefs.getBoolean("introSeen", false),
        onlineStates = prefs.getBoolean("onlineStates2", true),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_value.value)
        prefs.edit()
            .putBoolean("offRouteAlerts", s.offRouteAlerts).putInt("offRouteFt", s.offRouteFt)
            .putBoolean("keepScreenOn", s.keepScreenOn).putBoolean("checkUpdatesOnLaunch", s.checkUpdatesOnLaunch)
            .putBoolean("batterySaver", s.batterySaver).putBoolean("lowBatteryWarning", s.lowBatteryWarning)
            .putBoolean("autoSaverAtLow", s.autoSaverAtLow)
            .putString("look", s.look).putBoolean("sunlight", s.sunlight).putBoolean("introSeen", s.introSeen)
            .putBoolean("onlineStates2", s.onlineStates)
            .apply()
        _value.value = s
    }

    companion object {
        @Volatile private var instance: SettingsStore? = null
        /** One store for the app and its background service. */
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context.applicationContext).also { instance = it } }
    }
}
