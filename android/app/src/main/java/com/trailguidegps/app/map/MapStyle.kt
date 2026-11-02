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
        PlaceGroup("lookouts", listOf("lookout")),
        PlaceGroup("history", listOf("historic"), iconMinZoom = 9.0),
        PlaceGroup("shelters", listOf("shelter")),
        PlaceGroup("other", listOf("viewpoint", "hot_spring", "pass", "saddle")),
    )

    /** Layers you can tap for details. */
    val TAP_LAYERS: Array<String> = (listOf("water-points", "water-crossings", "peaks", "trail-hit", "trail-hit-unnamed") +
        PLACE_GROUPS.flatMap { g -> g.layers.filter { it.startsWith("places-") } }).toTypedArray()

    /** Layer groups the layers sheet can switch on and off. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "hillshade" to listOf("hillshade"),
        "contours" to listOf("contours-minor", "contours-major", "contour-labels"),
        "trails" to listOf("trail", "track", "trail-names", "trail-hit", "trail-hit-unnamed"),
        "notes" to listOf(LAYER_NOTES, "user-notes-photo", "user-notes-labels"),
        "water" to listOf("water-crossings", "water-points", "water-labels"),
        "peaks" to listOf("peaks", "peak-labels"),
    ) + PLACE_GROUPS.associate { g -> g.id to g.layers } + linkedMapOf(
        "landmanager" to listOf(LAYER_LAND_FILL),
        "forests" to listOf(LAYER_BOUNDARY_HIT, "boundary-line", "boundary-line-labels", "boundary-point-labels"),
        // The saved-area layers are added per area; MapScreen finds them by the "satellite" prefix.
        "satellite" to listOf("satellite-online"),
        "slope" to listOf("slope"),
        "roadaccess" to listOf("mvum-yearlong", "mvum-seasonal", "rough-roads", LAYER_ROAD_HIT),
        "burns" to listOf(LAYER_BURN_FILL, "burns-line", "burns-labels"),
        "fires" to listOf(LAYER_FIRE_FILL, "fire-line", LAYER_FIRE_POINTS, "fire-labels"),
        "wilderness" to listOf(LAYER_SPECIAL_FILL, "special-line", "special-line-other", "special-line-labels", "special-point-labels"),
    )

    /** Layer groups that start switched off. */
    val DEFAULT_OFF = setOf("landmanager", "satellite", "slope", "roadaccess", "burns")

    // ---- Dark and sunlight versions of the map ----

    /** Day colors → night colors. Anything not listed (route colors, warnings) stays as it is. */
    private val NIGHT = mapOf(
        "#EFEEE2" to "#1A1D1B", "#D5E3C3" to "#1E2A1F", "#E7EBD6" to "#222820", "#E6E0D2" to "#2A2621",
        "#A9CFEA" to "#17344D", "#4A8FC7" to "#3F82BC", "#B88A5B" to "#6A5643", "#A47448" to "#8B7157",
        "#C9B48F" to "#6E624E", "#8A6A43" to "#B38D5E", "#B5462A" to "#E3714D", "#FFFFFF" to "#121514",
        "#23271F" to "#DCE2DD", "#1F5F92" to "#7DB7E6", "#8E3218" to "#F08B6A", "#6B4F2E" to "#D2AE80",
        "#2E5E1E" to "#7FB86A", "#2F5520" to "#7FB86A", "#3E6B2A" to "#8CC476", "#4E4232" to "#000000",
        "#6B5A44" to "#0E100F", "#5A4636" to "#CDB8A3", "#4A3A2C" to "#D7C4B0", "#5E8A4A" to "#6FA35A",
        "#5D4037" to "#A1887F", "#6D4C41" to "#8D6E63",
    )

    private fun recolor(v: Any?, table: Map<String, String>): Any? = when (v) {
        is String -> table[v.uppercase()] ?: v
        is JSONArray -> JSONArray().apply { for (i in 0 until v.length()) put(recolor(v.get(i), table)) }
        else -> v
    }

    /** Line widths a third wider: plain numbers, and the outputs of zoom curves and case/match. */
    private fun wider(v: Any?, k: Double): Any? = when {
        v is Number -> v.toDouble() * k
        v is JSONArray && v.optString(0) == "interpolate" -> JSONArray().apply {
            for (i in 0 until v.length()) put(if (i >= 4 && (i - 4) % 2 == 1 && v.get(i) is Number) (v.get(i) as Number).toDouble() * k else v.get(i))
        }
        v is JSONArray && (v.optString(0) == "case" || v.optString(0) == "match") -> JSONArray().apply {
            for (i in 0 until v.length()) put(if (i >= 2 && v.get(i) is Number) (v.get(i) as Number).toDouble() * k else v.get(i))
        }
        else -> v
    }

    /**
     * The map for the current look: [dark] swaps in night colors; [sun] (bright sunlight) makes
     * lines wider, labels bigger with stronger halos, and contours and hillshade stronger.
     */
    fun forLook(styleJson: String, dark: Boolean, sun: Boolean): String {
        if (!dark && !sun) return styleJson
        val o = JSONObject(styleJson)
        val layers = o.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val paint = l.optJSONObject("paint") ?: continue
            val layout = l.optJSONObject("layout")
            if (dark) paint.keys().asSequence().toList().forEach { k -> if (k.endsWith("color")) paint.put(k, recolor(paint.get(k), NIGHT)) }
            if (dark && l.optString("type") == "raster") paint.put("raster-brightness-max", 0.8)
            if (sun) {
                if (paint.has("line-width") && paint.optDouble("line-opacity", 1.0) > 0.05) paint.put("line-width", wider(paint.get("line-width"), 1.35))
                if (paint.has("line-opacity") && l.getString("id").startsWith("contours")) paint.put("line-opacity", 1.0)
                if (paint.has("text-halo-width")) paint.put("text-halo-width", paint.optDouble("text-halo-width", 1.0) + 0.8)
                if (layout?.opt("text-size") is Number) layout.put("text-size", layout.getDouble("text-size") * 1.15)
                if (paint.has("hillshade-exaggeration")) paint.put("hillshade-exaggeration", 0.65)
            }
        }
        return o.toString()
    }

    // ---- Base maps: what's under the overlays ----
    const val BASE_TOPO = "topo"
    const val BASE_SATELLITE = "satellite"
    const val BASE_HYBRID = "hybrid"
    val BASES = listOf(BASE_TOPO, BASE_SATELLITE, BASE_HYBRID)
    /** Topo's own colored areas, hidden over imagery. */
    val TOPO_FILLS = listOf("landcover-wood", "landcover-grass", "landcover-rock", "park", "water")
    /** Roads, streams and names: kept on the hybrid map, hidden on plain satellite. */
    val TOPO_DETAIL = listOf("waterway", "road-casing", "road", "place-names", "water-names", "waterway-names")

    /**
     * A copy of [styleJson] showing only one base map, for the picture on its button in the
     * layers screen: topo with contours and trails, imagery alone, or imagery with roads and names.
     */
    fun thumbnailStyle(styleJson: String, base: String): String {
        val keepTopo = setOf("ground", "hillshade", "contours-minor", "contours-major", "track", "trail", "trail-names",
            "peak-labels", "peaks") + TOPO_FILLS + TOPO_DETAIL
        val keepHybrid = setOf("ground", "track", "trail", "trail-names", "peak-labels") + TOPO_DETAIL
        val o = JSONObject(styleJson)
        val layers = o.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.getString("id")
            val show = when (base) {
                BASE_SATELLITE -> id == "ground" || id.startsWith("satellite")
                BASE_HYBRID -> id in keepHybrid || id.startsWith("satellite")
                else -> id in keepTopo
            }
            l.put("layout", (l.optJSONObject("layout") ?: JSONObject()).put("visibility", if (show) "visible" else "none"))
        }
        return o.toString()
    }

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
    /**
     * One state's map files for the style: on the phone ([local]), or streamed from the map
     * release ([remoteBase] plus file names in [remoteFiles]) when it isn't downloaded.
     */
    data class MapSource(val region: String, val local: java.io.File?, val remoteBase: String? = null, val remoteFiles: Set<String> = emptySet()) {
        fun has(name: String) = if (local != null) java.io.File(local, name).exists() else name in remoteFiles
        fun pm(name: String) = if (local != null) "pmtiles://file://" + java.io.File(local, name).absolutePath else "pmtiles://$remoteBase/$name"
        fun gj(name: String) = if (local != null) "file://" + java.io.File(local, name).absolutePath else "$remoteBase/$name"
    }

    /** Id endings for each state's copy of the map layers: "" for the first, "@washington" and so on for the rest. */
    @Volatile var suffixes: List<String> = listOf("")
        private set

    /** Every state's copy of a map layer id. */
    fun all(id: String): Array<String> = suffixes.map { id + it }.toTypedArray()
    fun all(ids: Collection<String>): List<String> = ids.flatMap { all(it).toList() }

    /** Which map file each per-state source comes from. */
    private val REGION_FILES = mapOf(
        "topo" to "topo.pmtiles", "contours" to "contours.pmtiles", "terrain" to "terrain.pmtiles", "water" to "water.geojson",
        "pois" to "pois.geojson", "slope" to "slope.pmtiles", "extras" to "extras.pmtiles", "publiclands" to "publiclands.pmtiles",
    )

    fun build(regions: List<MapSource>, satelliteAreas: List<Pair<String, java.io.File>> = emptyList()): String {
        val maps = regions.ifEmpty { listOf(MapSource("none", null)) }
        val sfx = maps.mapIndexed { i, r -> if (i == 0) "" else "@" + r.region }
        suffixes = sfx
        fun anyHas(file: String) = maps.any { it.has(file) }

        val sources = JSONObject()
        maps.forEachIndexed { i, r ->
            val x = sfx[i]
            if (r.has("topo.pmtiles")) sources.put("topo$x", JSONObject().put("type", "vector").put("url", r.pm("topo.pmtiles"))
                .put("attribution", "© OpenStreetMap contributors"))
            if (r.has("contours.pmtiles")) sources.put("contours$x", JSONObject().put("type", "vector").put("url", r.pm("contours.pmtiles"))
                .put("attribution", "USGS 3DEP"))
            if (r.has("terrain.pmtiles")) sources.put("terrain$x", JSONObject().put("type", "raster-dem").put("url", r.pm("terrain.pmtiles"))
                .put("encoding", "mapbox").put("tileSize", 256))
            if (r.has("water.geojson")) sources.put("water$x", JSONObject().put("type", "geojson").put("data", r.gj("water.geojson")))
            if (r.has("pois.geojson")) sources.put("pois$x", JSONObject().put("type", "geojson").put("data", r.gj("pois.geojson")))
            if (r.has("slope.pmtiles")) sources.put("slope$x", JSONObject().put("type", "raster").put("url", r.pm("slope.pmtiles")).put("tileSize", 256)
                .put("attribution", "Slope from USGS 3DEP"))
            if (r.has("extras.pmtiles")) sources.put("extras$x", JSONObject().put("type", "vector").put("url", r.pm("extras.pmtiles"))
                .put("attribution", "USDA Forest Service MVUM, NIFC fire history"))
            if (r.has("publiclands.pmtiles")) sources.put("publiclands$x", JSONObject().put("type", "vector").put("url", r.pm("publiclands.pmtiles"))
                .put("attribution", "Public land: USGS PAD-US"))
        }
        sources
            // Your own data, filled in by the app after the style loads.
            .put(SRC_SHOWN_TRACK, emptyGeoJson())
            .put(SRC_SHOWN_ROUTE, emptyGeoJson())
            .put(SRC_CAMPS, emptyGeoJson())
            .put(SRC_PLAN, emptyGeoJson())
            .put(SRC_PLAN_POINTS, emptyGeoJson())
            .put(SRC_PROFILE_MARK, emptyGeoJson())
            .put(SRC_OFF_ROUTE, emptyGeoJson())
            .put(SRC_MEASURE, emptyGeoJson())
            .put(SRC_MEASURE_POINTS, emptyGeoJson())
            .put(SRC_GOTO, emptyGeoJson())
            .put(SRC_FIRE_PERIMS, emptyGeoJson())
            .put(SRC_FIRE_POINTS, emptyGeoJson())
            .put(SRC_RECORDING, emptyGeoJson())
            .put(SRC_WAYPOINTS, emptyGeoJson())
            .put(SRC_NOTES, emptyGeoJson())

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
            // A layer drawn from a state's own files is repeated for each state that has that file.
            val file = source?.let { REGION_FILES[it] }
            if (file == null) { layers.put(l); return }
            maps.forEachIndexed { i, r ->
                if (r.has(file)) layers.put(JSONObject(l.toString()).put("id", id + sfx[i]).put("source", source + sfx[i]))
            }
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
        val hasLand = anyHas("publiclands.pmtiles")
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
        val hasExtras = anyHas("extras.pmtiles")
        if (hasExtras) {
            // Past wildfires: browner the more recent.
            layer(LAYER_BURN_FILL, "fill", "extras", "burns", paint = JSONObject().put("fill-color", "#6D4C41")
                .put("fill-opacity", arr("interpolate", arr("linear"), arr("get", "year"), 2000, 0.10, 2026, 0.38)))
            layer("burns-line", "line", "extras", "burns", paint = JSONObject().put("line-color", "#5D4037").put("line-width", 1)
                .put("line-opacity", 0.6))
        }
        layer("hillshade", "hillshade", "terrain", paint = JSONObject()
            .put("hillshade-exaggeration", 0.45).put("hillshade-shadow-color", "#4E4232")
            .put("hillshade-highlight-color", "#FFFFFF").put("hillshade-accent-color", "#6B5A44"))
        if (anyHas("slope.pmtiles")) {
            layer("slope", "raster", "slope", paint = JSONObject().put("raster-opacity", 0.5).put("raster-fade-duration", 0)
                .put("raster-resampling", "nearest"), minzoom = 10.0)
        }
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
        if (hasExtras) {
            // Forest Service road access: green passenger car, orange high clearance, red OHV only; dashed if seasonal.
            val accessColor = arr("match", arr("get", "access"), "car", "#2E7D32", "high", "#E65100", "#C62828")
            val accessWidth = byZoom(9, 1.5, 15, 4.5)
            layer("mvum-yearlong", "line", "extras", "mvum", arr("!=", arr("get", "seasonal"), true),
                JSONObject().put("line-color", accessColor).put("line-width", accessWidth).put("line-opacity", 0.85), minzoom = 9.0)
            layer("mvum-seasonal", "line", "extras", "mvum", arr("==", arr("get", "seasonal"), true),
                JSONObject().put("line-color", accessColor).put("line-width", accessWidth).put("line-opacity", 0.85)
                    .put("line-dasharray", arr(2.5, 1.2)), minzoom = 9.0)
            layer("rough-roads", "line", "extras", "rough", paint = JSONObject().put("line-color", "#6A1B9A")
                .put("line-width", byZoom(10, 1.5, 15, 3.5)).put("line-dasharray", arr(0.6, 1.4)), minzoom = 10.0)
            layer(LAYER_ROAD_HIT, "line", "extras", "mvum", paint = JSONObject().put("line-color", "#000000")
                .put("line-opacity", HIT_OPACITY).put("line-width", 16), minzoom = 9.0)
        }
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
                .put("text-size", 12).put("text-field", arr("get", "name")), minzoom = 11.0)
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
        if (hasExtras) {
            layer("burns-labels", "symbol", "extras", "burns", paint = halo("#FFFFFF", "#5D4037", 1.4),
                layout = JSONObject().put("text-font", arr(ITALIC)).put("text-size", 11)
                    .put("text-field", arr("concat", arr("get", "name"), " Fire ", arr("to-string", arr("get", "year"))))
                    .put("text-optional", true), minzoom = 10.0)
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
        // Creek crossings: many of them, so only when zoomed in, smaller, and under the other water icons.
        layer("water-crossings", "symbol", "water", filter = arr("==", arr("get", "kind"), "stream"), layout = JSONObject()
            .put("icon-image", arr("case", arr("==", arr("get", "seasonal"), true), "water-stream-s", "water-stream"))
            .put("icon-size", 0.7).put("icon-allow-overlap", true), minzoom = 13.0)
        layer("water-points", "symbol", "water", filter = arr("!=", arr("get", "kind"), "stream"), layout = JSONObject()
            .put("icon-image", arr("concat",
                arr("match", arr("get", "kind"), "drinking_water", "water-tap", "water_point", "water-point", "well", "water-well", "water-spring"),
                arr("case", arr("==", arr("get", "seasonal"), true), "-s", "")))
            .put("icon-allow-overlap", true).put("icon-size", 1))
        layer("water-labels", "symbol", "water", filter = arr("!=", arr("get", "kind"), "stream"), paint = halo("#FFFFFF", "#1F5F92"),
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
        // Which way each route runs: small arrows along it in its color.
        layer("shown-route-arrows", "symbol", SRC_SHOWN_ROUTE,
            paint = JSONObject().put("icon-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE))
                .put("icon-halo-color", "#FFFFFF").put("icon-halo-width", 1.5)
                .put("icon-opacity", arr("case", arr("==", arr("get", "focus"), true), 1.0, 0.6)),
            layout = JSONObject().put("symbol-placement", "line").put("symbol-spacing", 110).put("icon-image", "route-arrow")
                .put("icon-size", 0.75).put("icon-allow-overlap", true).put("icon-ignore-placement", true)
                .put("icon-rotation-alignment", "map").put("icon-keep-upright", false),
            minzoom = 12.0)
        // Each route's name along its line, so you can tell several apart.
        layer("shown-route-labels", "symbol", SRC_SHOWN_ROUTE,
            paint = JSONObject().put("text-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE))
                .put("text-halo-color", "#FFFFFF").put("text-halo-width", 2),
            layout = JSONObject().put("symbol-placement", "line").put("symbol-spacing", 350).put("text-font", arr(MEDIUM))
                .put("text-size", 12).put("text-field", arr("get", "name")).put("text-optional", true),
            minzoom = 11.0)
        // Nights out on multi-day routes.
        layer("route-camps", "circle", SRC_CAMPS, paint = JSONObject()
            .put("circle-radius", 7).put("circle-color", "#FFFFFF")
            .put("circle-stroke-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE)).put("circle-stroke-width", 4))
        layer("route-camps-labels", "symbol", SRC_CAMPS,
            paint = JSONObject().put("text-color", arr("coalesce", arr("get", "color"), ROUTE_PURPLE))
                .put("text-halo-color", "#FFFFFF").put("text-halo-width", 2),
            layout = JSONObject().put("text-field", arr("get", "label")).put("text-font", arr(MEDIUM)).put("text-size", 12)
                .put("text-offset", arr(0, 1.3)).put("text-anchor", "top").put("text-optional", true),
            minzoom = 10.0)
        layer("plan-casing", "line", SRC_PLAN, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 8),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("plan-trail", "line", SRC_PLAN, filter = arr("==", arr("get", "trail"), true),
            paint = JSONObject().put("line-color", ROUTE_PURPLE).put("line-width", 4.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("plan-straight", "line", SRC_PLAN, filter = arr("==", arr("get", "trail"), false),
            paint = JSONObject().put("line-color", ROUTE_PURPLE).put("line-width", 3.5).put("line-dasharray", arr(2, 1.5)),
            layout = JSONObject().put("line-cap", "round"))
        layer(LAYER_PLAN_POINTS, "circle", SRC_PLAN_POINTS, paint = JSONObject()
            .put("circle-radius", arr("case", arr("==", arr("get", "sel"), true), 11, arr("match", arr("get", "role"), "mid", 6, 8)))
            .put("circle-color", arr("case", arr("==", arr("get", "sel"), true), "#FFB300",
                arr("match", arr("get", "role"), "start", "#2E8B57", "end", ROUTE_PURPLE, "#FFFFFF")))
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
        // The measuring tool and the line to a Go to target.
        layer("measure-casing", "line", SRC_MEASURE, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 5))
        layer("measure", "line", SRC_MEASURE, paint = JSONObject().put("line-color", "#23271F").put("line-width", 2.5)
            .put("line-dasharray", arr(2, 1.5)))
        layer("measure-points", "circle", SRC_MEASURE_POINTS, paint = JSONObject().put("circle-radius", 5)
            .put("circle-color", "#FFFFFF").put("circle-stroke-color", "#23271F").put("circle-stroke-width", 2.5))
        layer("goto-line", "line", SRC_GOTO, paint = JSONObject().put("line-color", "#1F4FB4").put("line-width", 3)
            .put("line-dasharray", arr(1, 1.5)), layout = JSONObject().put("line-cap", "round"))
        layer("off-route", "line", SRC_OFF_ROUTE, paint = JSONObject().put("line-color", "#C62828").put("line-width", 3)
            .put("line-dasharray", arr(1.5, 1.5)), layout = JSONObject().put("line-cap", "round"))
        // Where you're pressing on the elevation graph.
        layer("profile-mark", "circle", SRC_PROFILE_MARK, paint = JSONObject().put("circle-radius", 9)
            .put("circle-color", "#FFFFFF").put("circle-stroke-color", "#23271F").put("circle-stroke-width", 4))
        layer("recording-casing", "line", SRC_RECORDING, paint = JSONObject().put("line-color", "#FFFFFF").put("line-width", 8),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("recording", "line", SRC_RECORDING, paint = JSONObject().put("line-color", "#E0661F").put("line-width", 4.5),
            layout = JSONObject().put("line-cap", "round").put("line-join", "round"))
        // Your trail notes: a yellow note icon, with a small camera when it has photos.
        layer(LAYER_NOTES, "symbol", SRC_NOTES, layout = JSONObject().put("icon-image", "note-pin").put("icon-size", 0.9)
            .put("icon-allow-overlap", true))
        layer("user-notes-photo", "symbol", SRC_NOTES, filter = arr("==", arr("get", "photo"), true),
            layout = JSONObject().put("icon-image", "photo-badge").put("icon-offset", arr(11, -11)).put("icon-allow-overlap", true))
        layer("user-notes-labels", "symbol", SRC_NOTES, paint = halo("#FFFFFF", "#6B5300"),
            layout = JSONObject().put("text-font", arr(MEDIUM)).put("text-size", 11).put("text-offset", arr(0, 1.3))
                .put("text-anchor", "top").put("text-field", arr("get", "title")).put("text-optional", true).put("text-max-width", 9),
            minzoom = 13.0)
        layer(LAYER_WAYPOINTS, "circle", SRC_WAYPOINTS, paint = JSONObject().put("circle-radius", 8)
            .put("circle-color", arr("get", "color")).put("circle-stroke-color", "#FFFFFF").put("circle-stroke-width", 2.5))
        layer("waypoint-photo", "symbol", SRC_WAYPOINTS, filter = arr("==", arr("get", "photo"), true),
            layout = JSONObject().put("icon-image", "photo-badge").put("icon-offset", arr(10, -10)).put("icon-allow-overlap", true))
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
    const val SRC_CAMPS = "user-camps"
    const val LAYER_PLAN_POINTS = "plan-points"
    const val LAYER_SHOWN_ROUTE_HIT = "shown-route-hit"
    const val SRC_PLAN = "user-plan"
    const val SRC_PLAN_POINTS = "user-plan-points"
    const val SRC_PROFILE_MARK = "user-profile-mark"
    const val SRC_OFF_ROUTE = "user-off-route"
    const val SRC_MEASURE = "user-measure"
    const val SRC_MEASURE_POINTS = "user-measure-points"
    const val SRC_GOTO = "user-goto"
    const val SRC_FIRE_PERIMS = "fires-perimeters"
    const val SRC_FIRE_POINTS = "fires-points"
    const val LAYER_FIRE_FILL = "fire-fill"
    const val LAYER_FIRE_POINTS = "fire-points"
    const val ROUTE_PURPLE = "#6B3FA0"
    const val SRC_RECORDING = "user-recording"
    const val SRC_WAYPOINTS = "user-waypoints"
    const val SRC_NOTES = "user-notes"
    const val LAYER_NOTES = "user-notes"
    /**
     * Invisible tap targets must not be fully transparent: MapLibre skips drawing a layer at
     * opacity 0, and a layer it doesn't draw can't be tapped. 1% is invisible but counts.
     */
    private const val HIT_OPACITY = 0.01
    const val LAYER_WAYPOINTS = "user-waypoints"
    const val LAYER_LAND_FILL = "land-fill"
    const val LAYER_BURN_FILL = "burns-fill"
    const val LAYER_ROAD_HIT = "mvum-hit"
    const val LAYER_SPECIAL_FILL = "special-fill"
    const val LAYER_BOUNDARY_HIT = "boundary-hit"

    private fun emptyGeoJson() = JSONObject().put("type", "geojson")
        .put("data", JSONObject().put("type", "FeatureCollection").put("features", JSONArray()))

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }
}
