package com.trailguidegps.app.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trailguidegps.app.MainActivity
import com.trailguidegps.app.R
import com.trailguidegps.app.geo.Geo
import com.trailguidegps.app.route.RouteFollower
import com.trailguidegps.app.data.SettingsStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps GPS running while a track records or a route is being followed,
 * including with the screen off. Android requires a visible notification for
 * this; it shows the live totals.
 */
class TrackRecordingService : Service() {
    private lateinit var lm: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = LocationListener {
        TrackRecorder.add(it)
        RouteFollower.update(this, it)
        checkBattery()
    }
    private var intervalNow = 0L
    /** Battery saver switched on by itself because the battery got low. */
    private var autoSaver = false
    private var warned20 = false
    private var warned10 = false
    private var lastBatteryCheck = 0L

    private fun wantedInterval(): Long {
        val s = SettingsStore.get(this).value.value
        return if (s.batterySaver || autoSaver) SAVER_INTERVAL_MS else INTERVAL_MS
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val want = wantedInterval()
        if (want == intervalNow) return
        lm.removeUpdates(listener)
        intervalNow = want
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, want, 0f, listener, Looper.getMainLooper()) }
    }

    /** Warns at 20% and 10%, and turns on battery saver at 20% if that's allowed. */
    private fun checkBattery() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheck < 60_000) return
        lastBatteryCheck = now
        val s = SettingsStore.get(this).value.value
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || plugged) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        if (pct <= 20 && !warned20) {
            warned20 = true
            val saving = s.autoSaverAtLow && !s.batterySaver
            if (saving) { autoSaver = true; requestUpdates() }
            if (s.lowBatteryWarning) {
                lowBatteryAlert("Battery at $pct%", if (saving) "Switched to battery saver: GPS every 15 seconds. Recording and route alerts keep going."
                    else "Recording and route alerts keep going. Battery saver in Settings stretches it further.")
            }
        }
        if (pct <= 10 && !warned10) {
            warned10 = true
            if (s.lowBatteryWarning) lowBatteryAlert("Battery at $pct%", "Consider saving your track now. Your emergency location screen works offline.")
        }
    }

    private fun lowBatteryAlert(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(BATTERY_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(BATTERY_CHANNEL, "Low battery", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Low battery warnings while recording or following a route"
            })
        }
        val n = NotificationCompat.Builder(this, BATTERY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        runCatching { nm.notify(BATTERY_NOTIFICATION_ID, n) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("TrailGuide GPS", "Starting GPS…"), type)
        requestUpdates()
        quietHandler.postDelayed(quietCheck, 30_000)
        // Battery saver switched on or off in Settings takes effect straight away.
        scope.launch { SettingsStore.get(this@TrackRecordingService).value.collectLatest { requestUpdates() } }
        scope.launch {
            combine(TrackRecorder.state, RouteFollower.state) { rec, fol -> rec to fol }.collectLatest { (rec, fol) ->
                if (rec == null && fol == null) return@collectLatest
                val lines = mutableListOf<String>()
                val title = when {
                    fol != null && fol.offRoute -> "Off route: ${fol.route.name}"
                    fol != null -> "Following ${fol.route.name}"
                    rec?.paused == true -> "Track paused"
                    else -> "Recording track"
                }
                fol?.let { lines += "${Geo.formatMiles(it.remainingM)} to go · +${Geo.formatFeet(it.climbLeftM)} climb left" }
                rec?.let {
                    lines += (if (fol != null) "Recording: " else "") +
                        "${Geo.formatMiles(it.stats.distanceM)} · +${Geo.formatFeet(it.stats.gainM)} · ${Geo.formatDuration(it.stats.movingMs)} moving"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, lines.joinToString("\n")))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If Android restarted the service after killing the app, the in-memory recording
        // is gone; its points are on disk and get saved as a recovered track on next launch.
        // Restarted by Android with a recording cut off on disk: carry on recording it.
        if (intent == null && !TrackRecorder.isRecording()) runCatching { TrackRecorder.resume(this) }
        if (intent?.action == ACTION_STOP || (!TrackRecorder.isRecording() && !RouteFollower.isFollowing())) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Every half minute: has GPS gone quiet while following a route? */
    private val quietHandler = android.os.Handler(Looper.getMainLooper())
    private val quietCheck = object : Runnable {
        override fun run() {
            runCatching { RouteFollower.checkQuiet(this@TrackRecordingService) }
            quietHandler.postDelayed(this, 30_000)
        }
    }

    override fun onDestroy() {
        quietHandler.removeCallbacks(quietCheck)
        lm.removeUpdates(listener)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a track is recording or a route is being followed"
                }
            )
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7
        /** A fix every 5 seconds balances track detail against battery. */
        const val INTERVAL_MS = 5_000L
        private const val ACTION_STOP = "stop"
        /** Battery saver: a fix every 15 seconds. */
        const val SAVER_INTERVAL_MS = 15_000L
        private const val BATTERY_CHANNEL = "battery"
        private const val BATTERY_NOTIFICATION_ID = 12

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackRecordingService::class.java))
        }

        /** Stops GPS in the background, unless recording or following still needs it. */
        fun stop(context: Context) {
            if (TrackRecorder.isRecording() || RouteFollower.isFollowing()) return
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }
    }
}
