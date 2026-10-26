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
     * Downloads every file into a temporary folder, then swaps it in, so a failed
     * download never leaves a half-updated map.
     */
    suspend fun download(release: Release, onProgress: (file: String, done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val tmp = File(dir.parentFile, "$region.download")
            tmp.deleteRecursively()
            tmp.mkdirs()
            var done = 0L
            val total = release.totalBytes
            for (asset in release.assets) {
                val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TrailGuideGPS")
                    connectTimeout = 15000
                    readTimeout = 60000
                }
                try {
                    if (conn.responseCode != 200) error("Download of ${asset.name} failed (${conn.responseCode})")
                    conn.inputStream.use { input ->
                        File(tmp, asset.name).outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(asset.name, done, total)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            File(tmp, TAG_FILE).writeText(release.tag)
            val missing = REQUIRED.filter { !File(tmp, it).exists() }
            if (missing.isNotEmpty()) error("Release is missing ${missing.joinToString()}")
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) error("Couldn't move map files into place")
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
