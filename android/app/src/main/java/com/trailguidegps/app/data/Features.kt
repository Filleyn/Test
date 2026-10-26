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
    data class Point(val name: String, val kind: String, val lat: Double, val lon: Double)
    class Trail(val name: String, val coords: List<DoubleArray>)

    private val pointGrid = HashMap<Long, MutableList<Int>>()
    private val trailGrid = HashMap<Long, MutableList<Int>>()

    init {
        points.forEachIndexed { i, p -> pointGrid.getOrPut(cell(p.lat, p.lon)) { mutableListOf() }.add(i) }
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
            if (d < 30) "at ${p.name}"
            else "${Geo.formatDistance(d)} ${Geo.compassPoint(Geo.bearingDeg(p.lat, p.lon, lat, lon))} of ${p.name}"
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

        private fun key(row: Long, col: Long) = (row shl 32) xor (col and 0xffffffffL)
        private fun cell(lat: Double, lon: Double) = key(floor(lat / CELL).toLong(), floor(lon / CELL).toLong())

        fun load(store: MapStore): Features {
            val points = mutableListOf<Point>()
            for (name in listOf("water.geojson", "pois.geojson")) {
                readFeatures(store.file(name)) { props, type, coords ->
                    val label = props["name"]
                    if (type == "Point" && !label.isNullOrBlank() && coords.isNotEmpty()) {
                        points += Point(label, props["kind"] ?: "", coords[0][1], coords[0][0])
                    }
                }
            }
            val trails = mutableListOf<Trail>()
            readFeatures(store.file("trails.geojson")) { props, type, coords ->
                val name = props["name"]
                if (type == "LineString" && !name.isNullOrBlank() && coords.size >= 2) trails += Trail(name, coords)
            }
            return Features(points, trails)
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
