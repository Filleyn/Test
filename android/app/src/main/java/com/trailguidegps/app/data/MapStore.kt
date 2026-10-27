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

/** Where the offline map files live on the phone, and how they get there. */
class MapStore(context: Context) {
    val region: String = BuildConfig.MAPS_REGION
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps/$region")

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
        val totalBytes get() = assets.sumOf { it.size }
    }

    /** Newest GitHub release whose tag starts with maps-<region>-. */
    suspend fun findLatestRelease(): Release = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.MAPS_REPO}/releases?per_page=30")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TrailGuideGPS")
            connectTimeout = 15000
            readTimeout = 30000
        }
        try {
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val releases = JSONArray(conn.inputStream.bufferedReader().readText())
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                val tag = r.getString("tag_name")
                if (!tag.startsWith("maps-$region-")) continue
                val list = r.getJSONArray("assets")
                val assets = (0 until list.length()).map { j ->
                    val a = list.getJSONObject(j)
                    Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
                }.filter { it.name in REQUIRED || it.name in OPTIONAL }
                return@withContext Release(tag, assets)
            }
            error("No map files found for $region")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * How many bytes an update to [release] would actually download, after
     * reusing files that haven't changed. Reads the release's small manifest.
     */
    suspend fun updateBytes(release: Release): Long = withContext(Dispatchers.IO) {
        val m = release.assets.firstOrNull { it.name == "manifest.json" } ?: return@withContext release.totalBytes
        val tmp = File(dir.parentFile, "$region.manifest-check.json")
        try {
            fetch(m, tmp.apply { delete() }, 0L) { }
            val newSums = checksums(tmp)
            val oldSums = checksums(file("manifest.json"))
            release.assets.filter { a ->
                a.name == "manifest.json" || newSums[a.name] == null || newSums[a.name] != oldSums[a.name] || !file(a.name).exists()
            }.sumOf { it.size }
        } finally {
            tmp.delete()
        }
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
            val tmp = File(dir.parentFile, "$region.download")
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
            for (asset in release.assets) {
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

            val toFetch = release.assets.filter { File(tmp, it.name).length() != it.size }
            val total = toFetch.sumOf { it.size }
            var done = toFetch.sumOf { a -> File(tmp, a.name).length().coerceAtMost(a.size) }
            for (asset in toFetch) {
                val target = File(tmp, asset.name)
                done = fetch(asset, target, done) { d -> onProgress(asset.name, d, total) }
                if (target.length() != asset.size) error("${asset.name} didn't finish downloading. Try again to resume.")
            }
            val missing = REQUIRED.filter { !File(tmp, it).exists() }
            if (missing.isNotEmpty()) error("Release is missing ${missing.joinToString()}")
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) error("Couldn't move map files into place")
            // Remove map files for other regions (for example the earlier test area).
            dir.parentFile?.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
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
        val OPTIONAL = listOf("routing-graph.json.gz")
        private const val TAG_FILE = "release-tag.txt"
    }
}
