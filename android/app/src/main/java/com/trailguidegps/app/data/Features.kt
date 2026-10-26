package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo
import org.json.JSONObject
import java.io.File

/** Named places from the map build, used to describe where you are. */
class Features private constructor(
    val points: List<Point>,
    val trails: List<Trail>,
) {
    data class Point(val name: String, val kind: String, val lat: Double, val lon: Double)
    data class Trail(val name: String, val coords: List<DoubleArray>)

    /**
     * "On Pinnacles Trail, 0.3 mi N of Kenny Spring" style description, or null
     * if nothing named is within 5 miles.
     */
    fun describe(lat: Double, lon: Double): String? {
        val onTrail = trails
            .map { it to Geo.distanceToLineM(lat, lon, it.coords) }
            .filter { it.second < 46 } // within about 150 ft
            .minByOrNull { it.second }?.first?.name

        val nearest = points
            .map { it to Geo.distanceM(it.lat, it.lon, lat, lon) }
            .minByOrNull { it.second }
            ?.takeIf { it.second < 8000 }

        val near = nearest?.let { (p, d) ->
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
        private val kindNames = mapOf(
            "spring" to "spring", "water_point" to "water point", "drinking_water" to "drinking water",
            "well" to "well", "campsite" to "campsite", "trailhead" to "trailhead", "peak" to "peak",
        )

        fun load(store: MapStore): Features {
            val points = mutableListOf<Point>()
            for (name in listOf("water.geojson", "pois.geojson")) {
                readFeatures(store.file(name)).forEach { f ->
                    val props = f.optJSONObject("properties") ?: return@forEach
                    val geom = f.optJSONObject("geometry") ?: return@forEach
                    if (geom.optString("type") != "Point") return@forEach
                    val label = props.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return@forEach
                    val c = geom.getJSONArray("coordinates")
                    points += Point(label, kindNames[props.optString("kind")] ?: props.optString("kind"), c.getDouble(1), c.getDouble(0))
                }
            }
            val trails = mutableListOf<Trail>()
            readFeatures(store.file("trails.geojson")).forEach { f ->
                val props = f.optJSONObject("properties") ?: return@forEach
                val name = props.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return@forEach
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                trails += Trail(name, (0 until c.length()).map { i ->
                    val p = c.getJSONArray(i)
                    doubleArrayOf(p.getDouble(0), p.getDouble(1))
                })
            }
            return Features(points, trails)
        }

        private fun readFeatures(file: File): List<JSONObject> {
            if (!file.exists()) return emptyList()
            val arr = runCatching { JSONObject(file.readText()).getJSONArray("features") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }
}
