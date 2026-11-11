package com.trailguidegps.app.data

import android.content.Context
import com.trailguidegps.app.DownloadState
import com.trailguidegps.app.record.MapDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A state available to download: its newest release, name and area. */
data class RegionInfo(val region: String, val title: String, val release: MapStore.Release, val bbox: DoubleArray?, val built: String?) {
    val name get() = MapStore.displayName(region)
}

/** A newer map release than the one installed, and what it would cost to download. */
data class MapUpdate(val tag: String, val bytes: Long, val built: String)

/**
 * The maps on the phone and the ones available: which states are downloaded, the catalog of
 * states with their sizes, downloading, updating and deleting, and searching states that
 * aren't downloaded. [reload] is called when the downloaded maps change, so search and
 * routing can be read again.
 */
class MapLibrary(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The "show other states online" setting. */
    private val onlineStates: () -> Boolean,
    private val friendly: (Exception) -> String,
    private val reload: () -> Unit,
) {
    /** States downloaded on the phone, Oregon (or the first) first. */
    private val _installed = MutableStateFlow(MapStore.installedRegions(ctx))
    val installed: StateFlow<List<String>> = _installed.asStateFlow()
    val stores: List<MapStore> get() = _installed.value.map { MapStore(ctx, it) }
    /** The first downloaded state (for single-state details like the build date). */
    val store: MapStore get() = stores.firstOrNull() ?: MapStore(ctx)

    private val mapPrefs = ctx.getSharedPreferences("maps", android.content.Context.MODE_PRIVATE)
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

    private val _catalog = MutableStateFlow(loadCachedCatalog())
    val catalog: StateFlow<List<RegionInfo>> = _catalog.asStateFlow()
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** Looks up which states have maps (online), with their sizes and areas; kept for offline use. */
    fun refreshCatalog() {
        scope.launch {
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
        val arr = org.json.JSONArray(catalogFile().readText())
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
        // The online map of other states is off for now (offline only); see BuildConfig.ONLINE_MAP.
        val online = com.trailguidegps.app.BuildConfig.ONLINE_MAP
        val remote = if (!online || (!onlineStates() && _installed.value.isNotEmpty())) emptyList() else _catalog.value.filter { it.region !in _installed.value }.map { r ->
            val server = com.trailguidegps.app.BuildConfig.TILES_URL
            if (server.isNotBlank()) {
                com.trailguidegps.app.map.MapStyle.MapSource(r.region, null, "$server/${r.region}", r.release.assets.map { it.name }.toSet(), tileServer = true)
            } else {
                com.trailguidegps.app.map.MapStyle.MapSource(r.region, null,
                    "https://github.com/${com.trailguidegps.app.BuildConfig.MAPS_REPO}/releases/download/${r.release.tag}",
                    r.release.assets.map { it.name }.toSet())
            }
        }
        return local + remote
    }

    // ---- Searching states that aren't downloaded ----

    /** The last couple of online search lists read into memory. */
    private val onlineIndex = object : LinkedHashMap<String, Features>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Features>?) = size > 2
    }

    /** States that can be searched without downloading them (their map build has a search list). */
    fun onlineSearchRegions(): List<String> =
        _catalog.value.filter { it.region !in _installed.value && it.release.assets.any { a -> a.name == MapStore.SEARCH_FILE } }.map { it.region }

    /** Size of a state's search list, for "about 2 MB, once". */
    fun onlineSearchBytes(region: String): Long? =
        _catalog.value.firstOrNull { it.region == region }?.release?.assets?.firstOrNull { it.name == MapStore.SEARCH_FILE }?.size

    /** True when the state's search list is already on the phone. */
    fun hasOnlineSearch(region: String): Boolean = java.io.File(java.io.File(ctx.filesDir, "search"), "$region.json.gz").exists()

    /**
     * Search data for a state that isn't downloaded: its search list is fetched once from the
     * map release (a few MB) and kept, so it works without signal afterwards. A newer list is
     * fetched when the state's maps are rebuilt; if that fails, the kept one is used.
     */
    suspend fun onlineSearch(region: String): Features = withContext(Dispatchers.IO) {
        synchronized(onlineIndex) { onlineIndex[region] }?.let { return@withContext it }
        val dir = java.io.File(ctx.filesDir, "search").apply { mkdirs() }
        val file = java.io.File(dir, "$region.json.gz")
        val tagFile = java.io.File(dir, "$region.tag")
        val info = _catalog.value.firstOrNull { it.region == region }
        val asset = info?.release?.assets?.firstOrNull { it.name == MapStore.SEARCH_FILE }
        val have = file.exists() && tagFile.exists() && tagFile.readText().trim() == info?.release?.tag
        if (!have) {
            try {
                if (info == null || asset == null) error("${MapStore.displayName(region)} can't be searched online yet. Download its maps to search it.")
                val tmp = java.io.File(dir, "$region.json.gz.tmp")
                val conn = (java.net.URL(asset.url).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TrailGuideGPS")
                    connectTimeout = 15000
                    readTimeout = 60000
                }
                try {
                    if (conn.responseCode != 200) error("Couldn't get the search list (HTTP ${conn.responseCode})")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                } finally {
                    conn.disconnect()
                }
                if (tmp.length() != asset.size) { tmp.delete(); error("The search list didn't finish downloading. Try again.") }
                file.delete()
                tmp.renameTo(file)
                tagFile.writeText(info.release.tag)
            } catch (e: Exception) {
                if (!file.exists()) throw e
            }
        }
        Features.loadSearchIndex(file, region).also { f -> synchronized(onlineIndex) { onlineIndex[region] = f } }
    }

    /** The state whose area contains a spot, from the catalog (or the downloaded manifests). */
    fun regionAt(lat: Double, lon: Double): String? {
        val boxes = _catalog.value.mapNotNull { r -> r.bbox?.let { r.region to it } } + stores.mapNotNull { s -> s.bbox()?.let { s.region to it } }
        // Smallest box first, so a point in a small state isn't claimed by a big neighbour's rectangle.
        return boxes.filter { (_, b) -> lon >= b[0] && lat >= b[1] && lon <= b[2] && lat <= b[3] }
            .minByOrNull { (_, b) -> (b[2] - b[0]) * (b[3] - b[1]) }?.first
    }

    /** Downloads (or updates) one state's maps, in the background download service. */
    fun downloadRegion(region: String) {
        if (MapDownloads.busy) return
        MapDownloads.start(ctx, listOf(region), _catalog.value.associate { it.region to it.release })
    }

    /** Removes one state's maps from the phone. */
    fun deleteRegion(region: String) {
        scope.launch {
            withContext(Dispatchers.IO) { MapStore(ctx, region).delete() }
            mapsChanged()
        }
    }

    private fun mapsChanged() {
        _installed.value = MapStore.installedRegions(ctx)
        com.trailguidegps.app.data.MapRegion.names = _installed.value.map { MapStore.displayName(it) }
        _mapsReady.value = _installed.value.isNotEmpty() || _onlineOnly.value
        _mapVersion.value += 1
        reload()
        computeUpdates()
        cleanOldMaps(delayMs = 20_000)
    }

    /** Frees the space of replaced map versions once the map has reloaded with the new ones. */
    private fun cleanOldMaps(delayMs: Long) {
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (MapDownloads.busy) return@launch
            withContext(Dispatchers.IO) { stores.forEach { runCatching { it.cleanOld() } } }
        }
    }

    /** Downloaded states with a newer map release, and how much the updates would download. */
    private val _updates = MutableStateFlow<List<String>>(emptyList())
    val updates: StateFlow<List<String>> = _updates.asStateFlow()
    private fun computeUpdates() {
        val cat = _catalog.value.associateBy { it.region }
        val need = stores.filter { s -> cat[s.region]?.let { it.release.tag != s.installedTag() } == true }
        _updates.value = need.map { it.region }
        scope.launch {
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
        if (regions.isEmpty() || MapDownloads.busy) return
        MapDownloads.start(ctx, regions, _catalog.value.associate { it.region to it.release })
    }

    /** Bumped whenever new map files are installed so the map reloads its style. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()
    fun redraw() { _mapVersion.value += 1 }

    private val _update = MutableStateFlow<MapUpdate?>(null)
    val update: StateFlow<MapUpdate?> = _update.asStateFlow()

    /** Map download progress, from the download service (it outlives this screen). */
    val download: StateFlow<DownloadState> = MapDownloads.state

    /** The newest map release found on GitHub, for showing its size before downloading. */
    private val _release = MutableStateFlow<MapStore.Release?>(null)
    val release: StateFlow<MapStore.Release?> = _release.asStateFlow()

    fun checkRelease() {
        refreshCatalog()
        if (_release.value != null) return
        scope.launch {
            _release.value = runCatching { store.findLatestRelease() }.getOrNull()
        }
    }

    /** Updates the downloaded states (or downloads the first state). */
    fun downloadMaps(force: Boolean = false) {
        if (_installed.value.size > 1 || (_updates.value.isNotEmpty() && !force)) { updateAllMaps(); return }
        downloadMapsSingle(force)
    }

    private fun downloadMapsSingle(force: Boolean) {
        if (MapDownloads.busy) return
        scope.launch {
            val release = runCatching { store.findLatestRelease() }.getOrNull()?.also { _release.value = it }
            if (!force && release != null && store.isReady() && store.installedTag() == release.tag) {
                _update.value = null
                return@launch
            }
            MapDownloads.start(ctx, listOf(store.region), release?.let { mapOf(store.region to it) } ?: emptyMap())
        }
    }

    init {
        // When the download service installs a state, reload the maps, search and routing.
        scope.launch {
            var seen = MapDownloads.installed.value
            MapDownloads.installed.collect { n -> if (n != seen) { seen = n; _update.value = null; mapsChanged() } }
        }
        MapRegion.names = _installed.value.map { MapStore.displayName(it) }
        cleanOldMaps(delayMs = 5_000)
    }
}
