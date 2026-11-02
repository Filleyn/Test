package com.trailguidegps.app.data

import android.util.JsonReader
import android.util.JsonToken
import com.trailguidegps.app.geo.Geo
import java.io.File
import kotlin.math.floor

/**
 * Named places from the map build, used to describe where you are.
 * Statewide files are large, so they are read with a streaming parser and
 * indexed on a coarse grid for quick nearby lookups.
 */
class Features private constructor(
    private val points: List<Point>,
    private val trails: List<Trail>,
) {
    /** A named or unnamed point: water source, campsite, peak, lake, town and so on. */
    data class Point(
        val name: String?,
        val kind: String,
        val lat: Double,
        val lon: Double,
        val osmId: String? = null,
        val seasonal: Boolean = false,
        val lastEdit: String? = null,
        val isWater: Boolean = false,
        /** For towns: city, town, village or hamlet. */
        val place: String? = null,
        val population: Int? = null,
        /** The place's official web page, when mappers added one. */
        val website: String? = null,
        /** For lakes: size, so distance can be measured to the shore rather than the middle. */
        val acres: Double? = null,
        /** Elevation in feet, for peaks (from the map data, or the terrain if mappers left it out). */
        val eleFt: Double? = null,
        /** Which state's maps it came from ("oregon"). */
        val region: String? = null,
    ) {
        val label: String get() = name ?: (Features.KIND_LABELS[kind] ?: kind.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
    class Trail(val name: String, val coords: List<DoubleArray>, val region: String? = null)

    private val pointGrid = HashMap<Long, MutableList<Int>>()
    private val trailGrid = HashMap<Long, MutableList<Int>>()

    /** Every point, including unnamed water sources, for "nearest" lists and search. */
    val allPoints: List<Point> get() = points

    /** Towns, for "14 mi NE of Sisters" style labels. */
    private val towns: List<Point> = points.filter { it.kind == "town" && it.name != null }

    /**
     * Where a point is, relative to a town people would recognise: the nearest
     * city or town within 30 mi, else the nearest village within 15 mi, else
     * the nearest place of any size. Null if nothing is within 60 mi.
     */
    fun nearTown(lat: Double, lon: Double, exclude: String? = null): String? {
        if (towns.isEmpty()) return null
        val knownSizes = towns.any { it.place != null }
        fun rank(t: Point): Int = when {
            !knownSizes -> 1
            t.place == "city" || t.place == "town" || (t.population ?: 0) >= 1000 -> 1
            t.place == "village" -> 2
            else -> 3
        }
        val withDist = towns.asSequence()
            .filter { it.name != exclude }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
        val pick = withDist.filter { rank(it.first) == 1 && it.second <= 48_000 }.minByOrNull { it.second }
            ?: withDist.filter { rank(it.first) <= 2 && it.second <= 24_000 }.minByOrNull { it.second }
            ?: withDist.filter { it.second <= 96_000 }.minByOrNull { it.second }
            ?: return null
        val (t, d) = pick
        return if (d < 1200) "in ${t.name}"
        else "${Geo.formatDistance(d)} ${Geo.compassPoint(Geo.bearingDeg(t.lat, t.lon, lat, lon))} of ${t.name}"
    }

    /** Water sources and lakes on a grid, for "is there water near this campsite". */
    private val waterGrid: Map<Long, List<Point>> by lazy {
        points.filter { it.isWater || it.kind == "lake" }.groupBy { cell(it.lat, it.lon) }
    }

    /**
     * The closest water within [maxM] of a spot, and how far it is (lakes from their
     * shore, roughly). Seasonal sources count only if [seasonalOk].
     */
    fun waterNear(lat: Double, lon: Double, maxM: Double, seasonalOk: Boolean = true): Pair<Point, Double>? =
        nearbyCells(lat, lon, 1).asSequence().flatMap { waterGrid[it].orEmpty().asSequence() }
            .filter { seasonalOk || !it.seasonal }
            .map { w ->
                val radius = w.acres?.let { kotlin.math.sqrt(it * 4046.86 / Math.PI) } ?: 0.0
                w to (Geo.distanceM(lat, lon, w.lat, w.lon) - radius).coerceAtLeast(0.0)
            }
            .filter { it.second <= maxM }
            .minByOrNull { it.second }

    /** Named trails grouped by name; one trail name often spans many map segments. */
    val trailsByName: Map<String, List<Trail>> by lazy { trails.groupBy { it.name } }

    init {
        points.forEachIndexed { i, p -> if (p.name != null) pointGrid.getOrPut(cell(p.lat, p.lon)) { mutableListOf() }.add(i) }
        trails.forEachIndexed { i, t ->
            val cells = HashSet<Long>()
            t.coords.forEach { c -> cells += cell(c[1], c[0]) }
            cells.forEach { trailGrid.getOrPut(it) { mutableListOf() }.add(i) }
        }
    }

    private fun nearbyCells(lat: Double, lon: Double, ring: Int): List<Long> {
        val r0 = floor(lat / CELL).toLong()
        val c0 = floor(lon / CELL).toLong()
        val out = ArrayList<Long>()
        for (dr in -ring..ring) for (dc in -ring..ring) out += key(r0 + dr, c0 + dc)
        return out
    }

    /**
     * "On Pinnacles Trail, 0.3 mi N of Kenny Spring" style description, or null
     * if nothing named is within about 5 miles.
     */
    fun describe(lat: Double, lon: Double): String? {
        val trailIdx = nearbyCells(lat, lon, 1).flatMap { trailGrid[it].orEmpty() }.toSet()
        val onTrail = trailIdx
            .map { trails[it] to Geo.distanceToLineM(lat, lon, trails[it].coords) }
            .filter { it.second < 46 } // within about 150 ft
            .minByOrNull { it.second }?.first?.name

        var nearest: Pair<Point, Double>? = null
        for (ring in listOf(1, 3)) {
            nearest = nearbyCells(lat, lon, ring).flatMap { pointGrid[it].orEmpty() }
                .map { points[it] to Geo.distanceM(points[it].lat, points[it].lon, lat, lon) }
                .minByOrNull { it.second }
            if (nearest != null) break
        }
        val near = nearest?.takeIf { it.second < 8000 }?.let { (p, d) ->
            if (d < 30) "at ${p.label}"
            else "${Geo.formatDistance(d)} ${Geo.compassPoint(Geo.bearingDeg(p.lat, p.lon, lat, lon))} of ${p.label}"
        }
        return when {
            onTrail != null && near != null -> "On $onTrail, $near"
            onTrail != null -> "On $onTrail"
            near != null -> near.replaceFirstChar { it.uppercase() }
            else -> null
        }
    }

    companion object {
        private const val CELL = 0.02 // degrees, about 1.4 mi

        val KIND_LABELS = mapOf(
            "spring" to "Spring", "water_point" to "Water point", "drinking_water" to "Drinking water",
            "well" to "Well", "campsite" to "Campsite", "trailhead" to "Trailhead", "toilets" to "Restroom",
            "viewpoint" to "Viewpoint", "peak" to "Peak", "lake" to "Lake", "town" to "Town",
            "waterfall" to "Waterfall", "hot_spring" to "Hot spring", "saddle" to "Saddle", "pass" to "Pass",
            "shelter" to "Shelter", "trail" to "Trail", "track" to "Dirt or forest road",
            "stream" to "Stream crossing", "lookout" to "Fire lookout", "historic" to "Historic site",
        )

        private fun key(row: Long, col: Long) = (row shl 32) xor (col and 0xffffffffL)
        private fun cell(lat: Double, lon: Double) = key(floor(lat / CELL).toLong(), floor(lon / CELL).toLong())

        fun load(store: MapStore): Features = load(listOf(store))

        /** Places and trails from every downloaded state, each tagged with its state. */
        fun load(stores: List<MapStore>): Features {
            val points = mutableListOf<Point>()
            val trails = mutableListOf<Trail>()
            for (store in stores) loadInto(store, points, trails)
            return Features(points, trails)
        }

        private fun loadInto(store: MapStore, points: MutableList<Point>, trails: MutableList<Trail>) {
            for (name in listOf("water.geojson", "pois.geojson")) {
                val isWater = name == "water.geojson"
                readFeatures(store.file(name)) { props, type, coords ->
                    val label = props["name"]?.takeIf { it.isNotBlank() }
                    // Unnamed water still matters ("nearest water"); other unnamed places don't.
                    if (type == "Point" && coords.isNotEmpty() && (label != null || isWater)) {
                        points += Point(
                            label, props["kind"] ?: "", coords[0][1], coords[0][0],
                            osmId = props["osm_id"], seasonal = props["seasonal"] == "true",
                            lastEdit = props["last_edit"], isWater = isWater,
                            place = props["place"], population = props["population"]?.toDoubleOrNull()?.toInt(),
                            website = props["website"], acres = props["acres"]?.toDoubleOrNull(),
                            eleFt = props["ele_ft"]?.toDoubleOrNull(), region = store.region,
                        )
                    }
                }
            }
            readFeatures(store.file("trails.geojson")) { props, type, coords ->
                val name = props["name"]
                if (type == "LineString" && !name.isNullOrBlank() && coords.size >= 2) trails += Trail(name, coords, store.region)
            }
        }

        /** Streams a GeoJSON FeatureCollection of Points and LineStrings. */
        private fun readFeatures(file: File, onFeature: (Map<String, String?>, String, List<DoubleArray>) -> Unit) {
            if (!file.exists()) return
            JsonReader(file.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "features") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var props: Map<String, String?> = emptyMap()
                        var type = ""
                        var coords: List<DoubleArray> = emptyList()
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "properties" -> props = readProps(r)
                                "geometry" -> {
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        when (r.nextName()) {
                                            "type" -> type = r.nextString()
                                            "coordinates" -> coords = readCoords(r)
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        onFeature(props, type, coords)
                    }
                    r.endArray()
                }
                r.endObject()
            }
        }

        private fun readProps(r: JsonReader): Map<String, String?> {
            if (r.peek() == JsonToken.NULL) { r.nextNull(); return emptyMap() }
            val m = HashMap<String, String?>()
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                when (r.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                    JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                    JsonToken.NULL -> { r.nextNull(); m[k] = null }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return m
        }

        /** Point → one pair; LineString → list of pairs. */
        private fun readCoords(r: JsonReader): List<DoubleArray> {
            r.beginArray()
            if (r.peek() == JsonToken.NUMBER) {
                val x = r.nextDouble(); val y = r.nextDouble()
                while (r.hasNext()) r.skipValue()
                r.endArray()
                return listOf(doubleArrayOf(x, y))
            }
            val out = ArrayList<DoubleArray>()
            while (r.hasNext()) {
                r.beginArray()
                val x = r.nextDouble(); val y = r.nextDouble()
                while (r.hasNext()) r.skipValue()
                r.endArray()
                out += doubleArrayOf(x, y)
            }
            r.endArray()
            return out
        }
    }
}
