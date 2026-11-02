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
    data class Downloading(val file: String, val done: Long, val total: Long, val region: String? = null) : DownloadState
    data class UpToDate(val tag: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    val tracker = LocationTracker(app)

    // ---- Maps for several states ----

    /** States downloaded on the phone, Oregon (or the first) first. */
    private val _installed = MutableStateFlow(MapStore.installedRegions(app))
    val installed: StateFlow<List<String>> = _installed.asStateFlow()
    val stores: List<MapStore> get() = _installed.value.map { MapStore(ctx, it) }
    /** The first downloaded state (for single-state details like the build date). */
    val store: MapStore get() = stores.firstOrNull() ?: MapStore(ctx)

    private val mapPrefs = app.getSharedPreferences("maps", android.content.Context.MODE_PRIVATE)
    /** Using the online map without downloading any state yet. */
    private val _onlineOnly = MutableStateFlow(mapPrefs.getBoolean("onlineOnly", false))

    private val _mapsReady = MutableStateFlow(_installed.value.isNotEmpty() || _onlineOnly.value)
    val mapsReady: StateFlow<Boolean> = _mapsReady.asStateFlow()

    fun useOnlineMap() {
        mapPrefs.edit().putBoolean("onlineOnly", true).apply()
        _onlineOnly.value = true
        _mapsReady.value = true
        _mapVersion.value += 1
    }

    /** A state available to download: its newest release, name and area. */
    data class RegionInfo(val region: String, val title: String, val release: MapStore.Release, val bbox: DoubleArray?, val built: String?) {
        val name get() = MapStore.displayName(region)
    }
    private val _catalog = MutableStateFlow(loadCachedCatalog())
    val catalog: StateFlow<List<RegionInfo>> = _catalog.asStateFlow()
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** Looks up which states have maps (online), with their sizes and areas; kept for offline use. */
    fun refreshCatalog() {
        viewModelScope.launch {
            runCatching {
                val releases = MapStore.catalog()
                val list = releases.map { (region, rel) ->
                    val m = MapStore.fetchManifest(rel)
                    RegionInfo(region, m?.optString("title")?.takeIf { it.isNotBlank() } ?: MapStore.displayName(region), rel,
                        m?.optJSONArray("bbox")?.let { a -> DoubleArray(4) { a.getDouble(it) } },
                        m?.optString("built_utc")?.take(10))
                }.sortedBy { it.name }
                val changed = list.map { it.release.tag } != _catalog.value.map { it.release.tag }
                _catalog.value = list
                _catalogError.value = null
                saveCatalog(list)
                if (changed) _mapVersion.value += 1
                computeUpdates()
            }.onFailure { _catalogError.value = friendly(it as? Exception ?: Exception(it)) }
        }
    }

    private fun catalogFile() = java.io.File(ctx.filesDir, "map-catalog.json")
    private fun saveCatalog(list: List<RegionInfo>) = runCatching {
        catalogFile().writeText(org.json.JSONArray().apply {
            list.forEach { r ->
                put(org.json.JSONObject().put("region", r.region).put("title", r.title).put("tag", r.release.tag).put("built", r.built ?: "")
                    .put("bbox", r.bbox?.let { org.json.JSONArray(it.toList()) } ?: org.json.JSONObject.NULL)
                    .put("assets", org.json.JSONArray().apply { r.release.assets.forEach { a -> put(org.json.JSONObject().put("n", a.name).put("u", a.url).put("s", a.size)) } }))
            }
        }.toString())
    }
    private fun loadCachedCatalog(): List<RegionInfo> = runCatching {
        val arr = org.json.JSONArray(java.io.File((this as AndroidViewModel).getApplication<Application>().filesDir, "map-catalog.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val assets = o.getJSONArray("assets")
            RegionInfo(o.getString("region"), o.getString("title"),
                MapStore.Release(o.getString("tag"), (0 until assets.length()).map { j -> assets.getJSONObject(j).let { MapStore.Asset(it.getString("n"), it.getString("u"), it.getLong("s")) } }),
                o.optJSONArray("bbox")?.let { a -> DoubleArray(4) { a.getDouble(it) } }, o.optString("built").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    /** Map files for the style: downloaded states from the phone, the rest streamed from GitHub. */
    fun mapSources(): List<com.trailguidegps.app.map.MapStyle.MapSource> {
        val local = stores.map { com.trailguidegps.app.map.MapStyle.MapSource(it.region, it.dir) }
        val remote = _catalog.value.filter { it.region !in _installed.value }.map { r ->
            com.trailguidegps.app.map.MapStyle.MapSource(r.region, null,
                "https://github.com/${com.trailguidegps.app.BuildConfig.MAPS_REPO}/releases/download/${r.release.tag}",
                r.release.assets.map { it.name }.toSet())
        }
        return local + remote
    }

    /** The state whose area contains a spot, from the catalog (or the downloaded manifests). */
    fun regionAt(lat: Double, lon: Double): String? {
        val boxes = _catalog.value.mapNotNull { r -> r.bbox?.let { r.region to it } } + stores.mapNotNull { s -> s.bbox()?.let { s.region to it } }
        // Smallest box first, so a point in a small state isn't claimed by a big neighbour's rectangle.
        return boxes.filter { (_, b) -> lon >= b[0] && lat >= b[1] && lon <= b[2] && lat <= b[3] }
            .minByOrNull { (_, b) -> (b[2] - b[0]) * (b[3] - b[1]) }?.first
    }

    /** Downloads (or updates) one state's maps. */
    fun downloadRegion(region: String) {
        if (_download.value is DownloadState.Checking || _download.value is DownloadState.Downloading) return
        viewModelScope.launch {
            _download.value = DownloadState.Checking
            try {
                val st = MapStore(ctx, region)
                val release = _catalog.value.firstOrNull { it.region == region }?.release ?: st.findLatestRelease()
                st.download(release) { file, done, total -> _download.value = DownloadState.Downloading(file, done, total, region) }
                mapsChanged()
                _download.value = DownloadState.UpToDate(release.tag)
            } catch (e: Exception) {
                _download.value = DownloadState.Error(friendly(e))
            }
        }
    }

    /** Removes one state's maps from the phone. */
    fun deleteRegion(region: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { MapStore(ctx, region).delete() }
            mapsChanged()
        }
    }

    private fun mapsChanged() {
        _installed.value = MapStore.installedRegions(ctx)
        com.trailguidegps.app.data.MapRegion.names = _installed.value.map { MapStore.displayName(it) }
        _mapsReady.value = _installed.value.isNotEmpty() || _onlineOnly.value
        _mapVersion.value += 1
        loadFeatures()
        loadRouting()
        computeUpdates()
    }

    /** Downloaded states with a newer map release, and how much the updates would download. */
    private val _updates = MutableStateFlow<List<String>>(emptyList())
    val updates: StateFlow<List<String>> = _updates.asStateFlow()
    private fun computeUpdates() {
        val cat = _catalog.value.associateBy { it.region }
        val need = stores.filter { s -> cat[s.region]?.let { it.release.tag != s.installedTag() } == true }
        _updates.value = need.map { it.region }
        viewModelScope.launch {
            runCatching {
                _update.value = if (need.isEmpty()) null else {
                    val bytes = need.sumOf { s -> s.updateBytes(cat.getValue(s.region).release) }
                    MapUpdate(need.joinToString(",") { it.region }, bytes, need.joinToString(", ") { it.name })
                }
            }
        }
    }

    /** Updates every downloaded state that has newer maps, one after another. */
    fun updateAllMaps() {
        val regions = _updates.value
        if (regions.isEmpty()) return
        viewModelScope.launch {
            for (r in regions) {
                downloadRegion(r)
                // Wait for this one to finish before the next.
                while (_download.value is DownloadState.Checking || _download.value is DownloadState.Downloading) kotlinx.coroutines.delay(500)
                if (_download.value is DownloadState.Error) break
            }
        }
    }

    /** Bumped whenever new map files are installed so the map reloads its style. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** The newest map release found on GitHub, for showing its size before downloading. */
    private val _release = MutableStateFlow<MapStore.Release?>(null)
    val release: StateFlow<MapStore.Release?> = _release.asStateFlow()

    fun checkRelease() {
        refreshCatalog()
        if (_release.value != null) return
        viewModelScope.launch {
            _release.value = runCatching { store.findLatestRelease() }.getOrNull()
        }
    }

    private val _features = MutableStateFlow<Features?>(null)
    val features: StateFlow<Features?> = _features.asStateFlow()

    init {
        com.trailguidegps.app.data.MapRegion.names = _installed.value.map { MapStore.displayName(it) }
        loadFeatures()
    }

    private fun loadFeatures() {
        val s = stores
        if (s.isEmpty()) { _features.value = null; return }
        viewModelScope.launch {
            _features.value = withContext(Dispatchers.IO) { runCatching { Features.load(s) }.getOrNull() }
        }
    }

    /** Updates the downloaded states (or downloads the first state). */
    fun downloadMaps(force: Boolean = false) {
        if (_installed.value.size > 1 || (_updates.value.isNotEmpty() && !force)) { updateAllMaps(); return }
        downloadMapsSingle(force)
    }

    private fun downloadMapsSingle(force: Boolean) {
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
                val st = store
                st.download(release) { file, done, total ->
                    _download.value = DownloadState.Downloading(file, done, total, st.region)
                }
                _update.value = null
                mapsChanged()
                _download.value = DownloadState.UpToDate(release.tag)
            } catch (e: Exception) {
                _download.value = DownloadState.Error(friendly(e))
            }
        }
    }

    // ---- Waypoints, tracks and recording (phase 3) ----

    val waypoints = WaypointStore(app)
    val trailNotes = com.trailguidegps.app.data.TrailNoteStore(app)
    val photos = com.trailguidegps.app.data.PhotoStore(app)

    /** Copies in a photo (camera or gallery), shrunk. Returns its file name. */
    suspend fun addPhoto(uri: android.net.Uri): String? = withContext(Dispatchers.IO) { photos.add(uri) }

    fun deleteWaypoint(id: String) {
        waypoints.get(id)?.let { photos.delete(it.photos) }
        waypoints.delete(id)
        folders.put(com.trailguidegps.app.data.FolderStore.waypointKey(id), null)
    }

    fun deleteNote(id: String) {
        trailNotes.get(id)?.let { photos.delete(it.photos) }
        trailNotes.delete(id)
        folders.put(com.trailguidegps.app.data.FolderStore.noteKey(id), null)
    }

    /**
     * Makes an editable route from a recorded track: the line kept to within about 25 ft, with
     * planning points about every half mile, in the same folder as the track.
     */
    fun trackToRoute(id: String) {
        viewModelScope.launch {
            val (name, pts) = withContext(Dispatchers.IO) { tracks.load(id) } ?: return@launch
            if (pts.size < 2) { _message.value = "That track is too short to make a route."; return@launch }
            val r = withContext(Dispatchers.Default) {
                val coords = com.trailguidegps.app.data.RouteNear.simplify(pts.map { doubleArrayOf(it.lat, it.lon) }, 8.0)
                val dist = com.trailguidegps.app.data.RouteNear.lengthM(coords)
                SavedRoute(RouteStore.newId(), "$name (route)", System.currentTimeMillis(),
                    com.trailguidegps.app.data.RouteNear.planningPoints(coords), coords, RouteStats.compute(coords, dist, terrain), emptyList())
            }
            withContext(Dispatchers.IO) { routes.save(r) }
            folders.folderOf(com.trailguidegps.app.data.FolderStore.trackKey(id))?.let { folders.put(com.trailguidegps.app.data.FolderStore.routeKey(r.id), it.id) }
            setShown(_shownRoutes.value + r, r)
            _message.value = "Made \"${r.name}\". Tap Edit to change it."
        }
    }
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
        refreshCatalog()
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
        /** Pages to check, as (label, web address): official site, agency alerts, web search. */
        val links: List<Pair<String, String>> = emptyList(),
        /** A paragraph of advice (camping rules, burn hazards). */
        val note: String? = null,
        /** Set when this is one of your trail notes. */
        val noteId: String? = null,
        val photos: List<String> = emptyList(),
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
        val kindLabel = Features.KIND_LABELS[r.kind] ?: r.kind
        _selected.value = SelectedPlace(r.name, parts.joinToString(" · "), r.lat, r.lon, p?.osmId, p?.isWater == true,
            links = com.trailguidegps.app.data.PublicLand.placeLinks(r.name, kindLabel, p?.website, null))
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
            // Wildfires in and around the downloaded states (Oregon's area if none).
            val boxes = stores.mapNotNull { it.bbox() }
            val area = if (boxes.isEmpty()) listOf(-125.5, 41.0, -115.5, 47.0)
                else listOf(boxes.minOf { it[0] } - 0.5, boxes.minOf { it[1] } - 0.5, boxes.maxOf { it[2] } + 0.5, boxes.maxOf { it[3] } + 0.5)
            _firesStatus.value = runCatching { fires.refresh(area) }.fold(
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
    // ---- Quick tools: measure, go to, reverse, water along a route ----

    /** Points tapped while measuring, or null when not measuring. */
    private val _measure = MutableStateFlow<List<DoubleArray>?>(null)
    val measure: StateFlow<List<DoubleArray>?> = _measure.asStateFlow()
    fun startMeasure() { select(null); _measure.value = emptyList() }
    fun addMeasurePoint(lat: Double, lon: Double) { _measure.value = _measure.value?.plus(doubleArrayOf(lat, lon)) }
    fun undoMeasure() { _measure.value = _measure.value?.dropLast(1) }
    fun clearMeasure() { if (_measure.value != null) _measure.value = emptyList() }
    fun stopMeasure() { _measure.value = null }

    /** A place to head toward with the compass arrow. */
    data class GoTo(val name: String, val lat: Double, val lon: Double)
    private val _goTo = MutableStateFlow<GoTo?>(null)
    val goTo: StateFlow<GoTo?> = _goTo.asStateFlow()
    val compass = com.trailguidegps.app.location.Compass(app)
    fun startGoTo(name: String, lat: Double, lon: Double) {
        _goTo.value = GoTo(name, lat, lon)
        select(null)
    }
    fun stopGoTo() { _goTo.value = null }

    /** Saves a copy of a route running the other way (for the hike out), and shows it. */
    fun reverseRoute(id: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            val coords = r.coords.reversed()
            val pts = r.points.reversed()
            // Each point's flag says how the leg *into* it was drawn; reversed, that leg leads into the previous point.
            val flipped = pts.mapIndexed { i, p -> p.copy(followTrails = if (i == 0) true else pts[i - 1].followTrails) }
            val stats = withContext(Dispatchers.Default) { RouteStats.compute(coords, r.stats.distanceM, terrain) }
            val baseName = r.name.removeSuffix(" (reversed)")
            val copy = r.copy(
                id = RouteStore.newId(), name = if (r.name.endsWith(" (reversed)")) baseName else "$baseName (reversed)",
                createdAt = System.currentTimeMillis(), points = flipped, coords = coords, stats = stats, via = r.via.reversed(),
                camps = r.camps.map { it.copy(alongM = (r.stats.distanceM - it.alongM).coerceAtLeast(0.0)) }.sortedBy { it.alongM },
            )
            withContext(Dispatchers.IO) { routes.save(copy) }
            folders.folderOf(com.trailguidegps.app.data.FolderStore.routeKey(r.id))?.let {
                folders.put(com.trailguidegps.app.data.FolderStore.routeKey(copy.id), it.id)
            }
            setShown(_shownRoutes.value.filter { it.id != r.id } + copy, copy)
            _message.value = "Saved \"${copy.name}\": +%,d ft climb, −%,d ft descent.".format(
                (stats.gainM * com.trailguidegps.app.geo.Geo.FT_PER_M).toInt(), (stats.lossM * com.trailguidegps.app.geo.Geo.FT_PER_M).toInt())
        }
    }

    /** Water sources along a line, by mile, with the longest dry stretches. */
    fun waterPlan(coords: List<DoubleArray>, maxOffM: Double): com.trailguidegps.app.data.WaterPlan =
        com.trailguidegps.app.data.WaterPlanner.plan(coords, features.value?.allPoints.orEmpty(), maxOffM)

    // ---- Trip folders ----

    val folders = com.trailguidegps.app.data.FolderStore(app)
    private val folderPrefs = app.getSharedPreferences("folders_ui", android.content.Context.MODE_PRIVATE)
    /** The folder you last saved into, offered first next time (handy while planning a trip). */
    fun lastFolder(): String? = folderPrefs.getString("last", null)?.takeIf { id -> folders.folders.value.any { it.id == id } }
    fun rememberFolder(id: String?) { folderPrefs.edit().putString("last", id).apply() }

    /** Shows a folder's routes, its most recent track, and fits the map to everything in it. */
    fun showFolder(folderId: String) {
        viewModelScope.launch {
            val keys = folders.keysIn(folderId)
            val rs = withContext(Dispatchers.IO) { keys.filter { it.startsWith("route:") }.mapNotNull { routes.load(it.removePrefix("route:")) } }
            val ts = tracks.summaries.value.filter { "track:${it.id}" in keys }.sortedByDescending { it.startedAt }
            val wps = waypoints.items.value.filter { "wp:${it.id}" in keys }
            if (rs.isNotEmpty()) setShown((_shownRoutes.value.filter { s -> rs.none { it.id == s.id } } + rs), rs.first())
            val trackPts = ts.firstOrNull()?.let { t -> tracks.load(t.id)?.second?.also { _shownTrack.value = t.id to it } }.orEmpty()
            val pts = rs.flatMap { r -> r.coords.map { it[0] to it[1] } } + wps.map { it.lat to it.lon } + trackPts.map { it.lat to it.lon }
            if (pts.isNotEmpty()) focusOn(pts) else _message.value = "This folder is empty."
        }
    }

    fun stopRecording(name: String?, save: Boolean, folderId: String? = null) {
        val app = getApplication<Application>()
        val pts = TrackRecorder.finish(app)
        TrackRecordingService.stop(app)
        if (save && pts.size >= 2) {
            val summary = tracks.save(name?.takeIf { it.isNotBlank() } ?: defaultTrackName(pts.first().time), pts)
            folderId?.let { folders.put(com.trailguidegps.app.data.FolderStore.trackKey(summary.id), it); rememberFolder(it) }
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

    // ---- Trip plan to leave behind ----

    /**
     * Draws the route on a map snapshot (offline map files), writes the trip plan PDF,
     * and hands back what's needed to share it by email or text.
     */
    fun makeTripPlan(route: SavedRoute, form: com.trailguidegps.app.data.TripForm, onReady: (android.net.Uri, String, String) -> Unit) {
        val app = getApplication<Application>()
        com.trailguidegps.app.data.TripForm.remember(app, form)
        _message.value = "Making the trip plan…"
        snapshotRoute(route) { bitmap ->
            viewModelScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                        val safe = route.name.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "Route" }
                        val f = java.io.File(dir, "Trip plan - $safe.pdf")
                        com.trailguidegps.app.data.TripPlan.write(f, route, form, _pace.value, bitmap)
                        androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                    }
                }
                r.onSuccess { uri ->
                    onReady(uri, com.trailguidegps.app.data.TripPlan.subject(route, form),
                        com.trailguidegps.app.data.TripPlan.body(route, form, _pace.value))
                }.onFailure { _message.value = "Couldn't make the trip plan: ${it.message}" }
            }
        }
    }

    private var activeSnapshotter: org.maplibre.android.snapshotter.MapSnapshotter? = null

    /** A map picture of the route (topo, no satellite), with the route drawn on. Null if it can't be drawn. */
    private fun snapshotRoute(route: SavedRoute, done: (android.graphics.Bitmap?) -> Unit) {
        val app = getApplication<Application>()
        if (route.coords.size < 2 || stores.isEmpty()) { done(null); return }
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        route.coords.forEach { b.include(org.maplibre.android.geometry.LatLng(it[0], it[1])) }
        val raw = runCatching { b.build() }.getOrNull() ?: run { done(null); return }
        // A margin around the route so it doesn't touch the edges.
        val padLat = (raw.latitudeNorth - raw.latitudeSouth).coerceAtLeast(0.004) * 0.12
        val padLon = (raw.longitudeEast - raw.longitudeWest).coerceAtLeast(0.004) * 0.12
        val bounds = org.maplibre.android.geometry.LatLngBounds.from(
            raw.latitudeNorth + padLat, raw.longitudeEast + padLon, raw.latitudeSouth - padLat, raw.longitudeWest - padLon,
        )
        // The offline style, without the layers that are off by default or need a connection.
        val styleJson = org.json.JSONObject(com.trailguidegps.app.map.MapStyle.build(stores.map { com.trailguidegps.app.map.MapStyle.MapSource(it.region, it.dir) }))
        val layers = styleJson.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.getString("id")
            if (id.startsWith("satellite") || id.startsWith("land-fill") || id.startsWith("fire")) {
                l.put("layout", (l.optJSONObject("layout") ?: org.json.JSONObject()).put("visibility", "none"))
            }
        }
        val w = 532
        val h = 300
        val ratio = 2f
        val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(w, h)
            .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromJson(styleJson.toString()))
            .withRegion(bounds).withPixelRatio(ratio).withLogo(false)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(app, opts)
        activeSnapshotter = snap // keep it alive until it answers
        var finished = false
        val timeout = android.os.Handler(android.os.Looper.getMainLooper())
        timeout.postDelayed({ if (!finished) { finished = true; runCatching { snap.cancel() }; done(null) } }, 20_000)
        snap.start({ s ->
            if (finished) return@start
            finished = true
            val bmp = s.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(bmp)
            // pixelForLatLng may answer in map points rather than bitmap pixels; check against the middle.
            val mid = s.pixelForLatLng(bounds.center)
            val scale = if (kotlin.math.abs(mid.x * ratio - bmp.width / 2f) < kotlin.math.abs(mid.x - bmp.width / 2f)) ratio else 1f
            fun px(lat: Double, lon: Double) = s.pixelForLatLng(org.maplibre.android.geometry.LatLng(lat, lon)).let { android.graphics.PointF(it.x * scale, it.y * scale) }
            val path = android.graphics.Path()
            route.coords.forEachIndexed { i, c -> px(c[0], c[1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            val stroke = { color: Int, width: Float ->
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = width; this.color = color
                    strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
                }
            }
            canvas.drawPath(path, stroke(android.graphics.Color.WHITE, 12f))
            canvas.drawPath(path, stroke(android.graphics.Color.parseColor(route.color), 7f))
            val dot = { lat: Double, lon: Double, color: Int ->
                val p = px(lat, lon)
                canvas.drawCircle(p.x, p.y, 13f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
                canvas.drawCircle(p.x, p.y, 9f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            }
            route.coords.last().let { dot(it[0], it[1], android.graphics.Color.rgb(183, 28, 28)) }
            route.coords.first().let { dot(it[0], it[1], android.graphics.Color.rgb(46, 139, 87)) }
            // Camps, numbered by night.
            val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 26f; typeface = android.graphics.Typeface.DEFAULT_BOLD; color = android.graphics.Color.parseColor(route.color)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            route.camps.sortedBy { it.alongM }.forEachIndexed { i, c ->
                RouteStore.pointAt(route.coords, c.alongM)?.let { pt ->
                    val p = px(pt[0], pt[1])
                    canvas.drawCircle(p.x, p.y, 17f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
                    canvas.drawCircle(p.x, p.y, 17f, stroke(android.graphics.Color.parseColor(route.color), 5f))
                    canvas.drawText("${i + 1}", p.x, p.y + 9f, label)
                }
            }
            done(bmp)
        }, { _ -> if (!finished) { finished = true; done(null) } })
    }

    // ---- Backup and restore ----

    private val backupPrefs = app.getSharedPreferences("backup", android.content.Context.MODE_PRIVATE)
    private val _lastBackup = MutableStateFlow(backupPrefs.getLong("last", 0L).takeIf { it > 0 })
    /** When you last made a backup, or null. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    fun backupFileName() = "TrailGuide backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes the backup to a file you picked (this phone, Google Drive, OneDrive…). */
    fun backup(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val trk = tracks.summaries.value.mapNotNull { tracks.load(it.id) }
                    val rte = routes.summaries.value.mapNotNull { routes.load(it.id) }.map { it.name to it.coords }
                    val gpx = Gpx.write(waypoints.items.value, trk, rte)
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { com.trailguidegps.app.data.Backup.write(app, it, gpx) }
                }
            }
            r.onSuccess { c ->
                val now = System.currentTimeMillis()
                backupPrefs.edit().putLong("last", now).apply()
                _lastBackup.value = now
                _message.value = "Backed up ${c.routes} routes, ${c.tracks} tracks and ${c.waypoints} waypoints."
            }.onFailure { _message.value = "Backup failed: ${it.message ?: "couldn't write the file"}" }
        }
    }

    /**
     * Makes a backup file and hands it to Android's share sheet, so apps that don't
     * appear in the file picker (OneDrive, email) can take it.
     */
    fun shareBackup(onReady: (android.net.Uri) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                    val f = java.io.File(dir, backupFileName())
                    val trk = tracks.summaries.value.mapNotNull { tracks.load(it.id) }
                    val rte = routes.summaries.value.mapNotNull { routes.load(it.id) }.map { it.name to it.coords }
                    f.outputStream().use { com.trailguidegps.app.data.Backup.write(app, it, Gpx.write(waypoints.items.value, trk, rte)) }
                    androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                }
            }
            r.onSuccess { uri ->
                val now = System.currentTimeMillis()
                backupPrefs.edit().putLong("last", now).apply()
                _lastBackup.value = now
                onReady(uri)
            }.onFailure { _message.value = "Backup failed: ${it.message ?: "couldn't write the file"}" }
        }
    }

    /** Restores a backup file, adding to what's already on the phone. */
    fun restore(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use { com.trailguidegps.app.data.Backup.restore(app, it) } }
            }
            r.onSuccess { c ->
                waypoints.reload(); tracks.reload(); routes.reload(); waterNotes.reload(); folders.reload(); trailNotes.reload()
                _pace.value = Pace.load(app)
                _message.value = "Restored ${c.routes} routes, ${c.tracks} tracks, ${c.waypoints} waypoints and ${c.notes} notes."
            }.onFailure { _message.value = it.message ?: "That file couldn't be restored." }
        }
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
    fun exportGpx(fileName: String, wps0: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList(),
                  noteIds: List<String> = emptyList()): android.net.Uri? {
        val app = getApplication<Application>()
        // Trail notes go out as waypoints, so other apps and GPS units can show them.
        val wps = wps0 + noteIds.mapNotNull { trailNotes.get(it) }.map { n ->
            Waypoint(id = n.id, name = n.title, lat = n.lat, lon = n.lon, note = n.text, createdAt = n.createdAt)
        }
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
    /** One trail network per downloaded state. */
    @Volatile private var graphs: List<RouteGraph> = emptyList()
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
        val s = stores
        if (s.isEmpty()) { _routingReady.value = false; terrain = null; graphs = emptyList(); return }
        viewModelScope.launch(Dispatchers.IO) {
            _routingReady.value = null
            terrain = runCatching { Terrain(s.map { it.file("terrain.pmtiles") }) }.getOrNull()
            graphs = s.mapNotNull { st -> if (st.unpackRouting()) runCatching { RouteGraph.open(st.routingFile()) }.getOrNull() else null }
            _routingReady.value = graphs.isNotEmpty()
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
        /** The saved route being changed, or null for a new one. */
        val editing: SavedRoute? = null,
        /** A point tapped to move or remove it. */
        val selected: Int? = null,
        /** New points go before the first one instead of after the last. */
        val addAtStart: Boolean = false,
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

    fun cancelPlanning() {
        val editing = _plan.value.editing
        _plan.value = PlanState()
        // Leaving an edit without saving puts the original back on the map, unchanged.
        editing?.let { addRouteToMap(it.id) }
    }

    /**
     * Opens a saved route in the planner to change it. Its line is kept exactly as saved,
     * split at its points; only the parts you change are worked out again.
     */
    fun editRoute(id: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            // Imported routes have no planning points (or one per GPS fix): give them some along the line.
            val route = if (r.points.size < 2 || r.points.size > 120 || r.points.size == 2 && r.coords.size > 50) withContext(Dispatchers.Default) {
                val coords = if (r.coords.size > 2000) com.trailguidegps.app.data.RouteNear.simplify(r.coords, 5.0) else r.coords
                r.copy(coords = coords, points = com.trailguidegps.app.data.RouteNear.planningPoints(coords))
            } else r
            select(null)
            val legs = withContext(Dispatchers.Default) { splitIntoLegs(route) }
            _plan.value = PlanState(active = true, points = route.points, legs = legs, stats = route.stats,
                followTrails = route.points.last().followTrails, editing = route)
            hideRoute(id)
        }
    }

    /** The saved line cut at each planning point, with the route's trail/road mix shared out by length. */
    private fun splitIntoLegs(r: SavedRoute): List<RouteLeg> {
        val c = r.coords
        val geo = com.trailguidegps.app.geo.Geo::distanceM
        val idx = IntArray(r.points.size)
        for (j in 1 until r.points.size) {
            val p = r.points[j]
            if (j == r.points.size - 1) { idx[j] = c.size - 1; continue }
            var best = idx[j - 1]
            var bestD = Double.MAX_VALUE
            for (k in idx[j - 1] until c.size) {
                val d = geo(p.lat, p.lon, c[k][0], c[k][1])
                if (d < 3) { best = k; break }
                if (d < bestD) { bestD = d; best = k }
            }
            idx[j] = best
        }
        val t = r.terrain
        val tot = r.stats.distanceM.coerceAtLeast(1.0)
        return (1 until r.points.size).map { j ->
            val part = c.subList(idx[j - 1], (idx[j] + 1).coerceAtMost(c.size)).ifEmpty { listOf(c[idx[j - 1]]) }
            val len = (1 until part.size).sumOf { k -> geo(part[k - 1][0], part[k - 1][1], part[k][0], part[k][1]) }
            val share = len / tot
            RouteLeg(part, len, if (j == 1) r.via else emptyList(), r.points[j].followTrails,
                if (t != null) doubleArrayOf(t.trailM * share, t.trackM * share, t.roadM * share) else DoubleArray(3),
                if (j == 1) t?.sacMax ?: 0 else 0, (t?.sacRatedM ?: 0.0) * share)
        }
    }

    fun setAddAtStart(on: Boolean) { _plan.value = _plan.value.copy(addAtStart = on, selected = null) }
    fun selectPlanPoint(i: Int?) { _plan.value = _plan.value.copy(selected = i) }

    /** Runs a change to the points and legs under the planning lock, then recomputes the totals. */
    private fun changePlan(change: (PlanState) -> Pair<List<RoutePoint>, List<RouteLeg>>?) {
        viewModelScope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (!st.active) return@launch
                _plan.value = st.copy(busy = true)
                val result = withContext(Dispatchers.Default) { change(st) }
                if (result == null) { _plan.value = _plan.value.copy(busy = false); return@launch }
                val (pts, legs) = result
                val stats = withContext(Dispatchers.Default) { statsFor(legs) }
                _plan.value = _plan.value.copy(points = pts, legs = legs, stats = stats, busy = false, selected = null)
            } finally {
                planLock.unlock()
            }
        }
    }

    /** The leg from [a] to [b], drawn the way [b] says (trails or straight). */
    private fun legBetween(a: RoutePoint, b: RoutePoint): Pair<RoutePoint, RouteLeg> {
        val (pt, leg, _) = buildLeg(a, b.lat, b.lon, b.followTrails, b.waypointName)
        return pt to (leg ?: straightLeg(a, pt))
    }

    /** Moves point [i] to a new spot; the legs on either side are worked out again. */
    fun movePlanPoint(i: Int, lat: Double, lon: Double) = changePlan { st ->
        if (i !in st.points.indices) return@changePlan null
        val pts = st.points.toMutableList()
        val legs = st.legs.toMutableList()
        val mode = st.points[i].followTrails
        if (i == 0) {
            pts[0] = buildLeg(null, lat, lon, st.followTrails, null).first.copy(followTrails = true)
        } else {
            val (pt, leg) = legBetween(pts[i - 1], RoutePoint(lat, lon, mode))
            pts[i] = pt; legs[i - 1] = leg
        }
        if (i < pts.size - 1) {
            val (next, leg) = legBetween(pts[i], pts[i + 1])
            pts[i + 1] = next; legs[i] = leg
        }
        pts to legs
    }

    /** Takes out point [i]; its neighbours are joined directly. */
    fun removePlanPoint(i: Int) = changePlan { st ->
        if (i !in st.points.indices) return@changePlan null
        val pts = st.points.toMutableList()
        val legs = st.legs.toMutableList()
        when {
            pts.size == 1 -> { pts.clear(); legs.clear() }
            i == 0 -> { pts.removeAt(0); legs.removeAt(0); pts[0] = pts[0].copy(followTrails = true) }
            i == pts.size - 1 -> { pts.removeAt(i); legs.removeAt(i - 1) }
            else -> {
                pts.removeAt(i)
                legs.removeAt(i)          // the leg out of the removed point
                val (next, leg) = legBetween(pts[i - 1], pts[i])
                pts[i] = next; legs[i - 1] = leg
            }
        }
        pts to legs
    }

    /** Adds a point before the first one, extending the route at its start. */
    private fun addPlanPointAtStart(lat: Double, lon: Double, waypointName: String?) = changePlan { st ->
        val first = buildLeg(null, lat, lon, st.followTrails, waypointName).first.copy(followTrails = true)
        if (st.points.isEmpty()) return@changePlan listOf(first) to emptyList()
        val (old, leg) = legBetween(first, st.points[0].copy(followTrails = st.followTrails))
        (listOf(first, old) + st.points.drop(1)) to (listOf(leg) + st.legs)
    }

    fun setFollowTrails(on: Boolean) { _plan.value = _plan.value.copy(followTrails = on) }

    /** Adds a point; the leg to it follows trails when that mode is on and a connection exists. */
    fun addPlanPoint(lat: Double, lon: Double, waypointName: String? = null) {
        _plan.value.selected?.let { i -> movePlanPoint(i, lat, lon); return }
        if (_plan.value.addAtStart && _plan.value.points.isNotEmpty()) { addPlanPointAtStart(lat, lon, waypointName); return }
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
        if (_plan.value.addAtStart && _plan.value.points.size > 1) { removePlanPoint(0); return }
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
        // The trail network of the state these points are in (routes stop at state lines for now).
        val gs = graphs
        val g = gs.firstOrNull { it.snap(lat, lon) != null && (prev == null || it.snap(prev.lat, prev.lon) != null) }
            ?: gs.firstOrNull { it.snap(lat, lon) != null } ?: gs.firstOrNull()
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

    /** Saves the planned route; when editing, replaces the original unless [asNew]. */
    fun savePlan(name: String, color: String = com.trailguidegps.app.data.RouteColors.DEFAULT, folderId: String? = null, asNew: Boolean = false) {
        val st = _plan.value
        if (st.points.size < 2) return
        val old = st.editing?.takeIf { !asNew }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val coords = ArrayList<DoubleArray>()
                st.legs.forEach { leg -> leg.coords.forEach { c -> if (coords.lastOrNull()?.let { it[0] == c[0] && it[1] == c[1] } != true) coords += c } }
                SavedRoute(old?.id ?: RouteStore.newId(), name.ifBlank { "Route" }, old?.createdAt ?: System.currentTimeMillis(), st.points, coords,
                    st.stats, st.via, color, com.trailguidegps.app.data.RouteTerrain.of(st.legs),
                    // Camps stay if they're still on the route.
                    camps = old?.camps.orEmpty().filter { it.alongM < st.stats.distanceM - 200 })
                    .also { routes.save(it) }
            }
            _plan.value = PlanState()
            folderId?.let { folders.put(com.trailguidegps.app.data.FolderStore.routeKey(saved.id), it); rememberFolder(it) }
            setShown(_shownRoutes.value.filter { it.id != saved.id } + saved, saved)
            _message.value = if (old != null) "Saved changes to \"${saved.name}\"." else "Saved \"${saved.name}\"."
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
    /** Puts a route on the map alongside the others, without moving the map (from the Saved list). */
    fun addRouteToMap(id: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            setShown(_shownRoutes.value.filter { it.id != id } + r, r)
        }
    }

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

    /** Saves the nights out for a multi-day route (empty for a day hike). */
    fun setCamps(id: String, camps: List<com.trailguidegps.app.data.Camp>) {
        viewModelScope.launch {
            val sorted = camps.sortedBy { it.alongM }
            withContext(Dispatchers.IO) { routes.load(id)?.let { routes.save(it.copy(camps = sorted)) } }
            updateShown(id) { it.copy(camps = sorted) }
        }
    }

    /** Campsites and water along a route, for choosing camps. */
    suspend fun campSpots(route: SavedRoute): List<com.trailguidegps.app.data.CampSpot> = withContext(Dispatchers.Default) {
        features.value?.let { com.trailguidegps.app.data.TripDays.spots(route, it) }.orEmpty()
    }

    // ---- What's visible, and weather ----

    /** Named peaks visible from a spot, worked out offline. Null without terrain data. */
    suspend fun peaksFrom(lat: Double, lon: Double): com.trailguidegps.app.data.PeakView.Result? = withContext(Dispatchers.Default) {
        val t = terrain ?: return@withContext null
        val peaks = features.value?.allPoints.orEmpty().filter { it.kind == "peak" && it.name != null }
        runCatching {
            com.trailguidegps.app.data.PeakView.compute(lat, lon, peaks, t.maxZoom) { la, lo, z -> t.elevationM(la, lo, z) }
        }.getOrNull()
    }

    /** Where to get a forecast for a route: the start, the high point and each camp. */
    fun weatherSpots(r: SavedRoute): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        r.coords.firstOrNull()?.let { out += Triple("Start", it[0], it[1]) }
        r.stats.profile.maxByOrNull { it.second }?.let { (d, _) ->
            RouteStore.pointAt(r.coords, d)?.let { p ->
                if (out.none { com.trailguidegps.app.geo.Geo.distanceM(it.second, it.third, p[0], p[1]) < 2500 }) out += Triple("High point", p[0], p[1])
            }
        }
        r.camps.sortedBy { it.alongM }.forEachIndexed { i, c ->
            RouteStore.pointAt(r.coords, c.alongM)?.let { p ->
                if (out.none { com.trailguidegps.app.geo.Geo.distanceM(it.second, it.third, p[0], p[1]) < 2500 })
                    out += Triple("Night ${i + 1}" + (c.name?.let { ": $it" } ?: ""), p[0], p[1])
            }
        }
        return out
    }

    fun savedWeather(key: String) = com.trailguidegps.app.data.Weather.saved(getApplication(), key)

    /** Fetches forecasts for [spots] and keeps them under [key] for offline reading. */
    suspend fun fetchWeather(key: String, spots: List<Triple<String, Double, Double>>): Result<List<com.trailguidegps.app.data.Weather.Forecast>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val list = spots.map { (label, la, lo) -> com.trailguidegps.app.data.Weather.fetch(label, la, lo, elevationAt(la, lo)) }
                com.trailguidegps.app.data.Weather.save(getApplication(), key, list)
                list
            }.recoverCatching { e ->
                throw java.io.IOException(when (e) {
                    is java.net.UnknownHostException -> "No internet connection. The forecast needs Wi-Fi or data."
                    is java.net.SocketTimeoutException -> "The weather service didn't answer in time. Try again."
                    else -> e.message ?: "Couldn't get the forecast."
                })
            }
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
