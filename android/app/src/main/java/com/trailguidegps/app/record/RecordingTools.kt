package com.trailguidegps.app.record

import android.content.Context
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.SettingsStore
import com.trailguidegps.app.data.TrackPoint
import com.trailguidegps.app.data.TrackStore
import com.trailguidegps.app.route.RouteFollower

/**
 * Starting, stopping and saving a recorded track, and starting and stopping Follow. The
 * recorder's on-disk copy is deleted only after the track is safely saved, so a failed save
 * (a full phone) is tried again later instead of losing the track.
 */
class RecordingTools(
    private val app: Context,
    private val tracks: TrackStore,
    private val settings: SettingsStore,
    private val message: (String) -> Unit,
    /** A track was saved: (its id, its points, the folder picked or null). */
    private val onSaved: (String, List<TrackPoint>, String?) -> Unit,
) {
    /** A recording that was cut off (phone restart, app killed) is saved, not lost. */
    fun recoverAtLaunch() {
        // If saving it fails (a full phone), the on-disk copy is kept for the next launch.
        runCatching {
            val leftover = TrackRecorder.recoverable(app)
            if (leftover.size >= 2) {
                tracks.save("Recovered track ${dateLabel(leftover.first().time)}", leftover)
                message("A track that was still recording was saved as \"Recovered track\".")
            }
            TrackRecorder.discardRecoverable(app)
        }.onFailure {
            message("A track that was still recording couldn't be saved (is the phone full?). It's kept and will be tried again next time.")
        }
    }

    fun start() {
        // A track that couldn't be saved earlier is still on disk; starting a new one would erase it.
        val leftover = TrackRecorder.recoverable(app)
        if (leftover.size >= 2) {
            val saved = runCatching { tracks.save("Recovered track ${dateLabel(leftover.first().time)}", leftover) }.isSuccess
            if (!saved) {
                message("An earlier track still couldn't be saved (is the phone full?). Free some space, then start recording.")
                return
            }
        }
        TrackRecorder.start(app)
        TrackRecordingService.start(app)
    }

    fun setPaused(paused: Boolean) = TrackRecorder.setPaused(paused)

    fun stop(name: String?, save: Boolean, folderId: String?) {
        // The recorder's on-disk copy is deleted only after the track is safely saved.
        val pts = TrackRecorder.stop()
        TrackRecordingService.stop(app)
        if (save && pts.size >= 2) {
            val summary = runCatching { tracks.save(name?.takeIf { it.isNotBlank() } ?: defaultTrackName(pts.first().time), pts) }.getOrElse {
                message("The track couldn't be saved (is the phone full?). It's kept, and will be saved as a recovered track next time the app opens.")
                return
            }
            TrackRecorder.discardRecoverable(app)
            onSaved(summary.id, pts, folderId)
            message("Saved \"${summary.name}\".")
        } else {
            TrackRecorder.discardRecoverable(app)
            if (save) message("The track was too short to save.")
        }
    }

    fun defaultTrackName(startMs: Long = System.currentTimeMillis()) = "Track ${dateLabel(startMs)}"

    private fun dateLabel(ms: Long) = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(java.util.Date(ms))

    /** Follow a route: progress, what's left, and off-route alerts. */
    fun startFollowing(r: SavedRoute) {
        RouteFollower.start(r)
        RouteFollower.remember(app, r.id)
        TrackRecordingService.start(app)
        message(if (settings.value.value.offRouteAlerts)
            "Following \"${r.name}\". You'll get a buzz if you're more than ${settings.value.value.offRouteFt} ft off it."
        else "Following \"${r.name}\". Off-route alerts are off in Settings.")
    }

    fun stopFollowing() {
        RouteFollower.stop(app)
        TrackRecordingService.stop(app)
    }

    /**
     * The app was killed while following a route: pick it back up (the service does the same if
     * Android restarts it first).
     */
    fun resumeFollowing(load: (String) -> SavedRoute?) {
        if (RouteFollower.resume(app, load)) TrackRecordingService.start(app)
    }
}
