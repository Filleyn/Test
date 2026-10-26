package com.trailguidegps.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A GPS fix plus the details the app shows. Works with no cell signal. */
data class Fix(
    val location: Location,
    val satellitesUsed: Int,
    val satellitesVisible: Int,
) {
    val lat get() = location.latitude
    val lon get() = location.longitude
    val accuracyM: Float? get() = if (location.hasAccuracy()) location.accuracy else null

    /** Height above sea level where the phone can compute it, else GPS height. */
    val altitudeM: Double?
        get() = when {
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> null
        }

    val ageSeconds: Long get() = (System.currentTimeMillis() - location.time).coerceAtLeast(0) / 1000
}

/**
 * Reads the GPS receiver directly through the platform LocationManager, so it
 * needs neither Google Play services nor a network connection.
 */
class LocationTracker(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private var used = 0
    private var visible = 0
    private var running = false

    private val listener = LocationListener { loc -> onLocation(loc) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            visible = status.satelliteCount
            used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            _fix.value?.let { _fix.value = it.copy(satellitesUsed = used, satellitesVisible = visible) }
        }
    }

    private val appContext = context.applicationContext
    private val altitudeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun onLocation(loc: Location) {
        val current = _fix.value
        // Prefer a fresh GPS fix; accept a fused fix only if it is clearly better or newer.
        if (current != null && loc.provider != LocationManager.GPS_PROVIDER &&
            current.location.provider == LocationManager.GPS_PROVIDER &&
            loc.time - current.location.time < 10_000
        ) return
        _fix.value = Fix(loc, used, visible)
        // GPS reports height above the ellipsoid; Android 14+ can convert it to
        // height above sea level offline. It may read data, so do it off the main thread.
        if (Build.VERSION.SDK_INT >= 34 && loc.hasAltitude() && !loc.hasMslAltitude()) {
            val copy = Location(loc)
            altitudeExecutor.execute {
                val ok = runCatching {
                    android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(appContext, copy)
                }.isSuccess
                if (ok && _fix.value?.location?.time == copy.time) {
                    _fix.value = _fix.value?.copy(location = copy)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 1000L) {
        if (running) return
        running = true
        val looper = Looper.getMainLooper()
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) }
        }
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, looper)
        }
        if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            runCatching { lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, intervalMs, 0f, listener, looper) }
        }
        runCatching { lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(looper)) }
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
    }

    fun isGpsEnabled(): Boolean = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
}
