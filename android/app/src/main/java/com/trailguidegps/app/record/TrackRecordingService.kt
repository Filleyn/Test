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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps GPS running while a track records, including with the screen off.
 * Android requires a visible notification for this; it shows the live totals.
 */
class TrackRecordingService : Service() {
    private lateinit var lm: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = LocationListener { TrackRecorder.add(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Recording track", "Starting GPS…"), type)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        }
        scope.launch {
            TrackRecorder.state.collectLatest { rec ->
                if (rec == null) return@collectLatest
                val title = if (rec.paused) "Track paused" else "Recording track"
                val text = "${Geo.formatMiles(rec.stats.distanceM)} · +${Geo.formatFeet(rec.stats.gainM)} · ${Geo.formatDuration(rec.stats.movingMs)} moving"
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If Android restarted the service after killing the app, the in-memory recording
        // is gone; its points are on disk and get saved as a recovered track on next launch.
        if (intent?.action == ACTION_STOP || !TrackRecorder.isRecording()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lm.removeUpdates(listener)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a track is recording"
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackRecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }
    }
}
