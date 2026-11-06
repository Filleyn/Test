package com.trailguidegps.app.data

import android.content.Context
import com.trailguidegps.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where one state's offline map files live on the phone, and how they get there. */
class MapStore(context: Context, val region: String = BuildConfig.MAPS_REGION) {
    private val rootDir: File = root(context)
    /** The state's folder. Each installed version lives in its own subfolder inside it. */
    val stateDir: File = File(rootDir, region)

    /**
     * Folder holding the installed files. Every update goes into a new subfolder (named
     * after its release) instead of replacing files in place: the map engine caches each
     * file's layout by path, and reading a swapped file at old offsets crashes it.
     * Older installs kept their files directly in [stateDir].
     */
    val dir: File get() {
        val cur = File(stateDir, CURRENT_FILE).takeIf { it.exists() }?.readText()?.trim()
        if (!cur.isNullOrEmpty()) File(stateDir, cur).takeIf { it.isDirectory }?.let { return it }
        return stateDir
    }

    /** Removes this state's map files from the phone. */
    fun delete() { stateDir.deleteRecursively() }

    /** Deletes earlier versions' files, once the map has moved on to the current ones. */
    fun cleanOld() {
        val keep = dir
        if (keep == stateDir) return
        stateDir.listFiles()?.forEach { f ->
            if (f != keep && f.name != CURRENT_FILE) f.deleteRecursively()
        }
    }

    /** Everyday name: "Oregon". */
    val name: String get() = displayName(region)

    fun file(name: String) = File(dir, name)

    fun isReady(): Boolean = REQUIRED.all { file(it).let { f -> f.exists() && f.length() > 0 } }

    fun installedTag(): String? = file(TAG_FILE).takeIf { it.exists() }?.readText()?.trim()

    fun manifest(): JSONObject? = file("manifest.json").takeIf { it.exists() }?.let {
        runCatching { JSONObject(it.readText()) }.getOrNull()
    }

    /** [west, south, east, north] of the region, from the manifest. */
    fun bbox(): DoubleArray? = manifest()?.optJSONArray("bbox")?.let { a ->
        DoubleArray(4) { a.getDouble(it) }
    }

    fun builtDate(): String? = manifest()?.optString("built_utc")?.takeIf { it.isNotBlank() }?.take(10)

