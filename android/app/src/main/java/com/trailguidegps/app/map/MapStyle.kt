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
    class PlaceGroup(
        val id: String, val kinds: List<String>, val iconMinZoom: Double? = null, val labels: Boolean = true,
        /** Places the map build worked out itself (a trail meeting a road) show only from this zoom. */
        val derivedMinZoom: Double? = null,
    ) {
        val layers: List<String> get() = listOfNotNull(
            "places-$id", if (derivedMinZoom != null) "places-$id-derived" else null,
            if (labels) "place-labels-$id" else null,
        )
    }

    val PLACE_GROUPS = listOf(
        PlaceGroup("campsites", listOf("campsite")),
        PlaceGroup("trailheads", listOf("trailhead"), derivedMinZoom = 11.0),
        // Restrooms are everywhere in towns, so they only show when zoomed in.
        PlaceGroup("restrooms", listOf("toilets"), iconMinZoom = 13.0, labels = false),
        PlaceGroup("waterfalls", listOf("waterfall")),
        PlaceGroup("shelters", listOf("shelter")),
        PlaceGroup("other", listOf("viewpoint", "hot_spring", "pass", "saddle")),
    )

    /** Layers you can tap for details. */
    val TAP_LAYERS: Array<String> = (listOf("water-points", "peaks", "trail-hit", "trail-hit-unnamed") +
        PLACE_GROUPS.flatMap { g -> g.layers.filter { it.startsWith("places-") } }).toTypedArray()

    /** Layer groups the layers sheet can switch on and off. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "hillshade" to listOf("hillshade"),
        "contours" to listOf("contours-minor", "contours-major", "contour-labels"),
        "trails" to listOf("trail", "track", "trail-names", "trail-hit", "trail-hit-unnamed"),
        "water" to listOf("water-points", "water-labels"),
        "peaks" to listOf("peaks", "peak-labels"),
    ) + PLACE_GROUPS.associate { g -> g.id to g.layers } + linkedMapOf(
        "landmanager" to listOf(LAYER_LAND_FILL),
        "forests" to listOf(LAYER_BOUNDARY_HIT, "boundary-line", "boundary-line-labels", "boundary-point-labels"),
        // The saved-area layers are added per area; MapScreen finds them by the "satellite" prefix.
        "satellite" to listOf("satellite-online"),
        "fires" to listOf(LAYER_FIRE_FILL, "fire-line", LAYER_FIRE_POINTS, "fire-labels"),
        "wilderness" to listOf(LAYER_SPECIAL_FILL, "special-line", "special-line-other", "special-line-labels", "special-point-labels"),
    )

    /** Layer groups that start switched off. */
    val DEFAULT_OFF = setOf("landmanager", "satellite")

    /** Tap these (in order) for who manages the land, when nothing else was tapped. */
    val LAND_TAP_LAYERS = arrayOf(LAYER_SPECIAL_FILL, LAYER_BOUNDARY_HIT, LAYER_LAND_FILL)

    /** Fill colors by land manager, the usual public-land map colors. */
    private val MANAGER_COLORS = linkedMapOf(
        "USFS" to "#7FB843", "BLM" to "#F5C83A", "NPS" to "#A98BD4", "FWS" to "#F49C7A",
        "USBR" to "#F7D9A8", "USACE" to "#C792D8", "DOD" to "#E88A8A", "OTHF" to "#B8A79A",
        "TRIB" to "#D2A46C", "NGO" to "#B5B5B5", "PVT" to "#B5B5B5",
    )
    private val MANAGER_TYPE_COLORS = linkedMapOf(
        "FED" to "#B8A79A", "STAT" to "#5DA9E9", "LOC" to "#8FA6B5", "DIST" to "#8FA6B5",
        "TRIB" to "#D2A46C", "JNT" to "#7FC8BF",
    )

    private const val REGULAR = "NotoSans-Regular"
    private const val MEDIUM = "NotoSans-Medium"
    private const val ITALIC = "NotoSans-Italic"

    /** Saved satellite areas to draw: (area id, its PMTiles file). */
    fun build(store: MapStore, satelliteAreas: List<Pair<String, java.io.File>> = emptyList()): String {
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
            .apply {
                if (store.file("publiclands.pmtiles").exists()) {
                    put("publiclands", JSONObject().put("type", "vector").put("url", pm("publiclands.pmtiles"))
                        .put("attribution", "Public land: USGS PAD-US"))
                }
            }
            // Your own data, filled in by the app after the style loads.
            .put(SRC_SHOWN_TRACK, emptyGeoJson())
            .put(SRC_SHOWN_ROUTE, emptyGeoJson())
            .put(SRC_PLAN, emptyGeoJson())
            .put(SRC_PLAN_POINTS, emptyGeoJson())
            .put(SRC_PROFILE_MARK, emptyGeoJson())
            .put(SRC_OFF_ROUTE, emptyGeoJson())
            .put(SRC_FIRE_PERIMS, emptyGeoJson())
            .put(SRC_FIRE_POINTS, emptyGeoJson())
            .put(SRC_RECORDING, emptyGeoJson())
            .put(SRC_WAYPOINTS, emptyGeoJson())

        val roads = arr("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")
        val trails = arr("path", "track")
        val layers = JSONArray()
        fun layer(id: String, type: String, source: String? = null, sourceLayer: String? = null,
                  filter: Any? = null, paint: JSONObject? = null, layout: JSONObject? = null, minzoom: Double? = null,
                  maxzoom: Double? = null) {
            val l = JSONObject().put("id", id).put("type", type)
            source?.let { l.put("source", it) }
            sourceLayer?.let { l.put("source-layer", it) }
            filter?.let { l.put("filter", it) }
            paint?.let { l.put("paint", it) }
            layout?.let { l.put("layout", it) }
            minzoom?.let { l.put("minzoom", it) }
            maxzoom?.let { l.put("maxzoom", it) }
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
        // Public land: who manages it, under the hillshade so the terrain still reads.
        val hasLand = store.file("publiclands.pmtiles").exists()
        if (hasLand) {
            val mgrColor = JSONArray().put("match").put(arr("get", "mgr")).apply {
                MANAGER_COLORS.forEach { (k, v) -> put(k); put(v) }
                put(JSONArray().put("match").put(arr("get", "mgr_type")).apply {
                    MANAGER_TYPE_COLORS.forEach { (k, v) -> put(k); put(v) }
                    put("#C8C8C8")
                })
            }
            layer(LAYER_LAND_FILL, "fill", "publiclands", "land", paint = JSONObject()
                .put("fill-color", mgrColor).put("fill-opacity", byZoom(6, 0.45, 13, 0.3)))
            layer(LAYER_SPECIAL_FILL, "fill", "publiclands", "special", paint = JSONObject()
                .put("fill-color", "#2E5E1E").put("fill-opacity", 0.07))
            layer(LAYER_BOUNDARY_HIT, "fill", "publiclands", "boundary", paint = JSONObject()
                .put("fill-color", "#000000").put("fill-opacity", HIT_OPACITY))
        }
        layer("hillshade", "hillshade", "terrain", paint = JSONObject()
            .put("hillshade-exaggeration", 0.45).put("hillshade-shadow-color", "#4E4232")
            .put("hillshade-highlight-color", "#FFFFFF").put("hillshade-accent-color", "#6B5A44"))
        // Satellite: USGS imagery online, with saved areas on top so they show offline. Off until switched on.
        sources.put("satellite-online", JSONObject().put("type", "raster")
            .put("tiles", arr(com.trailguidegps.app.data.SatelliteStore.TILE_URL + "/{z}/{y}/{x}"))
            .put("tileSize", 256).put("maxzoom", com.trailguidegps.app.data.SatelliteStore.MAX_IMAGERY_ZOOM)
            .put("attribution", "Imagery: USGS The National Map"))
        val hidden = JSONObject().put("visibility", "none")
        val rasterPaint = JSONObject().put("raster-fade-duration", 0)
        // Oregon's 1-ft 2024 imagery, online, when zoomed in; drawn over saved areas so it's the sharpest when you have signal.
        sources.put("satellite-online-hd", JSONObject().put("type", "raster")
            .put("tiles", arr(com.trailguidegps.app.data.SatelliteStore.OREGON_TEMPLATE))
            .put("tileSize", 256).put("minzoom", 15).put("maxzoom", 19)
            .put("attribution", "Imagery: Oregon Statewide Imagery Program 2024"))
        layer("satellite-online", "raster", "satellite-online", paint = rasterPaint, layout = hidden)
        satelliteAreas.forEach { (id, f) ->
            sources.put("sat-$id", JSONObject().put("type", "raster").put("url", "pmtiles://file://" + f.absolutePath).put("tileSize", 256))
            layer("satellite-$id", "raster", "sat-$id", paint = JSONObject(rasterPaint.toString()), layout = JSONObject(hidden.toString()))
        }
        layer("satellite-online-hd", "raster", "satellite-online-hd", paint = JSONObject(rasterPaint.toString()),
            layout = JSONObject(hidden.toString()), minzoom = 15.0)
        layer("contours-minor", "line", "contours", "contours", arr("==", arr("get", "major"), 0),
            JSONObject().put("line-color", "#B88A5B").put("line-width", 0.6).put("line-opacity", 0.5))
        layer("contours-major", "line", "contours", "contours", arr("==", arr("get", "major"), 1),
            JSONObject().put("line-color", "#A47448").put("line-width", 1.2).put("line-opacity", 0.85))
        layer("water", "fill", "topo", "water", paint = JSONObject().put("fill-color", "#A9CFEA"))
        layer("waterway", "line", "topo", "waterway", paint = JSONObject().put("line-color", "#4A8FC7")
            .put("line-width", arr("match", arr("get", "class"), "river", 2.5, 1)))
        if (hasLand) {
            // National forest, park, refuge and reservation boundaries; wilderness and similar areas.
            layer("boundary-line", "line", "publiclands", "boundary", paint = JSONObject()
                .put("line-color", arr("match", arr("get", "des"), "TRIBL", "#8A5A2B", "#3E6B2A"))
                .put("line-width", byZoom(6, 1.2, 12, 3.0)).put("line-opacity", 0.75).put("line-dasharray", arr(5, 2)))
            // Wilderness solid, study areas and the rest dashed (dash patterns can't vary per feature).
            layer("special-line", "line", "publiclands", "special", arr("==", arr("get", "des"), "WA"), JSONObject()
                .put("line-color", "#2E5E1E").put("line-width", byZoom(6, 1.2, 12, 2.6)))
            layer("special-line-other", "line", "publiclands", "special", arr("!=", arr("get", "des"), "WA"), JSONObject()
                .put("line-color", "#2E5E1E").put("line-width", byZoom(6, 0.8, 12, 1.8)).put("line-dasharray", arr(3, 2)))
        }
        layer("road-casing", "line", "topo", "transportation", inClass(roads),
            JSONObject().put("line-color", "#FFFFFF").put("line-width", byZoom(10, 2, 15, 7)))
        layer("road", "line", "topo", "transportation", inClass(roads),
            JSONObject().put("line-color", "#C9B48F").put("line-width", byZoom(10, 1, 15, 4)))
        // Dirt and forest roads: solid brown, so they don't look like trails.
        layer("track", "line", "topo", "transportation", inClass(arr("track")),
            JSONObject().put("line-color", "#8A6A43").put("line-width", byZoom(10, 0.9, 15, 2.6)))
        // Trails: dashed red-brown.
        layer("trail", "line", "topo", "transportation", inClass(arr("path")),
            JSONObject().put("line-color", "#B5462A").put("line-width", byZoom(10, 1.2, 15, 2.8))
                .put("line-dasharray", arr(3, 1.5)))
        // Invisible wide lines so trails are easy to tap (named ones carry their name here).
        layer("trail-hit", "line", "topo", "transportation_name", inClass(trails),
            JSONObject().put("line-color", "#000000").put("line-opacity", HIT_OPACITY).put("line-width", 18))
        layer("trail-hit-unnamed", "line", "topo", "transportation", inClass(trails),
            JSONObject().put("line-color", "#000000").put("line-opacity", HIT_OPACITY).put("line-width", 18))
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
            halo("#FFFFFF", "#8E3218").put("text-color", arr("match", arr("get", "class"), "track", "#6B4F2E", "#8E3218")),
            JSONObject().put("symbol-placement", "line").put("text-font", arr(MEDIUM))
                .put("text-size", 12).put("text-field", arr("get", "name")), minzoom = 12.0)
        if (hasLand) {
            val upper = arr("upcase", arr("get", "name"))
            // Names along the boundary when zoomed in, one name in the middle when zoomed out.
            layer("boundary-line-labels", "symbol", "publiclands", "boundary", arr("has", "name"),
                halo("#FFFFFF", "#2F5520", 1.6),
                JSONObject().put("symbol-placement", "line").put("symbol-spacing", 500).put("text-font", arr(MEDIUM))
                    .put("text-size", 11).put("text-letter-spacing", 0.06).put("text-field", upper), minzoom = 10.0)
            layer("special-line-labels", "symbol", "publiclands", "special", arr("has", "name"),
                halo("#FFFFFF", "#2E5E1E", 1.6),
                JSONObject().put("symbol-placement", "line").put("symbol-spacing", 450).put("text-font", arr(ITALIC))
                    .put("text-size", 11).put("text-field", arr("get", "name")), minzoom = 11.0)
            layer("boundary-point-labels", "symbol", "publiclands", "labels", arr("==", arr("get", "layer"), "boundary"),
                halo("#FFFFFF", "#2F5520", 1.6),
                JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 12).put("text-letter-spacing", 0.06)
                    .put("text-field", upper).put("text-max-width", 8), minzoom = 6.0, maxzoom = 10.0)
            layer("special-point-labels", "symbol", "publiclands", "labels", arr("==", arr("get", "layer"), "special"),
                halo("#FFFFFF", "#2E5E1E", 1.6),
                JSONObject().put("text-font", arr(ITALIC)).put("text-size", 11).put("text-field", arr("get", "name"))
                    .put("text-max-width", 8), minzoom = 8.0, maxzoom = 11.0)
        }
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
        val notDerived = arr("!", arr("has", "derived"))
        PLACE_GROUPS.forEach { g ->
            val icon = JSONObject().put("icon-image", arr("concat", "poi-", arr("get", "kind")))
                .put("icon-allow-overlap", true).put("icon-size", 1)
            if (g.derivedMinZoom != null) {
                layer("places-${g.id}", "symbol", "pois", filter = arr("all", inKind(g.kinds), notDerived), layout = icon, minzoom = g.iconMinZoom)
                layer("places-${g.id}-derived", "symbol", "pois", filter = arr("all", inKind(g.kinds), arr("has", "derived")),
                    layout = JSONObject(icon.toString()), minzoom = g.derivedMinZoom)
            } else {
                layer("places-${g.id}", "symbol", "pois", filter = inKind(g.kinds), layout = icon, minzoom = g.iconMinZoom)
            }
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
        // The route whose details are in the bottom panel draws a little wider.
        val focusWidth = { on: Double, off: Double -> arr("case", arr("==", arr("get", "focus"), true), on, off) }
        layer("shown-route-casing", "line", SRC_SHOWN_ROUTE, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", focusWidth(8.0, 6.0)),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer(LAYER_SHOWN_ROUTE_HIT, "line", SRC_SHOWN_ROUTE, paint = JSONObject().put("line-color", "#000000").put("line-opacity", HIT_OPACITY).put("line-width", 20))
        layer("shown-route", "line", SRC_SHOWN_ROUTE, paint = JSONObject()
            .put("line-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE)).put("line-width", focusWidth(5.0, 3.5))
            .put("line-opacity", focusWidth(1.0, 0.55)),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        // Each route's name along its line, so you can tell several apart.
        layer("shown-route-labels", "symbol", SRC_SHOWN_ROUTE,
            paint = JSONObject().put("text-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE))
                .put("text-halo-color", "#FFFFFF").put("text-halo-width", 2),
            layout = JSONObject().put("symbol-placement", "line").put("symbol-spacing", 350).put("text-font", arr(MEDIUM))
                .put("text-size", 12).put("text-field", arr("get", "name")).put("text-optional", true),
            minzoom = 11.0)
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
        // Current wildfires (online data, last copy kept): orange areas, flame-red dots.
        layer(LAYER_FIRE_FILL, "fill", SRC_FIRE_PERIMS, paint = JSONObject().put("fill-color", "#E65100").put("fill-opacity", 0.28))
        layer("fire-line", "line", SRC_FIRE_PERIMS, paint = JSONObject().put("line-color", "#B71C1C").put("line-width", 1.8))
        layer(LAYER_FIRE_POINTS, "circle", SRC_FIRE_POINTS, paint = JSONObject()
            .put("circle-radius", byZoom(5, 4, 12, 8)).put("circle-color", "#D32F2F")
            .put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 2))
        layer("fire-labels", "symbol", SRC_FIRE_POINTS, paint = halo("#FFFFFF", "#B71C1C"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 11).put("text-offset", arr(0, 1.2))
                .put("text-anchor", "top").put("text-optional", true)
                .put("text-field", arr("concat", arr("get", "IncidentName"), " Fire")), minzoom = 7.0)
        layer("off-route", "line", SRC_OFF_ROUTE, paint = JSONObject().put("line-color", "#C62828").put("line-width", 3)
            .put("line-dasharray", arr(1.5, 1.5)), layout = JSONObject().put("line-cap", "round"))
        // Where you're pressing on the elevation graph.
        layer("profile-mark", "circle", SRC_PROFILE_MARK, paint = JSONObject().put("circle-radius", 9)
            .put("circle-color", "#FFFFFF").put("circle-stroke-color", "#23271F").put("circle-stroke-width", 4))
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
    const val LAYER_SHOWN_ROUTE_HIT = "shown-route-hit"
    const val SRC_PLAN = "user-plan"
    const val SRC_PLAN_POINTS = "user-plan-points"
    const val SRC_PROFILE_MARK = "user-profile-mark"
    const val SRC_OFF_ROUTE = "user-off-route"
    const val SRC_FIRE_PERIMS = "fires-perimeters"
    const val SRC_FIRE_POINTS = "fires-points"
    const val LAYER_FIRE_FILL = "fire-fill"
    const val LAYER_FIRE_POINTS = "fire-points"
    const val ROUTE_PURPLE = "#6B3FA0"
    const val SRC_RECORDING = "user-recording"
    const val SRC_WAYPOINTS = "user-waypoints"
    /**
     * Invisible tap targets must not be fully transparent: MapLibre skips drawing a layer at
     * opacity 0, and a layer it doesn't draw can't be tapped. 1% is invisible but counts.
     */
    private const val HIT_OPACITY = 0.01
    const val LAYER_WAYPOINTS = "user-waypoints"
    const val LAYER_LAND_FILL = "land-fill"
    const val LAYER_SPECIAL_FILL = "special-fill"
    const val LAYER_BOUNDARY_HIT = "boundary-hit"

    private fun emptyGeoJson() = JSONObject().put("type", "geojson")
        .put("data", JSONObject().put("type", "FeatureCollection").put("features", JSONArray()))

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }
}
