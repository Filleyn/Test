package com.trailguidegps.app.data

import android.content.Context
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One recorded GPS point. Elevation is metres; the app shows feet. */
data class TrackPoint(val lat: Double, val lon: Double, val eleM: Double?, val time: Long)

/** Totals for a track, in the app's units: miles, feet, minutes. */
data class TrackStats(val distanceM: Double, val gainM: Double, val lossM: Double, val movingMs: Long, val totalMs: Long) {
    companion object {
        /** Walking pace counts as moving; slower than about 0.5 mph counts as stopped. */
        private const val MOVING_MPS = 0.22
        /** Ignore elevation wiggles under about 10 ft so GPS noise doesn't inflate climb. */
        private const val ELE_HYSTERESIS_M = 3.0

        fun of(points: List<TrackPoint>): TrackStats {
            if (points.size < 2) return TrackStats(0.0, 0.0, 0.0, 0, 0)
            var dist = 0.0
            var moving = 0L
            var gain = 0.0
            var loss = 0.0
            var ref: Double? = points.firstOrNull { it.eleM != null }?.eleM
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val d = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
                val dt = (b.time - a.time).coerceAtLeast(0)
                dist += d
                if (dt in 1..120_000 && d / (dt / 1000.0) > MOVING_MPS) moving += dt
                val e = b.eleM
                if (e != null && ref != null) {
                    if (e - ref > ELE_HYSTERESIS_M) { gain += e - ref; ref = e }
                    else if (ref - e > ELE_HYSTERESIS_M) { loss += ref - e; ref = e }
                }
            }
            return TrackStats(dist, gain, loss, moving, points.last().time - points.first().time)
        }
    }
}

data class TrackSummary(val id: String, val name: String, val startedAt: Long, val stats: TrackStats, val pointCount: Int)

/** Saved tracks, one JSON file each in the app's private storage. */
class TrackStore(context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val _summaries = MutableStateFlow(scan())
    val summaries: StateFlow<List<TrackSummary>> = _summaries.asStateFlow()

    fun save(name: String, points: List<TrackPoint>, id: String = UUID.randomUUID().toString()): TrackSummary {
        val o = JSONObject().put("id", id).put("name", name).put("started", points.firstOrNull()?.time ?: System.currentTimeMillis())
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONArray().put(p.lat).put(p.lon).put(p.eleM ?: JSONObject.NULL).put(p.time))
        }
        o.put("points", arr)
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "$id.json"))
        _summaries.value = scan()
        return _summaries.value.first { it.id == id }
    }

    fun load(id: String): Pair<String, List<TrackPoint>>? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        val arr = o.getJSONArray("points")
        o.getString("name") to (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            TrackPoint(p.getDouble(0), p.getDouble(1), if (p.isNull(2)) null else p.getDouble(2), p.getLong(3))
        }
    }.getOrNull()

    fun rename(id: String, name: String) {
        val (_, pts) = load(id) ?: return
        save(name, pts, id)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _summaries.value = scan()
    }

    /** Re-reads the folder (after restoring a backup). */
    fun reload() { _summaries.value = scan() }

    private fun scan(): List<TrackSummary> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val arr = o.getJSONArray("points")
                val pts = (0 until arr.length()).map { i ->
                    val p = arr.getJSONArray(i)
                    TrackPoint(p.getDouble(0), p.getDouble(1), if (p.isNull(2)) null else p.getDouble(2), p.getLong(3))
                }
                TrackSummary(o.getString("id"), o.getString("name"), o.getLong("started"), TrackStats.of(pts), pts.size)
            }.getOrNull()
        }
        .sortedByDescending { it.startedAt }

    companion object {
        fun lineGeoJson(points: List<TrackPoint>): String {
            val coords = JSONArray()
            points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            val features = JSONArray()
            if (points.size >= 2) {
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }
    }
}
