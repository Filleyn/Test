package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Smoke and air quality, kept as GeoJSON with when they were fetched. [smoke] is the day's
 * smoke outlines from NOAA's satellite analysis (property "density": Light, Medium, Heavy);
 * [aqi] is EPA AirNow's latest-hour PM2.5 air quality as areas (property "gridcode": 1 Good …
 * 6 Hazardous). [smokeDay] is the day the smoke analysis is for, as yyyy-MM-dd.
 */
data class AirData(val smoke: String, val aqi: String, val smokeDay: String?, val aqiAt: Long, val fetchedAt: Long)

/**
 * Smoke (NOAA Hazard Mapping System) and air quality (EPA AirNow), public data with no key.
 * Needs a connection to update; the last copy is kept for offline, marked with its time.
 */
class AirStore(context: Context) {
    private val dir = File(context.filesDir, "air").apply { mkdirs() }
    private val _data = MutableStateFlow(loadCached())
    val data: StateFlow<AirData?> = _data.asStateFlow()

    private fun loadCached(): AirData? = runCatching {
        val meta = JSONObject(File(dir, "meta.json").readText())
        AirData(File(dir, "smoke.json").readText(), File(dir, "aqi.json").readText(),
            meta.optString("smokeDay").ifBlank { null }, meta.optLong("aqiAt"), meta.getLong("fetchedAt"))
    }.getOrNull()

    /** True if missing or older than [maxAgeMs] (AirNow updates hourly). */
    fun stale(maxAgeMs: Long = 60 * 60_000L) = _data.value?.let { System.currentTimeMillis() - it.fetchedAt > maxAgeMs } ?: true

    /** Downloads smoke and air quality for [bbox] (west, south, east, north). Throws if both fail. */
    suspend fun refresh(bbox: List<Double>) = withContext(Dispatchers.IO) {
        val old = _data.value
        val smoke = runCatching { latestSmoke(bbox) }
        val aqi = runCatching { aqiAreas(bbox) }
        if (smoke.isFailure && aqi.isFailure) throw smoke.exceptionOrNull()!!
        val (smokeJson, smokeDay) = smoke.getOrNull() ?: ((old?.smoke ?: EMPTY) to old?.smokeDay)
        val aqiJson = aqi.getOrNull() ?: old?.aqi ?: EMPTY
        val aqiAt = if (aqi.isSuccess) newestTimestamp(aqiJson) ?: System.currentTimeMillis() else old?.aqiAt ?: 0L
        val now = System.currentTimeMillis()
        File(dir, "smoke.json").writeText(smokeJson)
        File(dir, "aqi.json").writeText(aqiJson)
        File(dir, "meta.json").writeText(JSONObject().put("smokeDay", smokeDay ?: "").put("aqiAt", aqiAt).put("fetchedAt", now).toString())
        _data.value = AirData(smokeJson, aqiJson, smokeDay, aqiAt, now)
    }

