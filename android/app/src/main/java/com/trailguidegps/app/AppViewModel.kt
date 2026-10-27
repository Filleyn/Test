package com.trailguidegps.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Gpx
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.RouteLeg
import com.trailguidegps.app.data.RoutePoint
import com.trailguidegps.app.data.RouteStats
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.Terrain
import com.trailguidegps.app.route.RouteGraph
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
                    _update.value = null
                    _download.value = DownloadState.UpToDate(release.tag)
                    return@launch
                }
                store.download(release) { file, done, total ->
                    _download.value = DownloadState.Downloading(file, done, total)
                }
                _mapsReady.value = store.isReady()
                _mapVersion.value += 1
                _update.value = null
                loadFeatures()
                loadRouting()
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

    // ---- Map update notice ----

    /** A newer map release than the one installed, and what it would cost to download. */
    data class MapUpdate(val tag: String, val bytes: Long, val built: String)
    private val _update = MutableStateFlow<MapUpdate?>(null)
    val update: StateFlow<MapUpdate?> = _update.asStateFlow()
    private val _updateDismissed = MutableStateFlow(false)
    val updateDismissed: StateFlow<Boolean> = _updateDismissed.asStateFlow()
    fun dismissUpdate() { _updateDismissed.value = true }

    /** Quietly checks for newer maps when there's a connection; does nothing offline. */
    fun checkForMapUpdate() {
        if (!store.isReady()) return
        viewModelScope.launch {
            runCatching {
                val r = store.findLatestRelease()
                _release.value = r
                _update.value = if (r.tag != store.installedTag()) {
                    MapUpdate(r.tag, store.updateBytes(r), r.tag.removePrefix("maps-${store.region}-").substringBefore("-run"))
                } else null
            }
        }
    }

    // ---- Selection, search and water notes (phase 4) ----

    /** Whatever is selected on the map (a spring, campsite, trail, search result). */
    data class SelectedPlace(
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double,
        val osmId: String? = null,
        val isWater: Boolean = false,
        /** An official page to check, as (label, web address). */
        val link: Pair<String, String>? = null,
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

    // ---- Settings and following a route (phase 6) ----

    val settings = com.trailguidegps.app.data.SettingsStore.get(app)
    val following: StateFlow<com.trailguidegps.app.route.Following?> = com.trailguidegps.app.route.RouteFollower.state

    /** Follow the route in the bottom panel: progress, what's left, and off-route alerts. */
    fun startFollowing() {
        val r = _shownRoute.value ?: return
        val app = getApplication<Application>()
        com.trailguidegps.app.route.RouteFollower.start(r)
        TrackRecordingService.start(app)
        _message.value = if (settings.value.value.offRouteAlerts)
            "Following \"${r.name}\". You'll get a buzz if you're more than ${settings.value.value.offRouteFt} ft off it."
        else "Following \"${r.name}\". Off-route alerts are off in Settings."
    }

    /** A pace worked out from your recorded hikes, and how many hikes it used. */
    data class PaceSuggestion(val pace: Pace?, val hikes: Int)
    private val _paceSuggestion = MutableStateFlow<PaceSuggestion?>(null)
    val paceSuggestion: StateFlow<PaceSuggestion?> = _paceSuggestion.asStateFlow()

    /** Looks at saved tracks (with times) and fits a pace. Climb comes from the map's elevation, like route estimates. */
    fun suggestPace() {
        viewModelScope.launch {
            _paceSuggestion.value = withContext(Dispatchers.Default) {
                val hikes = tracks.summaries.value
                    .filter { it.stats.distanceM >= com.trailguidegps.app.geo.Geo.M_PER_MI && it.stats.movingMs >= 20 * 60_000 }
                    .mapNotNull { s ->
                        val pts = tracks.load(s.id)?.second ?: return@mapNotNull null
                        val coords = pts.map { doubleArrayOf(it.lat, it.lon) }
                        val gain = RouteStats.compute(coords, s.stats.distanceM, terrain).gainM.takeIf { terrain != null } ?: s.stats.gainM
                        val avgMph = s.stats.distanceM / com.trailguidegps.app.geo.Geo.M_PER_MI / (s.stats.movingMs / 3_600_000.0)
                        if (avgMph !in 0.5..4.5) return@mapNotNull null // imported tracks without real times
                        Pace.Companion.Hike(s.stats.distanceM, gain, s.stats.movingMs / 60_000.0, s.stats.totalMs / 60_000.0)
                    }
                PaceSuggestion(Pace.fit(hikes, _pace.value), hikes.size)
            }
        }
    }

    // ---- Wildfires (online, phase 6) ----

    val fires = com.trailguidegps.app.data.FireStore(app)
    private val _firesStatus = MutableStateFlow<String?>(null)
    val firesStatus: StateFlow<String?> = _firesStatus.asStateFlow()

    /** Fetches current fires if the copy is old (or [force]); quietly does nothing offline. */
    fun refreshFires(force: Boolean = false) {
        if (!force && !fires.stale()) return
        viewModelScope.launch {
            _firesStatus.value = "Updating…"
            _firesStatus.value = runCatching { fires.refresh() }.fold(
                onSuccess = { null },
                onFailure = { if (force) "Couldn't update: no connection?" else null },
            )
        }
    }

    // ---- Satellite areas saved for offline use (phase 7) ----

    val satellite = com.trailguidegps.app.data.SatelliteStore.get(app)

    /** Picking an area on the map to save; [replacing] when changing an existing area's boundary. */
    data class SatSelect(val replacing: com.trailguidegps.app.data.SatArea? = null)
    private val _satSelect = MutableStateFlow<SatSelect?>(null)
    val satSelect: StateFlow<SatSelect?> = _satSelect.asStateFlow()

    init {
        // Pick up downloads that were cut off when the app closed.
        if (satellite.nextToDownload() != null) {
            runCatching { com.trailguidegps.app.record.SatelliteDownloadService.start(app) }
        }
    }

    fun startSatSelect(replacing: com.trailguidegps.app.data.SatArea? = null) {
        select(null)
        _satSelect.value = SatSelect(replacing)
        replacing?.let { a -> focusOn(listOf(a.north to a.west, a.south to a.east)) }
    }

    fun cancelSatSelect() { _satSelect.value = null }

    fun saveSatArea(name: String, west: Double, south: Double, east: Double, north: Double, maxZoom: Int) {
        val replacing = _satSelect.value?.replacing
        val a = satellite.add(name.ifBlank { "Satellite area" }, west, south, east, north, maxZoom, replacing?.id)
        _satSelect.value = null
        com.trailguidegps.app.record.SatelliteDownloadService.start(getApplication())
        _message.value = "Saving \"${a.name}\" (${a.total} tiles). It keeps going if you leave the app."
    }

    fun resumeSatArea(id: String) {
        satellite.resume(id)
        com.trailguidegps.app.record.SatelliteDownloadService.start(getApplication())
    }

    fun stopFollowing() {
        val app = getApplication<Application>()
        com.trailguidegps.app.route.RouteFollower.stop(app)
        TrackRecordingService.stop(app)
    }

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
                    imp.routes.forEach { (n, pts) ->
                        val coords = pts.map { doubleArrayOf(it.lat, it.lon) }
                        val dist = coords.zipWithNext { a, b -> com.trailguidegps.app.geo.Geo.distanceM(a[0], a[1], b[0], b[1]) }.sum()
                        routes.save(SavedRoute(
                            RouteStore.newId(), n, System.currentTimeMillis(),
                            listOfNotNull(pts.firstOrNull(), pts.lastOrNull()).map { RoutePoint(it.lat, it.lon, true) },
                            coords, RouteStats.compute(coords, dist, terrain), emptyList(),
                        ))
                    }
                }
                _message.value = "Imported ${imp.waypoints.size} waypoints, ${imp.routes.size} routes and ${imp.tracks.size} tracks."
                val pts = imp.waypoints.map { it.lat to it.lon } + imp.tracks.flatMap { t -> t.second.map { it.lat to it.lon } } +
                    imp.routes.flatMap { r -> r.second.map { it.lat to it.lon } }
                focusOn(pts)
            }.onFailure {
                _message.value = "That file couldn't be read as GPX."
            }
        }
    }

    /** Writes a GPX file to the app's share folder and returns a link other apps can open. */
    fun exportGpx(fileName: String, wps: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList()): android.net.Uri? {
        val app = getApplication<Application>()
        return runCatching {
            val trk = trackIds.mapNotNull { tracks.load(it) }
            val rte = routeIds.mapNotNull { routes.load(it) }.map { it.name to it.coords }
            val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
            val safe = fileName.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "TrailGuide" }
            val file = java.io.File(dir, "$safe.gpx")
            file.writeText(Gpx.write(wps, trk, rte))
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }.getOrNull()
    }

    // ---- Route planning (phase 5) ----

    val routes = RouteStore(app)
    @Volatile private var graph: RouteGraph? = null
    @Volatile private var terrain: Terrain? = null

    /** null while loading, true when the trail network is ready, false if it isn't downloaded. */
    private val _routingReady = MutableStateFlow<Boolean?>(null)
    val routingReady: StateFlow<Boolean?> = _routingReady.asStateFlow()

    private val _pace = MutableStateFlow(Pace.load(app))
    val pace: StateFlow<Pace> = _pace.asStateFlow()
    fun setPace(p: Pace) { Pace.save(getApplication(), p); _pace.value = p }

    init { loadRouting() }

    /** Opens the trail network and elevation tiles (unpacking the network the first time). */
    fun loadRouting() {
        if (!store.isReady()) { _routingReady.value = false; return }
        viewModelScope.launch(Dispatchers.IO) {
            _routingReady.value = null
            terrain = runCatching { Terrain(store.file("terrain.pmtiles")) }.getOrNull()
            graph = if (store.unpackRouting()) RouteGraph.open(store.routingFile()) else null
            _routingReady.value = graph != null
        }
    }

    /** Elevation in metres from the map's terrain tiles, or null. */
    fun elevationAt(lat: Double, lon: Double): Double? = runCatching { terrain?.elevationM(lat, lon) }.getOrNull()

    data class PlanState(
        val active: Boolean = false,
        val points: List<RoutePoint> = emptyList(),
        val legs: List<RouteLeg> = emptyList(),
        val stats: RouteStats = RouteStats.EMPTY,
        val followTrails: Boolean = true,
        val busy: Boolean = false,
    ) {
        val via: List<String> get() = legs.flatMap { it.names }.fold(mutableListOf()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
    }

    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan.asStateFlow()
    private val planLock = kotlinx.coroutines.sync.Mutex()

    fun startPlanning() {
        select(null)
        _plan.value = PlanState(active = true)
    }

    fun cancelPlanning() { _plan.value = PlanState() }

    fun setFollowTrails(on: Boolean) { _plan.value = _plan.value.copy(followTrails = on) }

    /** Adds a point; the leg to it follows trails when that mode is on and a connection exists. */
    fun addPlanPoint(lat: Double, lon: Double, waypointName: String? = null) {
        viewModelScope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (!st.active) return@launch
                _plan.value = st.copy(busy = true)
                val (point, leg, note) = withContext(Dispatchers.Default) {
                    buildLeg(st.points.lastOrNull(), lat, lon, st.followTrails, waypointName)
                }
                val legs = if (leg != null) st.legs + leg else st.legs
                val stats = withContext(Dispatchers.Default) { statsFor(legs) }
                _plan.value = _plan.value.copy(points = st.points + point, legs = legs, stats = stats, busy = false)
                note?.let { _message.value = it }
            } finally {
                planLock.unlock()
            }
        }
    }

    fun undoPlanPoint() {
        viewModelScope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (st.points.isEmpty()) return@launch
                // The first point has no leg; every later point has the leg leading to it.
                val legs = if (st.points.size > 1) st.legs.dropLast(1) else st.legs
                val stats = withContext(Dispatchers.Default) { statsFor(legs) }
                _plan.value = st.copy(points = st.points.dropLast(1), legs = legs, stats = stats)
            } finally {
                planLock.unlock()
            }
        }
    }

    private fun statsFor(legs: List<RouteLeg>): RouteStats {
        if (legs.isEmpty()) return RouteStats.EMPTY
        val coords = ArrayList<DoubleArray>()
        legs.forEach { leg -> leg.coords.forEach { c -> if (coords.lastOrNull()?.let { it[0] == c[0] && it[1] == c[1] } != true) coords += c } }
        return RouteStats.compute(coords, legs.sumOf { it.lengthM }, terrain)
    }

    private fun straightLeg(a: RoutePoint, b: RoutePoint) = RouteLeg(
        listOf(doubleArrayOf(a.lat, a.lon), doubleArrayOf(b.lat, b.lon)),
        com.trailguidegps.app.geo.Geo.distanceM(a.lat, a.lon, b.lat, b.lon), emptyList(), onTrail = false,
    )

    private fun buildLeg(prev: RoutePoint?, lat: Double, lon: Double, follow: Boolean, wp: String?): Triple<RoutePoint, RouteLeg?, String?> {
        val g = graph
        val dist = com.trailguidegps.app.geo.Geo::distanceM
        val snapNew = if (follow && g != null) g.snap(lat, lon) else null
        // A tapped point moves onto the trail; a waypoint keeps its exact spot.
        val point = if (snapNew != null && wp == null) RoutePoint(snapNew.lat, snapNew.lon, follow, wp) else RoutePoint(lat, lon, follow, wp)
        if (prev == null) {
            val note = when {
                follow && g == null -> "The trail network isn't downloaded yet, so routes use straight lines. Check for map updates."
                follow && snapNew == null -> "No trail within a quarter mile of this point."
                else -> null
            }
            return Triple(point, null, note)
        }
        if (!follow) return Triple(point, straightLeg(prev, point), null)
        if (g == null) {
            return Triple(point, straightLeg(prev, point), "The trail network isn't downloaded yet, so this part is a straight line.")
        }
        val snapPrev = g.snap(prev.lat, prev.lon)
        val routed = if (snapPrev != null && snapNew != null) g.route(snapPrev, snapNew) else null
        if (routed == null || snapPrev == null || snapNew == null) {
            val why = if (snapNew == null || snapPrev == null) "No trail within a quarter mile, so this part is a straight line."
            else "Couldn't find a trail connection, so this part is a straight line."
            return Triple(point, straightLeg(prev, point), why)
        }
        val coords = ArrayList<DoubleArray>()
        var len = routed.lengthM
        val offStart = dist(prev.lat, prev.lon, snapPrev.lat, snapPrev.lon)
        if (offStart > 15) { coords += doubleArrayOf(prev.lat, prev.lon); len += offStart }
        coords += routed.coords
        val offEnd = dist(snapNew.lat, snapNew.lon, point.lat, point.lon)
        if (offEnd > 15) { coords += doubleArrayOf(point.lat, point.lon); len += offEnd }
        return Triple(point, RouteLeg(coords, len, routed.names, onTrail = true, routed.kindM, routed.sacMax, routed.sacRatedM), null)
    }

    fun savePlan(name: String, color: String = com.trailguidegps.app.data.RouteColors.DEFAULT) {
        val st = _plan.value
        if (st.points.size < 2) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val coords = ArrayList<DoubleArray>()
                st.legs.forEach { leg -> leg.coords.forEach { c -> if (coords.lastOrNull()?.let { it[0] == c[0] && it[1] == c[1] } != true) coords += c } }
                SavedRoute(RouteStore.newId(), name.ifBlank { "Route" }, System.currentTimeMillis(), st.points, coords, st.stats, st.via, color,
                    com.trailguidegps.app.data.RouteTerrain.of(st.legs))
                    .also { routes.save(it) }
            }
            _plan.value = PlanState()
            setShown(_shownRoutes.value + saved, saved)
            _message.value = "Saved \"${saved.name}\"."
        }
    }

    /** Saved routes drawn on the map, and the one whose details show in the bottom panel. */
    private val _shownRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val shownRoutes: StateFlow<List<SavedRoute>> = _shownRoutes.asStateFlow()
    private val _shownRoute = MutableStateFlow<SavedRoute?>(null)
    val shownRoute: StateFlow<SavedRoute?> = _shownRoute.asStateFlow()
    private val shownPrefs = app.getSharedPreferences("shown_routes", android.content.Context.MODE_PRIVATE)

    init {
        // Bring back the routes that were on the map last time.
        viewModelScope.launch {
            val ids = shownPrefs.getString("ids", "").orEmpty().split(",").filter { it.isNotBlank() }
            val loaded = withContext(Dispatchers.IO) { ids.mapNotNull { routes.load(it) } }
            if (loaded.isNotEmpty() && _shownRoutes.value.isEmpty()) {
                _shownRoutes.value = loaded
                _shownRoute.value = loaded.firstOrNull { it.id == shownPrefs.getString("focus", null) } ?: loaded.last()
            }
        }
    }

    private fun setShown(list: List<SavedRoute>, focus: SavedRoute?) {
        _shownRoutes.value = list
        _shownRoute.value = focus
        shownPrefs.edit().putString("ids", list.joinToString(",") { it.id }).putString("focus", focus?.id).apply()
    }

    /** Adds a saved route to the map (keeping any others) and shows its details. */
    fun showRoute(id: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            setShown(_shownRoutes.value.filter { it.id != id } + r, r)
            focusOn(r.coords.map { it[0] to it[1] })
        }
    }

    /** Shows the details of a route already on the map (after tapping its line). */
    fun focusRoute(id: String) {
        _shownRoutes.value.firstOrNull { it.id == id }?.let { setShown(_shownRoutes.value, it) }
    }

    /** Takes a route off the map; with no id, the one in the bottom panel. */
    fun hideRoute(id: String? = _shownRoute.value?.id) {
        if (following.value?.route?.id == id) stopFollowing()
        val rest = _shownRoutes.value.filter { it.id != id }
        setShown(rest, if (_shownRoute.value?.id == id) rest.lastOrNull() else _shownRoute.value)
    }

    fun hideAllRoutes() {
        if (following.value != null) stopFollowing()
        setShown(emptyList(), null)
    }

    /** Puts every saved route on the map, so any of them can be tapped. */
    fun showAllRoutes() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { routes.summaries.value.mapNotNull { routes.load(it.id) } }
            setShown(all, _shownRoute.value?.let { f -> all.firstOrNull { it.id == f.id } } ?: all.firstOrNull())
        }
    }

    private fun updateShown(id: String, change: (SavedRoute) -> SavedRoute) {
        val list = _shownRoutes.value.map { if (it.id == id) change(it) else it }
        setShown(list, _shownRoute.value?.let { f -> list.firstOrNull { it.id == f.id } })
    }

    fun deleteRoute(id: String) {
        routes.delete(id)
        hideRoute(id)
    }

    fun setRouteColor(id: String, color: String) {
        routes.setColor(id, color)
        updateShown(id) { it.copy(color = color) }
    }

    fun renameRoute(id: String, name: String) {
        routes.rename(id, name)
        updateShown(id) { it.copy(name = name) }
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
