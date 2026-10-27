package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A saved satellite area. Bounds in degrees; [status] is queued, downloading, paused, ready or failed. */
data class SatArea(
    val id: String,
    val name: String,
    val west: Double, val south: Double, val east: Double, val north: Double,
    val maxZoom: Int,
    val createdAt: Long,
    val status: String,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val note: String? = null,
    /** The area this one is replacing (when its boundary was changed); removed once this one is ready. */
    val replaces: String? = null,
) {
    val ready get() = status == STATUS_READY

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Satellite imagery saved for offline use, one PMTiles file per area. Imagery
 * is USGS National Map orthoimagery (public domain), down to zoom 16 (about
 * 6 ft per pixel). Tiles already saved in another area are reused, so
 * changing an area's boundary only downloads the new parts.
 */
class SatelliteStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "satellite").apply { mkdirs() }
    private val _areas = MutableStateFlow(scan())
    val areas: StateFlow<List<SatArea>> = _areas.asStateFlow()
    /** Goes up whenever a finished area appears or disappears, so the map reloads its imagery. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    @Volatile private var pauseRequested: String? = null

    fun file(a: SatArea) = File(dir, "${a.id}.pmtiles")
    private fun meta(id: String) = File(dir, "$id.json")
    private fun parts(id: String) = File(dir, "$id.parts")

    private fun scan(): List<SatArea> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    private fun save(a: SatArea) {
        val o = JSONObject().put("id", a.id).put("name", a.name).put("west", a.west).put("south", a.south)
            .put("east", a.east).put("north", a.north).put("maxZoom", a.maxZoom).put("createdAt", a.createdAt)
            .put("status", a.status).put("done", a.done).put("total", a.total).put("bytes", a.bytes)
            .put("note", a.note ?: JSONObject.NULL).put("replaces", a.replaces ?: JSONObject.NULL)
        val tmp = File(dir, "${a.id}.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(meta(a.id))
        _areas.value = _areas.value.filter { it.id != a.id }.plus(a).sortedByDescending { it.createdAt }
    }

    private fun fromJson(o: JSONObject) = SatArea(
        o.getString("id"), o.getString("name"), o.getDouble("west"), o.getDouble("south"), o.getDouble("east"),
        o.getDouble("north"), o.getInt("maxZoom"), o.getLong("createdAt"), o.getString("status"),
        o.optInt("done"), o.optInt("total"), o.optLong("bytes"),
        o.optString("note").takeIf { !o.isNull("note") && it.isNotBlank() },
        o.optString("replaces").takeIf { !o.isNull("replaces") && it.isNotBlank() },
    )

    fun get(id: String) = _areas.value.firstOrNull { it.id == id }

    /** Adds an area to download. With [replaces], it's a new boundary for that area and keeps its name. */
    fun add(name: String, west: Double, south: Double, east: Double, north: Double, maxZoom: Int, replaces: String? = null): SatArea {
        val total = PmTilesWriter.countFor(west, south, east, north, MIN_ZOOM, maxZoom).toInt()
        val a = SatArea(UUID.randomUUID().toString(), name, west, south, east, north, maxZoom, System.currentTimeMillis(),
            SatArea.STATUS_QUEUED, 0, total, replaces = replaces)
        save(a)
        return a
    }

    fun rename(id: String, name: String) { get(id)?.let { save(it.copy(name = name)) } }

    fun delete(id: String) {
        val a = get(id) ?: return
        if (_areas.value.any { it.id == id && it.status == SatArea.STATUS_DOWNLOADING }) pauseRequested = id
        file(a).delete()
        parts(id).deleteRecursively()
        meta(id).delete()
        _areas.value = _areas.value.filter { it.id != id }
        if (a.ready) _version.value += 1
    }

    fun pause(id: String) {
        pauseRequested = id
        get(id)?.takeIf { it.status != SatArea.STATUS_READY }?.let { save(it.copy(status = SatArea.STATUS_PAUSED, note = null)) }
    }

    fun resume(id: String) {
        get(id)?.takeIf { it.status != SatArea.STATUS_READY }?.let { save(it.copy(status = SatArea.STATUS_QUEUED, note = null)) }
    }

    /** The next area waiting to download (or left mid-download when the app closed). */
    fun nextToDownload(): SatArea? = _areas.value.sortedBy { it.createdAt }
        .firstOrNull { it.status == SatArea.STATUS_QUEUED || it.status == SatArea.STATUS_DOWNLOADING }

    fun totalBytes(): Long = _areas.value.filter { it.ready }.sumOf { it.bytes }

    /**
     * Downloads one area: reuses tiles from areas already saved, fetches the rest
     * from USGS, then packs them into one file. Safe to stop and run again; it
     * picks up where it left off.
     */
    suspend fun download(area: SatArea, onProgress: (SatArea) -> Unit) = withContext(Dispatchers.IO) {
        pauseRequested = null
        val partDir = parts(area.id).apply { mkdirs() }
        val tiles = PmTilesWriter.tilesFor(area.west, area.south, area.east, area.north, MIN_ZOOM, area.maxZoom)
        // Other saved areas whose box overlaps this one: their tiles are copied instead of downloaded.
        val donors = _areas.value.filter { it.ready && it.id != area.id && overlaps(it, area) }
            .mapNotNull { a -> runCatching { a to PmTiles(file(a)) }.getOrNull() }
        var current = area.copy(status = SatArea.STATUS_DOWNLOADING, total = tiles.size, note = null)
        save(current)
        val done = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val missing = AtomicInteger(0)
        val gate = Semaphore(PARALLEL)
        try {
            coroutineScope {
                tiles.chunked(200).forEach { batch ->
                    if (pauseRequested == area.id || get(area.id) == null) return@coroutineScope
                    if (failures.get() > 25) return@coroutineScope
                    batch.map { t ->
                        async {
                            gate.withPermit { runCatching {
                                val f = File(partDir, "${t.z}_${t.x}_${t.y}.jpg")
                                val none = File(partDir, "${t.z}_${t.x}_${t.y}.none")
                                if (!f.exists() && !none.exists()) {
                                    val reused = donors.firstNotNullOfOrNull { (a, pm) ->
                                        if (t.z <= a.maxZoom && inside(t, a)) runCatching { pm.tile(t.z, t.x, t.y) }.getOrNull() else null
                                    }
                                    when {
                                        reused != null -> writeAtomically(f, reused)
                                        else -> when (val r = fetch(t)) {
                                            null -> failures.incrementAndGet()
                                            NOT_FOUND -> { none.createNewFile(); missing.incrementAndGet() }
                                            else -> { writeAtomically(f, r); failures.set(0) }
                                        }
                                    }
                                }
                                done.incrementAndGet()
                            } }
                        }
                    }.awaitAll()
                    if (get(area.id) == null) return@coroutineScope // deleted while downloading
                    current = current.copy(done = done.get())
                    save(current)
                    onProgress(current)
                }
            }
        } finally {
            donors.forEach { (_, pm) -> runCatching { pm.close() } }
        }

        if (get(area.id) == null) return@withContext // deleted while downloading
        if (pauseRequested == area.id) {
            save(current.copy(status = SatArea.STATUS_PAUSED))
            return@withContext
        }
        if (failures.get() > 25 || done.get() < tiles.size) {
            save(current.copy(status = SatArea.STATUS_PAUSED, note = "Stopped: no connection. Tap Resume when you're online."))
            return@withContext
        }
        val have = tiles.mapNotNull { t -> File(partDir, "${t.z}_${t.x}_${t.y}.jpg").takeIf { it.exists() && it.length() > 0 }?.let { t to it } }
        if (have.isEmpty()) {
            save(current.copy(status = SatArea.STATUS_FAILED, note = "No imagery is available for this area."))
            return@withContext
        }
        val out = file(current)
        PmTilesWriter.write(out, have, PmTilesWriter.TYPE_JPEG, area.west, area.south, area.east, area.north,
            area.name, "Imagery: USGS The National Map")
        partDir.deleteRecursively()
        save(current.copy(status = SatArea.STATUS_READY, done = tiles.size, bytes = out.length(),
            note = if (missing.get() > 0) "${missing.get()} tiles had no imagery" else null))
        area.replaces?.let { old -> delete(old) }
        _version.value += 1
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
    }

    /** Tile bytes, [NOT_FOUND] if USGS has no imagery there, or null on a network problem. */
    private fun fetch(t: TileXyz): ByteArray? {
        repeat(3) { attempt ->
            val c = runCatching { URL("$TILE_URL/${t.z}/${t.y}/${t.x}").openConnection() as HttpURLConnection }.getOrNull() ?: return null
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "TrailGuideGPS (personal offline hiking map)")
                val code = c.responseCode
                if (code == 404) return NOT_FOUND
                if (code == 200) {
                    val b = c.inputStream.readBytes()
                    if (b.size > 100) return b
                }
            } catch (_: Exception) {
            } finally {
                c.disconnect()
            }
            Thread.sleep(500L * (attempt + 1))
        }
        return null
    }

    private fun overlaps(a: SatArea, b: SatArea) = a.west < b.east && a.east > b.west && a.south < b.north && a.north > b.south

    private fun inside(t: TileXyz, a: SatArea): Boolean {
        val w = PmTilesWriter.xToLon(t.x, t.z)
        val e = PmTilesWriter.xToLon(t.x + 1, t.z)
        val n = PmTilesWriter.yToLat(t.y, t.z)
        val s = PmTilesWriter.yToLat(t.y + 1, t.z)
        return w < a.east && e > a.west && s < a.north && n > a.south
    }

    companion object {
        const val TILE_URL = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile"
        const val MIN_ZOOM = 8
        const val MAX_IMAGERY_ZOOM = 16
        /** Average USGS tile size measured over Oregon, for size estimates. */
        const val AVG_TILE_BYTES = 25_000L
        /** About 750 MB; bigger areas should be split up. */
        const val MAX_TILES = 30_000L
        private const val PARALLEL = 6
        private val NOT_FOUND = ByteArray(0)

        @Volatile private var instance: SatelliteStore? = null
        fun get(context: Context): SatelliteStore =
            instance ?: synchronized(this) { instance ?: SatelliteStore(context.applicationContext).also { instance = it } }
    }
}