    /** The newest day's smoke file (today's appears in the evening, so yesterday's is often the latest). */
    private fun latestSmoke(bbox: List<Double>): Pair<String, String> {
        val utc = TimeZone.getTimeZone("UTC")
        val name = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utc }
        val path = SimpleDateFormat("yyyy/MM", Locale.US).apply { timeZone = utc }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        var last: Throwable? = null
        for (back in 0..2) {
            val d = Date(System.currentTimeMillis() - back * 86_400_000L)
            val url = "$SMOKE_BASE/${path.format(d)}/hms_smoke${name.format(d)}.kml"
            val r = runCatching { get(url) }
            r.onSuccess { return smokeGeoJson(it, bbox) to day.format(d) }
            last = r.exceptionOrNull()
        }
        throw last ?: IllegalStateException("No smoke data")
    }

    private fun aqiAreas(bbox: List<Double>): String {
        val params = linkedMapOf(
            "where" to "1=1", "geometry" to bbox.joinToString(","), "geometryType" to "esriGeometryEnvelope", "inSR" to "4326",
            "spatialRel" to "esriSpatialRelIntersects", "outFields" to "gridcode,Timestamp", "outSR" to "4326",
            "maxAllowableOffset" to "0.01", "geometryPrecision" to "3", "f" to "geojson",
        )
        val q = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val text = get("$AQI_URL/query?$q")
        val o = JSONObject(text)
        if (o.has("error")) error("Air quality error: ${o.optJSONObject("error")?.optString("message")}")
        return text
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            if (c.responseCode != 200) error("$url returned ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    companion object {
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        private const val SMOKE_BASE = "https://satepsanone.nesdis.noaa.gov/pub/FIRE/web/HMS/Smoke_Polygons/KML"
        private const val AQI_URL = "https://services.arcgis.com/cJ9YHowT8TU7DUyn/ArcGIS/rest/services/AirNowLatestContoursPM25/FeatureServer/0"

        /** EPA's six AQI categories, by gridcode 1–6: name and the official color. */
        val CATEGORIES = listOf(
            "Good" to "#00E400", "Moderate" to "#FFFF00", "Unhealthy for sensitive groups" to "#FF7E00",
            "Unhealthy" to "#FF0000", "Very unhealthy" to "#8F3F97", "Hazardous" to "#7E0023",
        )
        fun categoryName(gridcode: Int) = CATEGORIES.getOrNull(gridcode - 1)?.first

        private val DENSITY = listOf("Light", "Medium", "Heavy")

        /** NOAA's smoke KML as GeoJSON polygons with a "density" property, keeping only those overlapping [bbox]. */
        fun smokeGeoJson(kml: String, bbox: List<Double>): String {
            val features = JSONArray()
            Regex("<Placemark>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL).findAll(kml).forEach { pm ->
                val body = pm.groupValues[1]
                val density = Regex("Smoke_(Light|Medium|Heavy)").find(body)?.groupValues?.get(1)
                    ?: Regex("Density:\\s*(?:<[^>]*>\\s*)*(Light|Medium|Heavy)").find(body)?.groupValues?.get(1) ?: return@forEach
                val rings = Regex("<coordinates>(.*?)</coordinates>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { m ->
                    m.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { t ->
                        val p = t.split(","); if (p.size < 2) null else p[0].toDoubleOrNull()?.let { lo -> p[1].toDoubleOrNull()?.let { la -> lo to la } }
                    }
                }.filter { it.size >= 4 }.toList()
                if (rings.isEmpty()) return@forEach
                val outer = rings[0]
                if (outer.maxOf { it.first } < bbox[0] || outer.minOf { it.first } > bbox[2] ||
                    outer.maxOf { it.second } < bbox[1] || outer.minOf { it.second } > bbox[3]) return@forEach
                val coords = JSONArray().apply {
                    rings.forEach { r -> put(JSONArray().apply { r.forEach { (lo, la) -> put(JSONArray().put(lo).put(la)) } }) }
                }
                features.put(JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("density", density).put("level", DENSITY.indexOf(density) + 1))
                    .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", coords)))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }

        private fun newestTimestamp(geojson: String): Long? = runCatching {
            val f = JSONObject(geojson).getJSONArray("features")
            (0 until f.length()).mapNotNull { f.getJSONObject(it).optJSONObject("properties")?.optLong("Timestamp")?.takeIf { t -> t > 0 } }.maxOrNull()
        }.getOrNull()

        /** The property [prop] of the polygon (or multipolygon) containing the spot, the highest if several; null if none. */
        fun valueAt(geojson: String, lat: Double, lon: Double, prop: String): Int? = runCatching {
            val f = JSONObject(geojson).getJSONArray("features")
            var best: Int? = null
            for (i in 0 until f.length()) {
                val ft = f.getJSONObject(i)
                val g = ft.optJSONObject("geometry") ?: continue
                val v = ft.optJSONObject("properties")?.optInt(prop, 0) ?: 0
                if (v <= (best ?: 0)) continue
                val polys = when (g.optString("type")) {
                    "Polygon" -> listOf(g.getJSONArray("coordinates"))
                    "MultiPolygon" -> g.getJSONArray("coordinates").let { a -> List(a.length()) { a.getJSONArray(it) } }
                    else -> emptyList()
                }
                if (polys.any { inside(it, lat, lon) }) best = v
            }
            best
        }.getOrNull()

        /** Even-odd test over a polygon's rings (holes count as outside). */
        private fun inside(rings: JSONArray, lat: Double, lon: Double): Boolean {
            var inside = false
            for (r in 0 until rings.length()) {
                val ring = rings.getJSONArray(r)
                var j = ring.length() - 1
                for (i in 0 until ring.length()) {
                    val xi = ring.getJSONArray(i).getDouble(0); val yi = ring.getJSONArray(i).getDouble(1)
                    val xj = ring.getJSONArray(j).getDouble(0); val yj = ring.getJSONArray(j).getDouble(1)
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }

        /**
         * For the trip plan: air quality at the start and the worst along the route, and the
         * heaviest smoke the satellite saw over it. Null when there's nothing to say.
         */
        fun routeLine(data: AirData?, coords: List<DoubleArray>, now: Long = System.currentTimeMillis()): String? {
            if (data == null || coords.isEmpty()) return null
            if (now - data.fetchedAt > 2 * 86_400_000L) return null   // too old to be useful
            val step = maxOf(1, coords.size / 60)
            val pts = coords.filterIndexed { i, _ -> i % step == 0 } + listOf(coords.last())
            val start = valueAt(data.aqi, coords[0][0], coords[0][1], "gridcode")
            val worst = pts.mapNotNull { valueAt(data.aqi, it[0], it[1], "gridcode") }.maxOrNull()
            val smoke = pts.mapNotNull { valueAt(data.smoke, it[0], it[1], "level") }.maxOrNull()
            val fmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
            val parts = mutableListOf<String>()
            if (start != null || worst != null) {
                var s = "Air quality (EPA AirNow, PM2.5, ${fmt.format(Date(data.aqiAt))}): "
                s += categoryName(start ?: worst!!)!!.lowercase().replaceFirstChar { it.uppercase() } + (if (start != null) " at the start" else " along the route")
                if (start != null && worst != null && worst > start) s += ", ${categoryName(worst)!!.lowercase()} in places along the route"
                parts += "$s."
            }
            data.smokeDay?.let { day ->
                val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) }.getOrNull()
                val label = d?.let { SimpleDateFormat("MMM d", Locale.US).format(it) } ?: day
                parts += if (smoke != null) "Smoke seen by satellite on $label: ${DENSITY[smoke - 1].lowercase()}, over part of the route."
                    else "No smoke seen by satellite over the route on $label."
            }
            return parts.joinToString(" ").ifBlank { null }
        }
    }
}
