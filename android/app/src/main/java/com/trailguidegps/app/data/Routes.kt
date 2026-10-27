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
import kotlin.math.roundToInt

/** A point you placed while planning. [followTrails] is how the leg *to* this point was drawn. */
data class RoutePoint(val lat: Double, val lon: Double, val followTrails: Boolean, val waypointName: String? = null)

/** The line from one route point to the next. */
data class RouteLeg(val coords: List<DoubleArray>, val lengthM: Double, val names: List<String>, val onTrail: Boolean)

/** Totals and the elevation profile for a route. Distances metres, elevation metres. */
data class RouteStats(
    val distanceM: Double,
    val gainM: Double,
    val lossM: Double,
    /** (distance along route m, elevation m) pairs. */
    val profile: List<Pair<Double, Double>>,
) {
    companion object {
        val EMPTY = RouteStats(0.0, 0.0, 0.0, emptyList())

        /**
         * Samples elevation every ~30 m along the line, smooths it, and totals the
         * climb and descent the same way the map build does.
         */
        fun compute(coords: List<DoubleArray>, distanceM: Double, terrain: Terrain?): RouteStats {
            if (coords.size < 2) return RouteStats(distanceM, 0.0, 0.0, emptyList())
            val step = maxOf(30.0, distanceM / 1500.0)
            val samples = ArrayList<Pair<Double, Double>>()
            var along = 0.0
            var nextAt = 0.0
            for (i in 0 until coords.size - 1) {
                val a = coords[i]
                val b = coords[i + 1]
                val d = Geo.distanceM(a[0], a[1], b[0], b[1])
                while (nextAt <= along + d) {
                    val t = if (d == 0.0) 0.0 else (nextAt - along) / d
                    terrain?.elevationM(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))?.let { samples += nextAt to it }
                    nextAt += step
                }
                along += d
            }
            val last = coords.last()
            terrain?.elevationM(last[0], last[1])?.let { samples += along to it }
            if (samples.size < 2) return RouteStats(distanceM, 0.0, 0.0, samples)
            val smooth = samples.indices.map { i ->
                val w = samples.subList(maxOf(0, i - 2), minOf(samples.size, i + 3))
                samples[i].first to w.sumOf { it.second } / w.size
            }
            var gain = 0.0
            var loss = 0.0
            var ref = smooth[0].second
            for ((_, e) in smooth) {
                if (e - ref > 1.0) { gain += e - ref; ref = e }
                else if (ref - e > 1.0) { loss += ref - e; ref = e }
            }
            return RouteStats(distanceM, gain, loss, smooth)
        }
    }
}

/** Your hiking-time settings: defaults are 2 mph, 30 min per 1,000 ft of climb, 10 min break per hour. */
data class Pace(val mph: Double = 2.0, val climbMinPer1000Ft: Int = 30, val breakMinPerHour: Int = 10) {
    /** Estimated minutes: walking time + extra for climbing + breaks. */
    fun minutes(distanceM: Double, gainM: Double): Int {
        val miles = distanceM / Geo.M_PER_MI
        val moving = miles / mph * 60.0 + gainM * Geo.FT_PER_M / 1000.0 * climbMinPer1000Ft
        return (moving + moving / 60.0 * breakMinPerHour).roundToInt()
    }

