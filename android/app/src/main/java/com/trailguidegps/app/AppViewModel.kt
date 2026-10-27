package com.trailguidegps.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Gpx
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.data.SearchResult
import com.trailguidegps.app.data.TrackPoint
import com.trailguidegps.app.data.WaterNotes
import com.trailguidegps.app.data.TrackStore
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.data.WaypointStore
import com.trailguidegps.app.location.LocationTracker
import com.trailguidegps.app.record.Recording
import com.trailguidegps.app.record.TrackRecorder
import com.trailguidegps.app.record.TrackRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Checking : DownloadState
    data class Downloading(val file: String, val done: Long, val total: Long) : DownloadState
    data class UpToDate(val tag: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = MapStore(app)
    val tracker = LocationTracker(app)

    private val _mapsReady = MutableStateFlow(store.isReady())
    val mapsReady: StateFlow<Boolean> = _mapsReady.asStateFlow()

    /** Bumped whenever new map files are installed so the map reloads its style. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** The newest map release found on GitHub, for showing its size before downloading. */
    private val _release = MutableStateFlow<MapStore.Release?>(null)
    val release: StateFlow<MapStore.Release?> = _release.asStateFlow()

    fun checkRelease() {
        if (_release.value != null) return
        viewModelScope.launch {
            _release.value = runCatching { store.findLatestRelease() }.getOrNull()
        }
    }

    private val _features = MutableStateFlow<Features?>(null)
    val features: StateFlow<Features?> = _features.asStateFlow()

    init {
        loadFeatures()
    }

    private fun loadFeatures() {
        if (!store.isReady()) return
        viewModelScope.launch {
            _features.value = withContext(Dispatchers.IO) { runCatching { Features.load(store) }.getOrNull() }
        }
    }

    /** Download the newest map files, or report that the installed ones are current. */
    fun downloadMaps(force: Boolean = false) {
        if (_download.value is DownloadState.Checking || _download.value is DownloadState.Downloading) return
        viewModelScope.launch {
            _download.value = DownloadState.Checking
            try {
                val release = store.findLatestRelease().also { _release.value = it }
                if (!force && store.isReady() && store.installedTag() == release.tag) {
                    _download.value = DownloadState.UpToDate(release.tag)
                    return@launch
                }
                store.download(release) { file, done, total ->
                    _download.value = DownloadState.Downloading(file, done, total)
                }
                _mapsReady.value = store.isReady()
                _mapVersion.value += 1
                loadFeatures()
                _download.value = DownloadState.UpToDate(release.tag)
            } catch (e: Exception) {
                _download.value = DownloadState.Error(friendly(e))
            }
        }
    }

    // ---- Waypoints, tracks and recording (phase 3) ----

    val waypoints = WaypointStore(app)
    val tracks = TrackStore(app)
    val recording: StateFlow<Recording?> = TrackRecorder.state

    /** A saved track drawn on the map, if the user chose one. */
    private val _shownTrack = MutableStateFlow<Pair<String, List<TrackPoint>>?>(null)
    val shownTrack: StateFlow<Pair<String, List<TrackPoint>>?> = _shownTrack.asStateFlow()

    /** Camera requests from other screens (for example "show on map" in Saved). */
    data class Focus(val points: List<Pair<Double, Double>>, val nonce: Long = System.nanoTime())
    private val _focus = MutableStateFlow<Focus?>(null)
    val focus: StateFlow<Focus?> = _focus.asStateFlow()

    /** Short messages for the user, shown once. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    init {
        // A recording that was cut off (phone restart, app killed) is saved, not lost.
        val leftover = TrackRecorder.recoverable(app)
        if (leftover.size >= 2) {
            tracks.save("Recovered track ${dateLabel(leftover.first().time)}", leftover)
            _message.value = "A track that was still recording was saved as \"Recovered track\"."
        }
        TrackRecorder.discardRecoverable(app)
    }

    fun focusOn(points: List<Pair<Double, Double>>) { if (points.isNotEmpty()) _focus.value = Focus(points) }

    // ---- Selection, search and water notes (phase 4) ----

    /** Whatever is selected on the map (a spring, campsite, trail, search result). */
    data class SelectedPlace(
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
        val osmId: String? = null,
        val isWater: Boolean = false,
    )
    private val _selected = MutableStateFlow<SelectedPlace?>(null)
    val selected: StateFlow<SelectedPlace?> = _selected.asStateFlow()
    fun select(p: SelectedPlace?) { _selected.value = p }

    val waterNotes = WaterNotes(app)

    /** Map centre, used to sort search results when there's no GPS fix. */
    @Volatile var mapCenter: Pair<Double, Double>? = null

    fun searchReference(): Pair<Double, Double>? =
        tracker.fix.value?.let { it.lat to it.lon } ?: mapCenter

    fun openResult(r: SearchResult) {
        val p = r.point
        val parts = mutableListOf(Features.KIND_LABELS[r.kind] ?: r.kind)
        if (p?.seasonal == true) parts += "may be seasonal"
        r.near?.let { parts += it }
        p?.lastEdit?.let { parts += "map data edited $it" }
        _selected.value = SelectedPlace(r.name, parts.joinToString(" · "), r.lat, r.lon, p?.osmId, p?.isWater == true)
        focusOn(listOf(r.lat to r.lon))
    }

    fun showTrack(id: String) {
        val (_, pts) = tracks.load(id) ?: return
        _shownTrack.value = id to pts
        focusOn(pts.map { it.lat to it.lon })
    }

    fun hideTrack() { _shownTrack.value = null }

    fun startRecording() {
        val app = getApplication<Application>()
        TrackRecorder.start(app)
        TrackRecordingService.start(app)
    }

    fun setPaused(paused: Boolean) = TrackRecorder.setPaused(paused)

    /** Stops recording; saves it unless [save] is false. */
    fun stopRecording(name: String?, save: Boolean) {
        val app = getApplication<Application>()
        val pts = TrackRecorder.finish(app)
        TrackRecordingService.stop(app)
        if (save && pts.size >= 2) {
            val summary = tracks.save(name?.takeIf { it.isNotBlank() } ?: defaultTrackName(pts.first().time), pts)
            _shownTrack.value = summary.id to pts
            _message.value = "Saved \"${summary.name}\"."
        } else if (save) {
            _message.value = "The track was too short to save."
        }
    }

    fun defaultTrackName(startMs: Long = System.currentTimeMillis()) = "Track ${dateLabel(startMs)}"

    private fun dateLabel(ms: Long) = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(java.util.Date(ms))

    fun deleteTrack(id: String) {
        tracks.delete(id)
        if (_shownTrack.value?.first == id) _shownTrack.value = null
    }

    fun importGpx(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { Gpx.read(it) }
                }
            }
            result.onSuccess { imp ->
                withContext(Dispatchers.IO) {
                    waypoints.addAll(imp.waypoints)
                    imp.tracks.forEach { (n, pts) -> tracks.save(n, pts) }
                }
                _message.value = "Imported ${imp.waypoints.size} waypoints and ${imp.tracks.size} tracks."
                val pts = imp.waypoints.map { it.lat to it.lon } + imp.tracks.flatMap { t -> t.second.map { it.lat to it.lon } }
                focusOn(pts)
            }.onFailure {
                _message.value = "That file couldn't be read as GPX."
            }
        }
    }

    /** Writes a GPX file to the app's share folder and returns a link other apps can open. */
    fun exportGpx(fileName: String, wps: List<Waypoint>, trackIds: List<String>): android.net.Uri? {
        val app = getApplication<Application>()
        return runCatching {
            val trk = trackIds.mapNotNull { tracks.load(it) }
            val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
            val safe = fileName.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "TrailGuide" }
            val file = java.io.File(dir, "$safe.gpx")
            file.writeText(Gpx.write(wps, trk))
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }.getOrNull()
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection. Connect to Wi-Fi or data and try again."
        is java.net.SocketTimeoutException -> "The download timed out. Check your connection and try again."
        else -> e.message ?: "Something went wrong while downloading."
    }

    override fun onCleared() {
        tracker.stop()
    }
}
