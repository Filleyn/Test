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

    /** A kind of place drawn with icons, switched on and off as one group. */
    class PlaceGroup(val id: String, val kinds: List<String>, val iconMinZoom: Double? = null, val labels: Boolean = true)

    val PLACE_GROUPS = listOf(
        PlaceGroup("campsites", listOf("campsite")),
        PlaceGroup("trailheads", listOf("trailhead")),
        // Restrooms are everywhere in towns, so they only show when zoomed in.
        PlaceGroup("restrooms", listOf("toilets"), iconMinZoom = 13.0, labels = false),
        PlaceGroup("other", listOf("shelter", "viewpoint", "waterfall", "hot_spring", "pass", "saddle")),
    )

    /** Layers you can tap for details. */
    val TAP_LAYERS: Array<String> = (listOf("water-points", "peaks", "trail-hit", "trail-hit-unnamed") +
        PLACE_GROUPS.map { "places-${it.id}" }).toTypedArray()

    /** Layer groups the layers sheet can switch on and off. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "hillshade" to listOf("hillshade"),
        "contours" to listOf("contours-minor", "contours-major", "contour-labels"),
        "trails" to listOf("trail", "trail-names", "trail-hit", "trail-hit-unnamed"),
        "water" to listOf("water-points", "water-labels"),
        "peaks" to listOf("peaks", "peak-labels"),
    ) + PLACE_GROUPS.associate { g -> g.id to listOfNotNull("places-${g.id}", if (g.labels) "place-labels-${g.id}" else null) }

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
            // Your own data, filled in by the app after the style loads.
            .put(SRC_SHOWN_TRACK, emptyGeoJson())
            .put(SRC_SHOWN_ROUTE, emptyGeoJson())
            .put(SRC_PLAN, emptyGeoJson())
            .put(SRC_PLAN_POINTS, emptyGeoJson())
            .put(SRC_RECORDING, emptyGeoJson())
            .put(SRC_WAYPOINTS, emptyGeoJson())

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
        fun inKind(values: List<String>) = arr("in", arr("get", "kind"), arr("literal", JSONArray(values)))
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
        // One layer per place group so each can be switched on and off on its own.
        PLACE_GROUPS.forEach { g ->
            layer("places-${g.id}", "symbol", "pois", filter = inKind(g.kinds),
                layout = JSONObject().put("icon-image", arr("concat", "poi-", arr("get", "kind")))
                    .put("icon-allow-overlap", true).put("icon-size", 1),
                minzoom = g.iconMinZoom)
            if (g.labels) {
                layer("place-labels-${g.id}", "symbol", "pois", filter = arr("all", arr("has", "name"), inKind(g.kinds)),
                    paint = halo("#FFFFFF", "#23271F"),
                    layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 11).put("text-offset", arr(0, 1.1))
                        .put("text-anchor", "top").put("text-field", arr("get", "name")).put("text-optional", true),
                    minzoom = 12.5)
            }
        }
        layer("water-points", "symbol", "water", layout = JSONObject()
            .put("icon-image", arr("case", arr("==", arr("get", "seasonal"), true), "water-seasonal",
                arr("match", arr("get", "kind"), "drinking_water", "water-tap", "water_point", "water-point", "well", "water-well", "water-spring")))
            .put("icon-allow-overlap", true).put("icon-size", 1))
        layer("water-labels", "symbol", "water", paint = halo("#FFFFFF", "#1F5F92"),
            layout = JSONObject().put("text-font", arr(ITALIC)).put("text-size", 11).put("text-offset", arr(0, 1.4))
                .put("text-anchor", "top")
                .put("text-field", arr("coalesce", arr("get", "name"),
                    arr("match", arr("get", "kind"), "spring", "Spring", "well", "Well", "Water"))),
            minzoom = 12.0)

        // Your tracks and waypoints draw above everything else.
        layer("shown-track-casing", "line", SRC_SHOWN_TRACK, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 7),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("shown-track", "line", SRC_SHOWN_TRACK, paint = JSONObject().put("line-color", "#8E3218").put("line-width", 4),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        // Routes are purple: saved routes solid, the route being planned on top with straight legs dashed.
        layer("shown-route-casing", "line", SRC_SHOWN_ROUTE, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 7.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("shown-route", "line", SRC_SHOWN_ROUTE, paint = JSONObject().put("line-color", ROUTE_PURPLE).put("line-width", 4.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("plan-casing", "line", SRC_PLAN, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 8),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("plan-trail", "line", SRC_PLAN, filter = arr("==", arr("get", "trail"), true),
            paint = JSONObject().put("line-color", ROUTE_PURPLE).put("line-width", 4.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("plan-straight", "line", SRC_PLAN, filter = arr("==", arr("get", "trail"), false),
            paint = JSONObject().put("line-color", ROUTE_PURPLE).put("line-width", 3.5).put("line-dasharray", arr(2, 1.5)),
            layout = JSONObject().put("line-cap", "round"))
        layer("plan-points", "circle", SRC_PLAN_POINTS, paint = JSONObject()
            .put("circle-radius", arr("match", arr("get", "role"), "mid", 6, 8))
            .put("circle-color", arr("match", arr("get", "role"), "start", "#2E8B57", "end", ROUTE_PURPLE, "#FFFFFF"))
            .put("circle-stroke-color", arr("match", arr("get", "role"), "mid", ROUTE_PURPLE, "#FFFFFF"))
            .put("circle-stroke-width", 3))
        layer("recording-casing", "line", SRC_RECORDING, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 8),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("recording", "line", SRC_RECORDING, paint = JSONObject().put("line-color", "#E0661F").put("line-width", 4.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer(LAYER_WAYPOINTS, "circle", SRC_WAYPOINTS, paint = JSONObject().put("circle-radius", 8)
            .put("circle-color", arr("get", "color")).put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 2.5))
        layer("waypoint-labels", "symbol", SRC_WAYPOINTS, paint = halo("#FFFFFF", "#23271F"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 12).put("text-offset", arr(0, 1.2))
                .put("text-anchor", "top").put("text-field", arr("get", "name")).put("text-optional", true))

        return JSONObject()
            .put("version", 8)
            .put("name", "TrailGuide Topo")
            .put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    const val SRC_SHOWN_TRACK = "user-shown-track"
    const val SRC_SHOWN_ROUTE = "user-shown-route"
    const val SRC_PLAN = "user-plan"
    const val SRC_PLAN_POINTS = "user-plan-points"
    const val ROUTE_PURPLE = "#6B3FA0"
    const val SRC_RECORDING = "user-recording"
    const val SRC_WAYPOINTS = "user-waypoints"
    const val LAYER_WAYPOINTS = "user-waypoints"

    private fun emptyGeoJson() = JSONObject().put("type", "geojson")
        .put("data", JSONObject().put("type", "FeatureCollection").put("features", JSONArray()))

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }
}