    companion object {
        fun load(context: Context): Pace {
            val p = context.getSharedPreferences("pace", Context.MODE_PRIVATE)
            return Pace(p.getFloat("mph", 2.0f).toDouble(), p.getInt("climb", 30), p.getInt("breaks", 10))
        }

        fun save(context: Context, pace: Pace) {
            context.getSharedPreferences("pace", Context.MODE_PRIVATE).edit()
                .putFloat("mph", pace.mph.toFloat()).putInt("climb", pace.climbMinPer1000Ft).putInt("breaks", pace.breakMinPerHour).apply()
        }

        /** "4h 40m" or "35m". */
        fun format(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}

/** A saved route: the points you placed, the line, and its totals. */
data class SavedRoute(
    val id: String,
    val name: String,
    val createdAt: Long,
    val points: List<RoutePoint>,
    val coords: List<DoubleArray>,
    val stats: RouteStats,
    val via: List<String>,
    val color: String = RouteColors.DEFAULT,
)

data class RouteSummary(
    val id: String, val name: String, val createdAt: Long, val distanceM: Double, val gainM: Double, val lossM: Double,
    val color: String = RouteColors.DEFAULT,
)

/** Colors you can give a route. Chosen to stand out from trails (red-brown), tracks and water. */
object RouteColors {
    const val DEFAULT = "#6B3FA0"
    val ALL = listOf(
        "Purple" to "#6B3FA0",
        "Blue" to "#1F4FB4",
        "Magenta" to "#C2185B",
        "Green" to "#2E7D32",
        "Gold" to "#B07D00",
        "Black" to "#222222",
    )
    fun nameOf(hex: String) = ALL.firstOrNull { it.second.equals(hex, true) }?.first ?: "Custom"
}

/** Saved routes, one JSON file each in the app's private storage. */
class RouteStore(context: Context) {
    private val dir = File(context.filesDir, "routes").apply { mkdirs() }
    private val _summaries = MutableStateFlow(scan())
    val summaries: StateFlow<List<RouteSummary>> = _summaries.asStateFlow()

    fun save(r: SavedRoute) {
        val o = JSONObject().put("id", r.id).put("name", r.name).put("created", r.createdAt)
            .put("distance", r.stats.distanceM).put("gain", r.stats.gainM).put("loss", r.stats.lossM)
            .put("via", JSONArray(r.via)).put("color", r.color)
            .put("points", JSONArray().apply {
                r.points.forEach { p ->
                    put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("trail", p.followTrails).put("wp", p.waypointName ?: JSONObject.NULL))
                }
            })
            .put("coords", JSONArray().apply { r.coords.forEach { put(JSONArray().put(round6(it[0])).put(round6(it[1]))) } })
            .put("profile", JSONArray().apply { r.stats.profile.forEach { put(JSONArray().put(it.first.roundToInt()).put((it.second * 10).roundToInt() / 10.0)) } })
        val tmp = File(dir, "${r.id}.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "${r.id}.json"))
        _summaries.value = scan()
    }

    fun load(id: String): SavedRoute? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        val pts = o.getJSONArray("points")
        val cs = o.getJSONArray("coords")
        val pf = o.optJSONArray("profile") ?: JSONArray()
        val via = o.optJSONArray("via") ?: JSONArray()
        SavedRoute(
            o.getString("id"), o.getString("name"), o.getLong("created"),
            (0 until pts.length()).map { i ->
                val p = pts.getJSONObject(i)
                RoutePoint(p.getDouble("lat"), p.getDouble("lon"), p.optBoolean("trail", true), p.optString("wp").takeIf { !p.isNull("wp") && it.isNotBlank() })
            },
            (0 until cs.length()).map { i -> cs.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } },
            RouteStats(o.getDouble("distance"), o.getDouble("gain"), o.getDouble("loss"),
                (0 until pf.length()).map { i -> pf.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }),
            (0 until via.length()).map { via.getString(it) },
            o.optString("color", RouteColors.DEFAULT).ifBlank { RouteColors.DEFAULT },
        )
    }.getOrNull()

    fun rename(id: String, name: String) {
        load(id)?.let { save(it.copy(name = name)) }
    }

    fun setColor(id: String, color: String) {
        load(id)?.let { save(it.copy(color = color)) }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _summaries.value = scan()
    }

    private fun scan(): List<RouteSummary> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                // Read only the small fields; the line itself can be large.
                val o = JSONObject(f.readText())
                RouteSummary(o.getString("id"), o.getString("name"), o.getLong("created"),
                    o.getDouble("distance"), o.getDouble("gain"), o.getDouble("loss"),
                    o.optString("color", RouteColors.DEFAULT).ifBlank { RouteColors.DEFAULT })
            }.getOrNull()
        }
        .sortedByDescending { it.createdAt }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6

        /** GeoJSON with each leg as a feature; straight-line legs are marked so they draw dashed. */
        fun legsGeoJson(legs: List<RouteLeg>): String {
            val features = JSONArray()
            legs.filter { it.coords.size >= 2 }.forEach { leg ->
                val c = JSONArray()
                leg.coords.forEach { c.put(JSONArray().put(it[1]).put(it[0])) }
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("trail", leg.onTrail))
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", c)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }

        /** The point [distanceM] along the line, as (lat, lon); distances measured the same way as the profile. */
        fun pointAt(coords: List<DoubleArray>, distanceM: Double): DoubleArray? {
            if (coords.isEmpty()) return null
            var along = 0.0
            for (i in 0 until coords.size - 1) {
                val a = coords[i]
                val b = coords[i + 1]
                val d = Geo.distanceM(a[0], a[1], b[0], b[1])
                if (along + d >= distanceM) {
                    val t = if (d == 0.0) 0.0 else ((distanceM - along) / d).coerceIn(0.0, 1.0)
                    return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
                }
                along += d
            }
            return coords.last()
        }

        fun pointGeoJson(p: DoubleArray?): String {
            val features = JSONArray()
            if (p != null) {
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p[1]).put(p[0]))))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }

        /** Several saved routes, each in its own color; the one in the bottom panel is marked "focus". */
        fun routesGeoJson(routes: List<SavedRoute>, focusId: String?): String {
            val features = JSONArray()
            // The focused route last so it draws on top.
            routes.sortedBy { it.id == focusId }.filter { it.coords.size >= 2 }.forEach { r ->
                val c = JSONArray()
                r.coords.forEach { c.put(JSONArray().put(it[1]).put(it[0])) }
                features.put(JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("id", r.id).put("color", r.color).put("focus", r.id == focusId))
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", c)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }

        fun lineGeoJson(coords: List<DoubleArray>, color: String = RouteColors.DEFAULT): String {
            val c = JSONArray()
            coords.forEach { c.put(JSONArray().put(it[1]).put(it[0])) }
            val features = JSONArray()
            if (coords.size >= 2) {
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("color", color))
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", c)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }

        fun pointsGeoJson(points: List<RoutePoint>): String {
            val features = JSONArray()
            points.forEachIndexed { i, p ->
                val role = when (i) { 0 -> "start"; points.size - 1 -> "end"; else -> "mid" }
                features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("role", if (points.size == 1) "start" else role))
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat))))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }
    }
}