    fun title(): String = manifest()?.optString("title")?.takeIf { it.isNotBlank() } ?: region

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val assets: List<Asset>) {
        /** Files a download installs (the online-only search file isn't one of them). */
        val downloadAssets get() = assets.filter { it.name !in ONLINE }
        val totalBytes get() = downloadAssets.sumOf { it.size }
    }

    /** Newest GitHub release whose tag starts with maps-<region>-. */
    suspend fun findLatestRelease(): Release = withContext(Dispatchers.IO) {
        run {
            val releases = allReleases()
            // GitHub doesn't always list releases newest first, so pick the most recently published.
            val newest = releases
                .filter { it.getString("tag_name").startsWith("maps-$region-") && !it.optBoolean("draft") }
                .maxByOrNull { it.optString("published_at").ifBlank { it.optString("created_at") } }
                ?: error("No map files found for $region")
            val list = newest.getJSONArray("assets")
            val assets = (0 until list.length()).map { j ->
                val a = list.getJSONObject(j)
                Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
            }.filter { it.name in REQUIRED || it.name in OPTIONAL }
            return@withContext Release(newest.getString("tag_name"), assets)
        }
    }

    /**
     * How many bytes an update to [release] would actually download, after
     * reusing files that haven't changed. Reads the release's small manifest.
     */
    suspend fun updateBytes(release: Release): Long = withContext(Dispatchers.IO) {
        val m = release.assets.firstOrNull { it.name == "manifest.json" } ?: return@withContext release.totalBytes
        val tmp = File(rootDir, "$region.manifest-check.json")
        try {
            fetch(m, tmp.apply { delete() }, 0L) { }
            val newSums = checksums(tmp)
            val oldSums = checksums(file("manifest.json"))
            release.downloadAssets.filter { a ->
                a.name == "manifest.json" || newSums[a.name] == null || newSums[a.name] != oldSums[a.name] || !file(a.name).exists()
            }.sumOf { it.size }
        } finally {
            tmp.delete()
        }
    }

    /** The unpacked trail network the route planner memory-maps. */
    fun routingFile(): File = file("routing.bin")

    /**
     * Unpacks routing.bin.gz next to itself (about 2-3 times larger) if it hasn't
     * been yet. Returns true when routing.bin is ready.
     */
    fun unpackRouting(): Boolean {
        val gz = file("routing.bin.gz")
        val bin = routingFile()
        if (!gz.exists()) return bin.exists()
        if (bin.exists() && bin.lastModified() >= gz.lastModified()) return true
        val tmp = File(dir, "routing.bin.tmp")
        return runCatching {
            java.util.zip.GZIPInputStream(gz.inputStream().buffered()).use { input ->
                tmp.outputStream().buffered().use { input.copyTo(it, 256 * 1024) }
            }
            bin.delete()
            tmp.renameTo(bin)
        }.getOrElse { tmp.delete(); false }
    }

    /** File name → SHA-256 from a manifest, so unchanged files can be reused. */
    private fun checksums(manifest: File): Map<String, String> = runCatching {
        val files = JSONObject(manifest.readText()).getJSONArray("files")
        (0 until files.length()).associate { i ->
            val f = files.getJSONObject(i)
            f.getString("name") to f.getString("sha256")
        }
    }.getOrDefault(emptyMap())

    /** Hard-links (or copies) an unchanged installed file into the new download folder. */
    private fun reuse(from: File, to: File): Boolean = runCatching {
        to.delete()
        try {
            java.nio.file.Files.createLink(to.toPath(), from.toPath())
        } catch (e: Exception) {
            from.copyTo(to, overwrite = true)
        }
        to.length() == from.length()
    }.getOrDefault(false)

    /**
     * Downloads the release into a temporary folder, then swaps it in, so a failed
     * download never leaves a half-updated map. Files that haven't changed since
     * the installed version are reused instead of downloaded again, and an
     * interrupted download of the same release picks up where it stopped.
     */
    suspend fun download(release: Release, onProgress: (file: String, done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val tmp = File(rootDir, "$region.download")
            val tagFile = File(tmp, TAG_FILE)
            if (!(tagFile.exists() && tagFile.readText().trim() == release.tag)) {
                tmp.deleteRecursively()
            }
            tmp.mkdirs()
            tagFile.writeText(release.tag)

            // Fetch the new manifest first (a few KB) and compare checksums.
            release.assets.firstOrNull { it.name == "manifest.json" }?.let { m ->
                val target = File(tmp, m.name)
                if (target.length() != m.size) fetch(m, target, 0L) { _ -> }
            }
            val newSums = checksums(File(tmp, "manifest.json"))
            val oldSums = checksums(file("manifest.json"))
            for (asset in release.downloadAssets) {
                if (asset.name == "manifest.json") continue
                val target = File(tmp, asset.name)
                val installed = file(asset.name)
                val sum = newSums[asset.name]
                if (target.length() != asset.size && sum != null && oldSums[asset.name] == sum &&
                    installed.exists() && installed.length() == asset.size
                ) {
                    reuse(installed, target)
                }
            }

            val toFetch = release.downloadAssets.filter { File(tmp, it.name).length() != it.size }
            val total = toFetch.sumOf { it.size }
            var done = toFetch.sumOf { a -> File(tmp, a.name).length().coerceAtMost(a.size) }
            for (asset in toFetch) {
                val target = File(tmp, asset.name)
                done = fetch(asset, target, done) { d -> onProgress(asset.name, d, total) }
                if (target.length() != asset.size) error("${asset.name} didn't finish downloading. Try again to resume.")
            }
            val missing = REQUIRED.filter { !File(tmp, it).exists() }
            if (missing.isNotEmpty()) error("Release is missing ${missing.joinToString()}")
            // Move the new files into their own folder and point at it. The old files stay
            // until the map has reloaded (cleanOld), so nothing it has open changes under it.
            stateDir.mkdirs()
            val name = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(stateDir, name).takeIf { it != dir } ?: File(stateDir, "$name-${System.currentTimeMillis()}")
            target.deleteRecursively()
            if (!tmp.renameTo(target)) error("Couldn't move map files into place")
            val pointer = File(stateDir, "$CURRENT_FILE.tmp")
            pointer.writeText(target.name)
            if (!pointer.renameTo(File(stateDir, CURRENT_FILE))) error("Couldn't switch to the new map files")
        }

    /** Downloads one file, resuming a partial one. Returns the updated running byte count. */
    private fun fetch(asset: Asset, target: File, doneBefore: Long, onBytes: (Long) -> Unit): Long {
        var done = doneBefore
        val have = if (target.exists()) target.length() else 0L
        if (have == asset.size) return done
        val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TrailGuideGPS")
            if (have in 1 until asset.size) setRequestProperty("Range", "bytes=$have-")
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            val code = conn.responseCode
            val append = code == 206
            if (code != 200 && code != 206) error("Download of ${asset.name} failed (HTTP $code)")
            if (!append) done -= have
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return done
    }

    companion object {
        val REQUIRED = listOf(
            "topo.pmtiles", "contours.pmtiles", "terrain.pmtiles",
            "water.geojson", "trails.geojson", "pois.geojson", "manifest.json",
        )
        val OPTIONAL = listOf("routing.bin.gz", "publiclands.pmtiles", "slope.pmtiles", "extras.pmtiles", "places.pmtiles")
        /** Listed for states you haven't downloaded, never downloaded with the maps. */
        val ONLINE = listOf("search.json.gz")
        const val SEARCH_FILE = "search.json.gz"
        private const val TAG_FILE = "release-tag.txt"
        private const val CURRENT_FILE = "current.txt"

        fun root(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps")

        /** States with complete map files on the phone, Oregon first, then by name. */
        fun installedRegions(context: Context): List<String> =
            (root(context).listFiles { f -> f.isDirectory && !f.name.endsWith(".download") } ?: emptyArray())
                .map { it.name }.filter { MapStore(context, it).isReady() }
                .sortedWith(compareBy({ it != BuildConfig.MAPS_REGION }, { it }))

        /** "north-carolina" → "North Carolina". */
        fun displayName(region: String) = region.split('-', '_', ' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        private val TAG = Regex("^maps-(.+)-(\\d{4}-\\d{2}-\\d{2})-run\\d+$")
        /** Test areas that aren't offered in the app. */
        private val HIDDEN = setOf("cottonwood-canyon")

        /** The newest map release for every state, from GitHub. */
        suspend fun catalog(): Map<String, Release> = withContext(Dispatchers.IO) {
            run {
                val releases = allReleases()
                releases
                    .filter { !it.optBoolean("draft") }
                    .mapNotNull { r -> TAG.find(r.getString("tag_name"))?.let { m -> m.groupValues[1] to r } }
                    .filter { it.first !in HIDDEN }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, rs) ->
                        val newest = rs.maxBy { it.optString("published_at").ifBlank { it.optString("created_at") } }
                        val list = newest.getJSONArray("assets")
                        Release(newest.getString("tag_name"), (0 until list.length()).map { j ->
                            val a = list.getJSONObject(j)
                            Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
                        }.filter { it.name in REQUIRED || it.name in OPTIONAL || it.name in ONLINE })
                    }
            }
        }

        /** GitHub lists 100 releases a page; read a few pages so older states don't drop off. */
        fun allReleases(maxPages: Int = 4): List<JSONObject> {
            val out = mutableListOf<JSONObject>()
            for (page in 1..maxPages) {
                val conn = (URL("https://api.github.com/repos/${BuildConfig.MAPS_REPO}/releases?per_page=100&page=$page").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "TrailGuideGPS")
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val list = try {
                    if (conn.responseCode != 200) {
                        if (out.isNotEmpty()) break
                        error("GitHub answered ${conn.responseCode}")
                    }
                    JSONArray(conn.inputStream.bufferedReader().readText())
                } finally {
                    conn.disconnect()
                }
                for (i in 0 until list.length()) out += list.getJSONObject(i)
                if (list.length() < 100) break
            }
            return out
        }

        /** A release's small manifest (title, area, build date), read straight from GitHub. */
        suspend fun fetchManifest(release: Release): JSONObject? = withContext(Dispatchers.IO) {
            val m = release.assets.firstOrNull { it.name == "manifest.json" } ?: return@withContext null
            runCatching {
                val c = (URL(m.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true; setRequestProperty("User-Agent", "TrailGuideGPS"); connectTimeout = 15000; readTimeout = 30000
                }
                try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            }.getOrNull()
        }
    }
}

/** Which part of the country a state's maps belong to, for grouping the Maps list. */
object MapAreas {
    val ORDER = listOf("West Coast", "Mountain West", "Southwest", "Midwest", "South", "Northeast", "Alaska and Hawaii", "Other")
    private val AREA = buildMap {
        listOf("california", "oregon", "washington").forEach { put(it, "West Coast") }
        listOf("idaho", "montana", "wyoming", "nevada", "utah", "colorado").forEach { put(it, "Mountain West") }
        listOf("arizona", "new-mexico", "texas", "oklahoma").forEach { put(it, "Southwest") }
        listOf("north-dakota", "south-dakota", "nebraska", "kansas", "minnesota", "iowa", "missouri", "wisconsin",
            "illinois", "michigan", "indiana", "ohio").forEach { put(it, "Midwest") }
        listOf("arkansas", "louisiana", "mississippi", "alabama", "tennessee", "kentucky", "georgia", "florida",
            "south-carolina", "north-carolina", "virginia", "west-virginia").forEach { put(it, "South") }
        listOf("maryland", "delaware", "district-of-columbia", "new-jersey", "pennsylvania", "new-york", "connecticut",
            "rhode-island", "massachusetts", "vermont", "new-hampshire", "maine").forEach { put(it, "Northeast") }
        put("hawaii", "Alaska and Hawaii")
    }
    /** "Mountain West" for "montana"; Alaska's regional maps ("alaska-southcentral") go with Alaska. */
    fun of(region: String): String = AREA[region] ?: if (region.startsWith("alaska")) "Alaska and Hawaii" else "Other"
}

/** The map area's everyday name ("Oregon"), for wording that shouldn't assume one state. */
object MapRegion {
    /** The downloaded states, set by the app as they change. */
    @Volatile var names: List<String> = listOf(MapStore.displayName(com.trailguidegps.app.BuildConfig.MAPS_REGION))
    val name: String get() = when (names.size) {
        0 -> "your area"
        1 -> names[0]
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
