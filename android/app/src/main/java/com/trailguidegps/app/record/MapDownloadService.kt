package com.trailguidegps.app.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trailguidegps.app.DownloadState
import com.trailguidegps.app.MainActivity
import com.trailguidegps.app.R
import com.trailguidegps.app.data.MapStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State map downloads, shared by the whole app. The work runs in [MapDownloadService], a
 * foreground service with a progress notification, so Android keeps it going when you
 * switch apps or the screen turns off.
 */
object MapDownloads {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /** Bumped after each state's files are installed, so the app reloads its maps. */
    private val _installed = MutableStateFlow(0)
    val installed: StateFlow<Int> = _installed.asStateFlow()

    private val queue = ArrayDeque<Pair<String, MapStore.Release?>>()

    val busy get() = _state.value.let { it is DownloadState.Checking || it is DownloadState.Downloading }

    /** Downloads (or updates) these states one after another. [releases] skips a lookup when known. */
    fun start(context: Context, regions: List<String>, releases: Map<String, MapStore.Release> = emptyMap()) {
        if (regions.isEmpty()) return
        synchronized(queue) {
            regions.forEach { r -> if (queue.none { it.first == r }) queue.addLast(r to releases[r]) }
        }
        if (!busy) _state.value = DownloadState.Checking
        context.startForegroundService(Intent(context, MapDownloadService::class.java))
    }

    internal fun next(): Pair<String, MapStore.Release?>? = synchronized(queue) { queue.removeFirstOrNull() }
    internal fun clear() = synchronized(queue) { queue.clear() }
    internal fun set(s: DownloadState) { _state.value = s }
    internal fun installedOne() { _installed.value += 1 }

    fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection. Connect to Wi-Fi or data and try again."
        is java.net.SocketTimeoutException, is java.net.SocketException ->
            "The connection dropped. Tap Download again and it picks up where it stopped."
        is java.io.IOException -> if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true)
            "The phone is out of space. Free some up and try again." else (e.message ?: "The download stopped. Try again to resume.")
        is OutOfMemoryError -> "The phone ran low on memory. Try again; the download resumes."
        else -> e.message ?: "Something went wrong while downloading."
    }
}

class MapDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Downloading maps", "Starting…", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val (region, known) = MapDownloads.next() ?: break
            val store = MapStore(this, region)
            MapDownloads.set(DownloadState.Checking)
            try {
                val release = known ?: store.findLatestRelease()
                // Progress is shown at most a few times a second; updating the screen for
                // every network read made the whole map redraw thousands of times.
                var lastAt = 0L
                var lastPct = -1
                store.download(release) { file, done, total ->
                    val now = System.currentTimeMillis()
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (now - lastAt >= 300 || done >= total) {
                        lastAt = now
                        MapDownloads.set(DownloadState.Downloading(file, done, total, region))
                        if (pct != lastPct) {
                            lastPct = pct
                            nm.notify(NOTIFICATION_ID, notification("Downloading ${store.name} maps", "$pct% · ${mb(done)} of ${mb(total)}", pct, 100))
                        }
                    }
                }
                MapDownloads.installedOne()
                MapDownloads.set(DownloadState.UpToDate(release.tag))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("TrailGuide", "Map download failed", e)
                MapDownloads.clear()
                MapDownloads.set(DownloadState.Error(MapDownloads.friendly(e)))
                break
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (MapDownloads.busy) MapDownloads.set(DownloadState.Error("The download stopped. Try again to resume."))
        super.onDestroy()
    }

    private fun mb(b: Long) = if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Map downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while downloading state maps"
            })
        }
    }

    private fun notification(title: String, text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        private const val CHANNEL = "maps"
        private const val NOTIFICATION_ID = 14
    }
}
