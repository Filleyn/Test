package com.offerscorer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.offerscorer.MainActivity
import com.offerscorer.core.DistanceAccumulator
import com.offerscorer.core.Fix
import com.offerscorer.core.METERS_PER_MILE
import com.offerscorer.data.Db
import com.offerscorer.data.Prefs

/**
 * Records driven miles from GPS while a shift is running. Runs as a foreground
 * service with a notification, so Android keeps it alive while the Uber app is
 * in front. Saves progress every 30 seconds so a crash loses almost nothing.
 */
class MileageService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.offerscorer.START_SHIFT"
        const val ACTION_STOP = "com.offerscorer.STOP_SHIFT"
        private const val CHANNEL = "shift"
        private const val NOTIF_ID = 42

        @Volatile var running = false
            private set
        @Volatile var liveMiles = 0.0
            private set
        @Volatile var startedAt = 0L
            private set
        /** True while a shift is running but the phone's location is switched off. */
        @Volatile var locationOff = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MileageService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MileageService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private var lm: LocationManager? = null
    private var acc = DistanceAccumulator()
    private var shiftId = -1L
    private var lastSave = 0L
    private var lastNotifiedTenth = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Db.get(this)
        prefs = Prefs(this)
        lm = getSystemService(LocationManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shift mileage", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finishShift(); return START_NOT_STICKY }
            else -> startOrResume() // ACTION_START, or a restart by the system (null intent)
        }
        return START_STICKY
    }

    private fun startOrResume() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val now = System.currentTimeMillis()
        val existing = prefs.activeShiftId.takeIf { it >= 0 }?.let { db.shift(it) }?.takeIf { it.active }
        if (existing != null) {
            shiftId = existing.id
            startedAt = existing.startTs
            acc = DistanceAccumulator(startMeters = existing.miles * METERS_PER_MILE)
        } else {
            shiftId = db.startShift(now, prefs.defaultPurpose)
            startedAt = now
            acc = DistanceAccumulator()
            prefs.activeShiftId = shiftId
        }
        db.closeStaleShifts(shiftId)
        liveMiles = acc.miles
        locationOff = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification())
            }
        } catch (e: Exception) {
            // Android can refuse a location service restarted in the background. The shift stays
            // open with its saved miles; the app shows it as paused until Resume is tapped.
            shiftId = -1
            stopSelf()
            return
        }
        running = true
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            finishShift()
        }
    }

    private fun finishShift() {
        runCatching { lm?.removeUpdates(this) }
        if (shiftId >= 0) {
            db.updateShift(shiftId, System.currentTimeMillis(), acc.miles, active = false)
        } else {
            // Ending a paused shift: close it with the miles it last saved.
            prefs.activeShiftId.takeIf { it >= 0 }?.let { db.shift(it) }?.let {
                db.updateShift(it.id, maxOf(it.endTs, it.startTs), it.miles, active = false)
            }
        }
        prefs.activeShiftId = -1
        shiftId = -1
        running = false
        locationOff = false
        liveMiles = 0.0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        acc.add(Fix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else 99f, location.time))
        liveMiles = acc.miles
        val now = System.currentTimeMillis()
        if (now - lastSave > 30_000 && shiftId >= 0) {
            db.updateShift(shiftId, now, acc.miles, active = true)
            lastSave = now
        }
        val tenth = (acc.miles * 10).toInt()
        if (tenth != lastNotifiedTenth) {
            lastNotifiedTenth = tenth
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    @Deprecated("Required by older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) = setLocationOff(false)
    override fun onProviderDisabled(provider: String) = setLocationOff(true)

    private fun setLocationOff(off: Boolean) {
        locationOff = off
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MileageService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (locationOff) "Location is off" else "Shift running")
            .setContentText(
                if (locationOff) "Miles aren't recording. Turn on Location."
                else String.format(java.util.Locale.US, "%.1f mi recorded", acc.miles)
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "End shift", stop).build())
            .build()
    }

    override fun onDestroy() {
        runCatching { lm?.removeUpdates(this) }
        if (running && shiftId >= 0) db.updateShift(shiftId, System.currentTimeMillis(), acc.miles, active = true)
        running = false
        super.onDestroy()
    }
}
