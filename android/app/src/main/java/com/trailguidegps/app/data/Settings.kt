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
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_value.value)
        prefs.edit()
            .putBoolean("offRouteAlerts", s.offRouteAlerts).putInt("offRouteFt", s.offRouteFt)
            .putBoolean("keepScreenOn", s.keepScreenOn).putBoolean("checkUpdatesOnLaunch", s.checkUpdatesOnLaunch)
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
