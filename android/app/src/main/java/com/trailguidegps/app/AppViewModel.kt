package com.trailguidegps.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Gpx
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.data.Pace
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
import com.trailguidegps.app.data.RouteSummary
import com.trailguidegps.app.data.WaypointStore
import com.trailguidegps.app.location.LocationTracker
import com.trailguidegps.app.record.Recording
import com.trailguidegps.app.record.TrackRecorder
import com.trailguidegps.app.record.TrackRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.trailguidegps.app.record.MapDownloads

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

    // The work is in MapLibrary; these pass through to it.
    private val maps = com.trailguidegps.app.data.MapLibrary(app, viewModelScope, { settings.value.value.onlineStates }, { friendly(it) }) {
        loadFeatures()
        loadRouting()
    }
    /** States downloaded on the phone, Oregon (or the first) first. */
    val installed get() = maps.installed
    val stores: List<MapStore> get() = maps.stores
    /** The first downloaded state (for single-state details like the build date). */
    val store: MapStore get() = maps.store
    val mapsReady get() = maps.mapsReady
    fun useOnlineMap() = maps.useOnlineMap()
    val catalog get() = maps.catalog
    val catalogError get() = maps.catalogError
    fun refreshCatalog() = maps.refreshCatalog()
    fun mapSources() = maps.mapSources()
    fun onlineSearchRegions() = maps.onlineSearchRegions()
    fun onlineSearchBytes(region: String) = maps.onlineSearchBytes(region)
    fun hasOnlineSearch(region: String) = maps.hasOnlineSearch(region)
    suspend fun onlineSearch(region: String): Features = maps.onlineSearch(region)
    fun regionAt(lat: Double, lon: Double) = maps.regionAt(lat, lon)
    fun downloadRegion(region: String) = maps.downloadRegion(region)
    fun deleteRegion(region: String) = maps.deleteRegion(region)
    val updates get() = maps.updates
    fun updateAllMaps() = maps.updateAllMaps()
    val mapVersion get() = maps.mapVersion
    val download get() = maps.download
    val release get() = maps.release
    fun checkRelease() = maps.checkRelease()
    fun downloadMaps(force: Boolean = false) = maps.downloadMaps(force)
    /** A newer map release than the one installed, and what it would cost to download. */
    val update get() = maps.update

    /** Turns streaming of not-downloaded states on or off, and redraws the map. */
    fun setOnlineStates(on: Boolean) {
        settings.update { it.copy(onlineStates = on) }
        maps.redraw()
    }

    private val _features = MutableStateFlow<Features?>(null)
    val features: StateFlow<Features?> = _features.asStateFlow()

    init {
        loadFeatures()
        // Trip plans, GPX files and backups made for sharing don't linger: clear ones over a day old.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                java.io.File(app.cacheDir, "exports").listFiles()?.forEach { f ->
                    if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete()
                }
            }
        }
    }

    private fun loadFeatures() {
        val s = stores
        if (s.isEmpty()) { _features.value = null; return }
        viewModelScope.launch {
            _features.value = withContext(Dispatchers.IO) { runCatching { Features.load(s) }.getOrNull() }
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

    // Recording a track and following a route: the work is in RecordingTools.
    private val recorder = com.trailguidegps.app.record.RecordingTools(app, tracks, com.trailguidegps.app.data.SettingsStore.get(app), { _message.value = it }) { id, pts, folderId ->
        folderId?.let { folders.put(com.trailguidegps.app.data.FolderStore.trackKey(id), it); rememberFolder(it) }
        _shownTrack.value = id to pts
    }

    init { recorder.recoverAtLaunch() }

    fun focusOn(points: List<Pair<Double, Double>>) { if (points.isNotEmpty()) _focus.value = Focus(points) }

    // ---- Map update notice ----

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
        // Found in a state that isn't downloaded: the map is blank there until it is.
        val region = p?.region ?: regionAt(r.lat, r.lon)
        if (!com.trailguidegps.app.BuildConfig.ONLINE_MAP && region != null && region !in maps.installed.value) {
            _message.value = "${com.trailguidegps.app.data.MapStore.displayName(region)} isn't downloaded yet. Download it in Maps for each state to see the map here."
        }
    }

    /** A spot typed into search as coordinates: show it with the usual place card (Go to, Pin, Weather…). */
    fun openCoordinates(lat: Double, lon: Double, near: String?) {
        val u = com.trailguidegps.app.geo.Geo.utm(lat, lon)
        val parts = mutableListOf("Coordinates")
        elevationAt(lat, lon)?.let { parts += com.trailguidegps.app.geo.Geo.formatFeet(it) }
        near?.let { parts += it }
        parts += com.trailguidegps.app.geo.Geo.dms(lat, lon)
        parts += "UTM ${u.line1} ${u.line2}"
        _selected.value = SelectedPlace("%.5f, %.5f".format(java.util.Locale.US, lat, lon), parts.joinToString(" · "), lat, lon)
        focusOn(listOf(lat to lon))
        val region = regionAt(lat, lon)
        if (!com.trailguidegps.app.BuildConfig.ONLINE_MAP && region != null && region !in maps.installed.value) {
            _message.value = "${com.trailguidegps.app.data.MapStore.displayName(region)} isn't downloaded yet. Download it in Maps for each state to see the map here."
        } else if (region == null && maps.catalog.value.isNotEmpty()) {
            _message.value = "That spot is outside the maps (they cover the U.S.). Check the numbers, or whether it should be West or South."
        }
    }

    fun showTrack(id: String) {
        val (_, pts) = tracks.load(id) ?: return
        _shownTrack.value = id to pts
        focusOn(pts.map { it.lat to it.lon })
    }

    fun hideTrack() { _shownTrack.value = null }

    fun startRecording() = recorder.start()

    fun setPaused(paused: Boolean) = recorder.setPaused(paused)

    // ---- Settings and following a route (phase 6) ----

    val settings = com.trailguidegps.app.data.SettingsStore.get(app)
    val following: StateFlow<com.trailguidegps.app.route.Following?> = com.trailguidegps.app.route.RouteFollower.state

    /** Follow the route in the bottom panel: progress, what's left, and off-route alerts. */
    fun startFollowing() { _shownRoute.value?.let { recorder.startFollowing(it) } }

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

    fun stopFollowing() = recorder.stopFollowing()

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

    /**
     * Flips a saved route to run the other way (for the hike out) and saves it in place,
     * so reversing never piles up copies. Reversing again puts it back.
     */
    fun reverseRoute(id: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            if (r.outAndBack) { _message.value = "\"${r.name}\" comes back the same way, so it's the same both ways."; return@launch }
            val coords = r.coords.reversed()
            val pts = r.points.reversed()
            // Each point's flag says how the leg *into* it was drawn; reversed, that leg leads into the previous point.
            val flipped = pts.mapIndexed { i, p -> p.copy(followTrails = if (i == 0) true else pts[i - 1].followTrails) }
            val stats = withContext(Dispatchers.Default) { RouteStats.compute(coords, r.stats.distanceM, terrain) }
            val turned = r.copy(
                // Older versions saved a separate "(reversed)" copy; drop that tag when flipping.
                name = r.name.removeSuffix(" (reversed)"),
                points = flipped, coords = coords, stats = stats, via = r.via.reversed(),
                camps = r.camps.map { it.copy(alongM = (r.stats.distanceM - it.alongM).coerceAtLeast(0.0)) }.sortedBy { it.alongM },
            )
            withContext(Dispatchers.IO) { routes.save(turned) }
            setShown(_shownRoutes.value.filter { it.id != r.id } + turned, turned)
            val start = turned.via.firstOrNull()?.takeIf { it.isNotBlank() }
            _message.value = "Reversed \"${turned.name}\"" + (start?.let { ", now starting on $it" } ?: "") +
                ": +%,d ft climb, −%,d ft descent.".format(
                    (stats.gainM * com.trailguidegps.app.geo.Geo.FT_PER_M).toInt(), (stats.lossM * com.trailguidegps.app.geo.Geo.FT_PER_M).toInt())
        }
    }

    /** The quickest ways out from a spot by trail: nearest trailheads and the nearest road. */
    suspend fun waysOut(lat: Double, lon: Double): com.trailguidegps.app.route.WaysOut.Result = withContext(Dispatchers.Default) {
        com.trailguidegps.app.route.WaysOut.find(graphs, features.value, terrain, _pace.value, lat, lon)
    }

    /** Saves a way out as a route and shows it, so it can be followed. */
    fun showWayOut(w: com.trailguidegps.app.route.WayOut) {
        viewModelScope.launch {
            val c = w.coords
            val leg = com.trailguidegps.app.data.RouteLeg(c, w.stats.distanceM, w.leg.names, true, w.leg.kindM, w.leg.sacMax, w.leg.sacRatedM)
            val r = SavedRoute(RouteStore.newId(), "Way out to ${w.name}", System.currentTimeMillis(),
                listOf(com.trailguidegps.app.data.RoutePoint(c.first()[0], c.first()[1], true), com.trailguidegps.app.data.RoutePoint(c.last()[0], c.last()[1], true)),
                c, w.stats, w.leg.names, terrain = com.trailguidegps.app.data.RouteTerrain.of(listOf(leg)))
            withContext(Dispatchers.IO) { routes.save(r) }
            select(null)
            setShown(_shownRoutes.value + r, r)
            focusOn(c.map { it[0] to it[1] })
            _message.value = "Saved \"${r.name}\". Tap Follow to be guided along it."
        }
    }

    /** Water sources along a line, by mile, with the longest dry stretches. */
    // ---- Water along a route and what to carry (the logic lives in WaterTools) ----

    private val water = com.trailguidegps.app.data.WaterTools(app, { features.value }, { _pace.value }, { savedWeather(it) })
    fun waterPlan(coords: List<DoubleArray>, maxOffM: Double) = water.plan(coords, maxOffM)
    val waterRate get() = water.rate
    fun setWaterRate(r: com.trailguidegps.app.data.WaterCarry.Rate) = water.setRate(r)
    val waterHidden get() = water.hidden
    fun setWaterHidden(v: Boolean) = water.setHidden(v)
    fun waterHeat(route: SavedRoute) = water.heat(route)
    fun setWaterHeat(routeId: String, heat: com.trailguidegps.app.data.WaterCarry.Heat?) = water.setHeat(routeId, heat)
    fun waterDays(route: SavedRoute, camps: List<com.trailguidegps.app.data.Camp>, heat: com.trailguidegps.app.data.WaterCarry.Heat, reliableOnly: Boolean) =
        water.days(route, camps, heat, reliableOnly)
    private fun tripWater(route: SavedRoute) = water.forTripPlan(route)

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

    fun stopRecording(name: String?, save: Boolean, folderId: String? = null) = recorder.stop(name, save, folderId)

    fun defaultTrackName(startMs: Long = System.currentTimeMillis()) = recorder.defaultTrackName(startMs)

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
        com.trailguidegps.app.map.RouteSnapshot.make(app, stores, route) { bitmap ->
            viewModelScope.launch {
                val water = withContext(Dispatchers.Default) {
                    runCatching { tripWater(route) }.getOrDefault(emptyMap<Int, String>() to null)
                }
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                        val safe = route.name.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "Route" }
                        val f = java.io.File(dir, "Trip plan - $safe.pdf")
                        com.trailguidegps.app.data.TripPlan.write(f, route, form, _pace.value, bitmap, areaStore.areas.value["r:${route.id}"]?.line, water)
                        // Shared straight from the app's own folder: no copy is left in Downloads.
                        androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                    }
                }
                r.onSuccess { uri ->
                    onReady(uri, com.trailguidegps.app.data.TripPlan.subject(route, form),
                        com.trailguidegps.app.data.TripPlan.body(route, form, _pace.value, areaStore.areas.value["r:${route.id}"]?.line, water))
                }.onFailure { _message.value = "Couldn't make the trip plan: ${it.message}" }
            }
        }
    }

    // ---- Backup, restore and GPX in and out (the work is in DataTransfer) ----

    private val transfer by lazy {
        com.trailguidegps.app.data.DataTransfer(app, viewModelScope, waypoints, tracks, routes, waterNotes, folders, trailNotes,
            message = { _message.value = it }, afterRestore = { _pace.value = Pace.load(app) }, terrain = { terrain }, focusOn = { focusOn(it) })
    }
    /** When you last made a backup, or null. */
    val lastBackup get() = transfer.lastBackup
    fun backupFileName() = transfer.backupFileName()
    fun backup(uri: android.net.Uri) = transfer.backup(uri)
    fun shareBackup(onReady: (android.net.Uri) -> Unit) = transfer.shareBackup(onReady)
    fun restore(uri: android.net.Uri) = transfer.restore(uri)
    fun importGpx(uri: android.net.Uri) = transfer.importGpx(uri)
    fun exportGpx(fileName: String, wps0: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList(),
                  noteIds: List<String> = emptyList()): android.net.Uri? = transfer.exportGpx(fileName, wps0, trackIds, routeIds, noteIds)

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

    init {
        // The app was killed while following a route: pick it back up (the service does the same if
        // Android restarts it first).
        recorder.resumeFollowing { routes.load(it) }
    }

    // ---- Where each saved item is ("Mt. Hood National Forest · 12 mi SE of Estacada"); the work is in AreaLabeler ----
    private val areaLabeler = com.trailguidegps.app.data.AreaLabeler(com.trailguidegps.app.data.AreaStore(app), { stores }, { _features.value })
    val areaStore get() = areaLabeler.store
    val areas get() = areaLabeler.areas

    init {
        areaLabeler.start(viewModelScope, routes, tracks, waypoints, trailNotes,
            kotlinx.coroutines.flow.combine(maps.installed, _features) { i, f -> i.sorted().joinToString(",") + "|" + (f != null) })
    }

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

    // The planner itself is in RoutePlanner; these pass through to it.
    private val planner = com.trailguidegps.app.route.RoutePlanner(viewModelScope, routes, { graphs }, { terrain }, { _message.value = it },
        { select(null) }, { addRouteToMap(it) }, { hideRoute(it) }) { saved, folderId ->
        folderId?.let { folders.put(com.trailguidegps.app.data.FolderStore.routeKey(saved.id), it); rememberFolder(it) }
        setShown(_shownRoutes.value.filter { it.id != saved.id } + saved, saved)
    }
    val plan: StateFlow<com.trailguidegps.app.route.PlanState> get() = planner.plan

    fun startPlanning() = planner.startPlanning()
    fun cancelPlanning() = planner.cancelPlanning()
    /** Opens a saved route in the planner to change it. */
    fun editRoute(id: String) = planner.editRoute(id)
    fun setAddAtStart(on: Boolean) = planner.setAddAtStart(on)
    fun selectPlanPoint(i: Int?) = planner.selectPlanPoint(i)
    fun movePlanPoint(i: Int, lat: Double, lon: Double) = planner.movePlanPoint(i, lat, lon)
    fun removePlanPoint(i: Int) = planner.removePlanPoint(i)
    fun setFollowTrails(on: Boolean) = planner.setFollowTrails(on)
    fun setPlanOutAndBack(on: Boolean) = planner.setPlanOutAndBack(on)
    fun addPlanPoint(lat: Double, lon: Double, waypointName: String? = null) = planner.addPlanPoint(lat, lon, waypointName)
    fun undoPlanPoint() = planner.undoPlanPoint()
    /** Saves the planned route; when editing, replaces the original unless [asNew]. */
    fun savePlan(name: String, color: String = com.trailguidegps.app.data.RouteColors.DEFAULT, folderId: String? = null, asNew: Boolean = false) =
        planner.savePlan(name, color, folderId, asNew)

    /**
     * Makes a saved route an out-and-back (adds the way back along the same path) or one-way again
     * (drops the way back). Camps on the way back are dropped when it's taken off.
     */
    fun setRouteOutAndBack(id: String, on: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            if (r.outAndBack == on) return@launch
            val updated = withContext(Dispatchers.Default) {
                fun scaled(t: com.trailguidegps.app.data.RouteTerrain?, k: Double) = t?.copy(trailM = t.trailM * k, trackM = t.trackM * k,
                    roadM = t.roadM * k, offTrailM = t.offTrailM * k, sacRatedM = t.sacRatedM * k)
                if (on) {
                    val coords = r.coords + r.coords.reversed().drop(1)
                    r.copy(coords = coords, stats = RouteStats.compute(coords, r.stats.distanceM * 2, terrain), terrain = scaled(r.terrain, 2.0),
                        outAndBack = true, outCount = r.coords.size, via = r.via)
                } else {
                    val n = r.outCount.takeIf { it in 2..r.coords.size } ?: ((r.coords.size + 1) / 2)
                    val coords = r.coords.take(n)
                    val dist = r.turnM ?: (r.stats.distanceM / 2)
                    r.copy(coords = coords, stats = RouteStats.compute(coords, dist, terrain), terrain = scaled(r.terrain, 0.5),
                        outAndBack = false, outCount = 0, camps = r.camps.filter { it.alongM < dist - 200 })
                }
            }
            withContext(Dispatchers.IO) { routes.save(updated) }
            updateShown(id) { updated }
            _message.value = if (on) "\"${r.name}\" now comes back the same way: %.1f mi round trip.".format(updated.stats.distanceM / com.trailguidegps.app.geo.Geo.M_PER_MI)
                else "\"${r.name}\" is one way again: %.1f mi.".format(updated.stats.distanceM / com.trailguidegps.app.geo.Geo.M_PER_MI)
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

    /** Saves your notes on a route (blank clears them). */
    /** Saves when you plan to do a route (0, 0 clears it). Used by Days, water, the after-dark warning and the trip plan. */
    fun setTripDates(id: String, start: Long, end: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { routes.setTripDates(id, start, end) }
            updateShown(id) { it.copy(tripStart = start, tripEnd = end) }
        }
    }

    fun setRouteNotes(id: String, notes: String) {
        val t = notes.trim()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { routes.setNotes(id, t) }
            updateShown(id) { it.copy(notes = t) }
        }
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
