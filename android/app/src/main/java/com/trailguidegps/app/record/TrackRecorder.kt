package com.trailguidegps.app.record

import android.content.Context
import android.location.Location
import android.os.Build
import com.trailguidegps.app.data.TrackPoint
import com.trailguidegps.app.data.TrackStats
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** Live state of the track being recorded. */
data class Recording(
    val startedAt: Long,
    val points: List<TrackPoint>,
    val paused: Boolean,
    val stats: TrackStats,
)

/**
 * Holds the track being recorded. The recording service feeds it locations;
 * the map and bottom panel read [state]. Every point is also appended to a
 * file, so a crash or a killed app never loses the hike.
 */
object TrackRecorder {
    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state.asStateFlow()

    private var file: File? = null
    private const val MAX_ACCURACY_M = 35f
    private const val MIN_STEP_M = 4.0

    private fun journal(context: Context) = File(context.filesDir, "recording-in-progress.jsonl")

    fun isRecording() = _state.value != null

    fun start(context: Context) {
        if (_state.value != null) return
        file = journal(context).apply { delete() }
        _state.value = Recording(System.currentTimeMillis(), emptyList(), false, TrackStats.of(emptyList()))
    }

    fun setPaused(paused: Boolean) {
        _state.value = _state.value?.copy(paused = paused)
    }

    @Synchronized
    fun add(location: Location) {
        val rec = _state.value ?: return
        if (rec.paused) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val ele = when {
            Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude() -> location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> null
        }
        val p = TrackPoint(location.latitude, location.longitude, ele, location.time)
        val last = rec.points.lastOrNull()
        // Skip GPS jitter while standing still, but keep a point at least once a minute.
        if (last != null && Geo.distanceM(last.lat, last.lon, p.lat, p.lon) < MIN_STEP_M && p.time - last.time < 60_000) return
        val pts = rec.points + p
        _state.value = rec.copy(points = pts, stats = TrackStats.of(pts))
        file?.appendText(JSONArray().put(p.lat).put(p.lon).put(p.eleM ?: org.json.JSONObject.NULL).put(p.time).toString() + "\n")
    }

    /** Ends the recording and returns its points. */
    fun finish(context: Context): List<TrackPoint> {
        val pts = _state.value?.points.orEmpty()
        _state.value = null
        journal(context).delete()
        file = null
        return pts
    }

    /** Points left over from a recording that never finished (app killed), if any. */
    fun recoverable(context: Context): List<TrackPoint> {
        if (_state.value != null) return emptyList()
        val f = journal(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            runCatching {
                val a = JSONArray(line)
                TrackPoint(a.getDouble(0), a.getDouble(1), if (a.isNull(2)) null else a.getDouble(2), a.getLong(3))
            }.getOrNull()
        }
    }

    fun discardRecoverable(context: Context) {
        if (_state.value == null) journal(context).delete()
    }
}
