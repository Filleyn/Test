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

/**
 * The line from one route point to the next. [kindM] is metres on trail, track and road;
 * straight-line legs count as off-trail. [sacMax]/[sacRatedM] come from the trails' SAC ratings.
 */
data class RouteLeg(
    val coords: List<DoubleArray>, val lengthM: Double, val names: List<String>, val onTrail: Boolean,
    val kindM: DoubleArray = DoubleArray(3), val sacMax: Int = 0, val sacRatedM: Double = 0.0,
)

/** What a route crosses, for the difficulty rating. Metres. */
data class RouteTerrain(
    val trailM: Double = 0.0, val trackM: Double = 0.0, val roadM: Double = 0.0, val offTrailM: Double = 0.0,
    val sacMax: Int = 0, val sacRatedM: Double = 0.0,
) {
    companion object {
        fun of(legs: List<RouteLeg>) = RouteTerrain(
            trailM = legs.sumOf { it.kindM[0] }, trackM = legs.sumOf { it.kindM[1] }, roadM = legs.sumOf { it.kindM[2] },
            offTrailM = legs.filter { !it.onTrail }.sumOf { it.lengthM } +
                legs.filter { it.onTrail }.sumOf { (it.lengthM - it.kindM.sum()).coerceAtLeast(0.0) },
            sacMax = legs.maxOfOrNull { it.sacMax } ?: 0,
            sacRatedM = legs.sumOf { it.sacRatedM },
        )
    }
}

/**
 * How hard a route is. The headline is the Shenandoah National Park rating,
 * √(feet of climb × 2 × miles), used by the Park Service and many hiking guides.
 * The rest covers what that number leaves out.
 */
data class Difficulty(
    val score: Double,
    val label: String,
    val level: Int, // 0 easiest … 4 very strenuous
    val steepestGradePct: Double?,
    val steepestAtM: Double?,
    val highPointFt: Double?,
    val terrain: RouteTerrain?,
) {
    companion object {
        val LABELS = listOf("Easiest", "Moderate", "Moderately strenuous", "Strenuous", "Very strenuous")
        val SAC = listOf(
            "Not rated",
            "T1 hiking: well-made trail, easy footing",
            "T2 mountain hiking: some steep or rough sections, sure footing helps",
            "T3 demanding mountain hiking: exposed spots, may need hands for balance",
            "T4 alpine hiking: route-finding, easy scrambling, snow possible",
            "T5 demanding alpine: scrambling, glacier or snow gear possible",
            "T6 difficult alpine: climbing skills needed",
        )

        /** The Shenandoah score and its level (0–4) from distance and climb alone. */
        fun rating(distanceM: Double, gainM: Double): Pair<Double, Int> {
            val score = kotlin.math.sqrt(gainM * Geo.FT_PER_M * 2 * (distanceM / Geo.M_PER_MI))
            return score to when { score < 50 -> 0; score < 100 -> 1; score < 150 -> 2; score < 200 -> 3; else -> 4 }
        }

        fun of(stats: RouteStats, terrain: RouteTerrain?): Difficulty {
            val (score, level) = rating(stats.distanceM, stats.gainM)
            // Steepest grade held over at least a tenth of a mile.
            var steepest: Double? = null
            var steepAt: Double? = null
            val p = stats.profile
            var j = 0
            for (i in p.indices) {
                if (j < i) j = i
                while (j < p.size && p[j].first - p[i].first < 160.0) j++
                if (j >= p.size) break
                val g = kotlin.math.abs(p[j].second - p[i].second) / (p[j].first - p[i].first) * 100
                if (steepest == null || g > steepest) { steepest = g; steepAt = p[i].first }
            }
            return Difficulty(score, LABELS[level], level, steepest, steepAt, p.maxOfOrNull { it.second }?.let { it * Geo.FT_PER_M }, terrain)
        }
    }
}

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
        /** One recorded hike, as the pace fit sees it. Distance m, climb m, times in minutes. */
        data class Hike(val distanceM: Double, val gainM: Double, val movingMin: Double, val totalMin: Double)

        /**
         * Works out a pace from recorded hikes: moving time ≈ miles ÷ mph + climb × minutes per
         * 1,000 ft, fitted by least squares; breaks from stopped time. Needs 3 hikes of a mile or
         * more. Falls back to keeping the current climb setting if the fit isn't believable.
         */
        fun fit(hikes: List<Hike>, current: Pace): Pace? {
            val hs = hikes.filter { it.distanceM >= Geo.M_PER_MI && it.movingMin >= 20 }
            if (hs.size < 3) return null
            val x1 = hs.map { it.distanceM / Geo.M_PER_MI * 60 }      // × (1 / mph)
            val x2 = hs.map { it.gainM * Geo.FT_PER_M / 1000 }         // × minutes per 1,000 ft
            val y = hs.map { it.movingMin }
            val s11 = x1.indices.sumOf { x1[it] * x1[it] }
            val s12 = x1.indices.sumOf { x1[it] * x2[it] }
            val s22 = x2.indices.sumOf { x2[it] * x2[it] }
            val s1y = x1.indices.sumOf { x1[it] * y[it] }
            val s2y = x2.indices.sumOf { x2[it] * y[it] }
            val det = s11 * s22 - s12 * s12
            var mph: Double
            var climb: Double
            if (det > 1e-6) {
                val a = (s1y * s22 - s2y * s12) / det
                climb = (s11 * s2y - s12 * s1y) / det
                mph = if (a > 0) 1 / a else 0.0
            } else { mph = 0.0; climb = -1.0 }
            if (climb !in 5.0..60.0 || mph !in 1.0..3.5) {
                climb = current.climbMinPer1000Ft.toDouble()
                val a = x1.indices.sumOf { x1[it] * (y[it] - climb * x2[it]) } / s11
                mph = if (a > 0) 1 / a else return null
                if (mph !in 0.8..4.0) return null
            }
            val moving = hs.sumOf { it.movingMin }
            val stopped = hs.sumOf { (it.totalMin - it.movingMin).coerceAtLeast(0.0) }
            val breaks = (stopped / moving * 60).coerceIn(0.0, 20.0)
            return Pace(
                mph = (mph * 10).roundToInt() / 10.0,
                climbMinPer1000Ft = ((climb / 5).roundToInt() * 5).coerceIn(0, 60),
                breakMinPerHour = ((breaks / 5).roundToInt() * 5).coerceIn(0, 20),
            )
        }

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
    val terrain: RouteTerrain? = null,
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
            .put("terrain", r.terrain?.let { t ->
                JSONObject().put("trail", t.trailM).put("track", t.trackM).put("road", t.roadM).put("off", t.offTrailM)
                    .put("sac", t.sacMax).put("sacRated", t.sacRatedM)
            } ?: JSONObject.NULL)
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
            o.optJSONObject("terrain")?.let { t ->
                RouteTerrain(t.optDouble("trail"), t.optDouble("track"), t.optDouble("road"), t.optDouble("off"),
                    t.optInt("sac"), t.optDouble("sacRated"))
            },
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
