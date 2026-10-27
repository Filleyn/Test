package com.trailguidegps.app.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trailguidegps.app.MainActivity
import com.trailguidegps.app.R
import com.trailguidegps.app.data.SatelliteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Downloads satellite areas in the background, one at a time, with a progress
 * notification. Keeps going if you switch apps; stops when the queue is empty.
 */
class SatelliteDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Saving satellite imagery", "Starting…", 0, 0), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) {
            job = scope.launch {
                val store = SatelliteStore.get(this@SatelliteDownloadService)
                val nm = getSystemService(NotificationManager::class.java)
                while (true) {
                    val next = store.nextToDownload() ?: break
                    runCatching {
                        store.download(next) { a ->
                            nm.notify(NOTIFICATION_ID, notification("Saving satellite: ${a.name}", "${a.done} of ${a.total} tiles", a.done, a.total))
                        }
                    }.onFailure {
                        store.get(next.id)?.let { a -> store.pause(a.id) }
                    }
                    // A paused or failed area isn't picked again; anything else queued is.
                    if (store.get(next.id)?.status == com.trailguidegps.app.data.SatArea.STATUS_DOWNLOADING) store.pause(next.id)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Satellite downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while saving satellite imagery for offline use"
            })
        }
    }

    private fun notification(title: String, text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(title).setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "satellite"
        private const val NOTIFICATION_ID = 13

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SatelliteDownloadService::class.java))
        }
    }
}
