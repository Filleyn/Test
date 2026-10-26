package com.trailguidegps.app.map

import com.trailguidegps.app.data.MapStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the offline topo style. Everything points at files on the phone:
 * PMTiles map tiles, GeoJSON layers, and label fonts bundled in the app.
 */
object MapStyle {
    const val GROUND = "#EFEEE2"

    /** Layer groups the layers sheet can switch on and off. */
    val GROUPS = linkedMapOf(
        "hillshade" to listOf("hillshade"),
        "contours" to listOf("contours-minor", "contours-major", "contour-labels"),
        "trails" to listOf("trail", "trail-names", "trail-hit", "trail-hit-unnamed"),
        "water" to listOf("water-points", "water-labels"),
        "places" to listOf("places", "place-labels", "peaks", "peak-labels"),
    )

    private const val REGULAR = "NotoSans-Regular"
    private const val MEDIUM = "NotoSans-Medium"
    private const val ITALIC = "NotoSans-Italic"

    fun build(store: MapStore): String {
        fun pm(name: String) = "pmtiles://file://" + store.file(name).absolutePath
        fun gj(name: String) = "file://" + store.file(name).absolutePath

        val sources = JSONObject()
            .put("topo", JSONObject().put("type", "vector").put("url", pm("topo.pmtiles"))
                .put("attribution", "© OpenStreetMap contributors"))
            .put("contours", JSONObject().put("type", "vector").put("url", pm("contours.pmtiles"))
                .put("attribution", "USGS 3DEP"))
            .put("terrain", JSONObject().put("type", "raster-dem").put("url", pm("terrain.pmtiles"))
                .put("encoding", "mapbox").put("tileSize", 256))
            .put("water", JSONObject().put("type", "geojson").put("data", gj("water.geojson")))
            .put("pois", JSONObject().put("type", "geojson").put("data", gj("pois.geojson")))

        val roads = arr("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")
        val trails = arr("path", "track")
        val layers = JSONArray()
        fun layer(id: String, type: String, source: String? = null, sourceLayer: String? = null,
                  filter: Any? = null, paint: JSONObject? = null, layout: JSONObject? = null, minzoom: Double? = null) {
            val l = JSONObject().put("id", id).put("type", type)
            source?.let { l.put("source", it) }
            sourceLayer?.let { l.put("source-layer", it) }
            filter?.let { l.put("filter", it) }
            paint?.let { l.put("paint", it) }
            layout?.let { l.put("layout", it) }
            minzoom?.let { l.put("minzoom", it) }
            layers.put(l)
        }
        fun inClass(values: JSONArray) = arr("in", arr("get", "class"), arr("literal", values))
        fun byZoom(vararg stops: Number) = JSONArray().put("interpolate").put(arr("linear")).put(arr("zoom")).apply { stops.forEach { put(it) } }
        fun halo(color: String, textColor: String, width: Double = 1.6) =
            JSONObject().put("text-color", textColor).put("text-halo-color", color).put("text-halo-width", width)

        layer("ground", "background", paint = JSONObject().put("background-color", GROUND))
        layer("landcover-wood", "fill", "topo", "landcover", inClass(arr("wood", "forest")),
            JSONObject().put("fill-color", "#D5E3C3"))
        layer("landcover-grass", "fill", "topo", "landcover", inClass(arr("grass", "farmland", "scrub")),
            JSONObject().put("fill-color", "#E7EBD6"))
        layer("landcover-rock", "fill", "topo", "landcover", inClass(arr("rock", "sand")),
            JSONObject().put("fill-color", "#E6E0D2"))
        layer("park", "line", "topo", "park", paint = JSONObject().put("line-color", "#5E8A4A")
            .put("line-width", 1.5).put("line-dasharray", arr(3, 2)).put("line-opacity", 0.7))
        layer("hillshade", "hillshade", "terrain", paint = JSONObject()
            .put("hillshade-exaggeration", 0.45).put("hillshade-shadow-color", "#4E4232")
            .put("hillshade-highlight-color", "#FFFFFF").put("hillshade-accent-color", "#6B5A44"))
        layer("contours-minor", "line", "contours", "contours", arr("==", arr("get", "major"), 0),
            JSONObject().put("line-color", "#B88A5B").put("line-width", 0.6).put("line-opacity", 0.5))
        layer("contours-major", "line", "contours", "contours", arr("==", arr("get", "major"), 1),
            JSONObject().put("line-color", "#A47448").put("line-width", 1.2).put("line-opacity", 0.85))
        layer("water", "fill", "topo", "water", paint = JSONObject().put("fill-color", "#A9CFEA"))
        layer("waterway", "line", "topo", "waterway", paint = JSONObject().put("line-color", "#4A8FC7")
            .put("line-width", arr("match", arr("get", "class"), "river", 2.5, 1)))
        layer("road-casing", "line", "topo", "transportation", inClass(roads),
            JSONObject().put("line-color", "#FFFFFF").put("line-width", byZoom(10, 2, 15, 7)))
        layer("road", "line", "topo", "transportation", inClass(roads),
            JSONObject().put("line-color", "#C9B48F").put("line-width", byZoom(10, 1, 15, 4)))
        layer("trail", "line", "topo", "transportation", inClass(trails),
            JSONObject().put("line-color", "#B5462A").put("line-width", byZoom(10, 1.2, 15, 2.8))
                .put("line-dasharray", arr(3, 1.5)))
        // Invisible wide lines so trails are easy to tap (named ones carry their name here).
        layer("trail-hit", "line", "topo", "transportation_name", inClass(trails),
            JSONObject().put("line-color", "#000000").put("line-opacity", 0).put("line-width", 18))
        layer("trail-hit-unnamed", "line", "topo", "transportation", inClass(trails),
            JSONObject().put("line-color", "#000000").put("line-opacity", 0).put("line-width", 18))
        layer("contour-labels", "symbol", "contours", "contours", arr("==", arr("get", "major"), 1),
            halo(GROUND, "#8A6440", 1.4),
            JSONObject().put("symbol-placement", "line").put("text-font", arr(REGULAR))
                .put("text-size", 10).put("text-field", arr("concat", arr("to-string", arr("get", "ele_ft")), " ft")),
            minzoom = 12.0)
        layer("waterway-names", "symbol", "topo", "waterway", arr("has", "name"),
            halo(GROUND, "#1F5F92"),
            JSONObject().put("symbol-placement", "line").put("text-font", arr(ITALIC))
                .put("text-size", 13).put("text-letter-spacing", 0.08).put("text-field", arr("get", "name")))
        layer("water-names", "symbol", "topo", "water_name", paint = halo(GROUND, "#1F5F92"),
            layout = JSONObject().put("text-font", arr(ITALIC)).put("text-size", 12).put("text-field", arr("get", "name")))
        layer("trail-names", "symbol", "topo", "transportation_name", inClass(trails),
            halo("#FFFFFF", "#8E3218"),
            JSONObject().put("symbol-placement", "line").put("text-font", arr(MEDIUM))
                .put("text-size", 12).put("text-field", arr("get", "name")), minzoom = 12.0)
        layer("place-names", "symbol", "topo", "place", paint = halo("#FFFFFF", "#23271F"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 13).put("text-field", arr("get", "name")))
        layer("peaks", "circle", "topo", "mountain_peak", paint = JSONObject().put("circle-radius", 4)
            .put("circle-color", "#5A4636").put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 1.5))
        layer("peak-labels", "symbol", "topo", "mountain_peak", paint = halo("#FFFFFF", "#4A3A2C"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 11).put("text-offset", arr(0, 1.1))
                .put("text-anchor", "top")
                .put("text-field", arr("case", arr("has", "ele_ft"),
                    arr("concat", arr("coalesce", arr("get", "name"), "Peak"), "\n", arr("to-string", arr("get", "ele_ft")), " ft"),
                    arr("coalesce", arr("get", "name"), ""))))
        layer("places", "circle", "pois", filter = arr("!=", arr("get", "kind"), "peak"), paint = JSONObject()
            .put("circle-radius", 6)
            .put("circle-color", arr("match", arr("get", "kind"), "campsite", "#B4491A", "trailhead", "#2F7A4B", "#6B716A"))
            .put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 2))
        layer("place-labels", "symbol", "pois", filter = arr("all", arr("has", "name"), arr("!=", arr("get", "kind"), "peak")),
            paint = halo("#FFFFFF", "#23271F"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 11).put("text-offset", arr(0, 1.1))
                .put("text-anchor", "top").put("text-field", arr("get", "name")), minzoom = 12.5)
        layer("water-points", "circle", "water", paint = JSONObject().put("circle-radius", 7)
            .put("circle-color", "#2E7DBF").put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 2))
        layer("water-labels", "symbol", "water", paint = halo("#FFFFFF", "#1F5F92"),
            layout = JSONObject().put("text-font", arr(ITALIC)).put("text-size", 11).put("text-offset", arr(0, 1.2))
                .put("text-anchor", "top")
                .put("text-field", arr("coalesce", arr("get", "name"),
                    arr("match", arr("get", "kind"), "spring", "Spring", "well", "Well", "Water"))),
            minzoom = 12.0)

        return JSONObject()
            .put("version", 8)
            .put("name", "TrailGuide Topo")
            .put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }
}
