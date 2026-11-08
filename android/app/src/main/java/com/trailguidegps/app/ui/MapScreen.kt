package com.trailguidegps.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.DownloadState
import com.trailguidegps.app.geo.Geo
import com.trailguidegps.app.location.Fix
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.TrackStore
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.map.MapIcons
import com.trailguidegps.app.map.MapStyle
import com.trailguidegps.app.record.Recording
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import kotlin.math.roundToInt

private typealias Selected = AppViewModel.SelectedPlace

private val kindLabels = com.trailguidegps.app.data.Features.KIND_LABELS


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: AppViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEmergency: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartFollowing: () -> Unit,
    onOpenSatellite: () -> Unit,
    onOpenMaps: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    val mapVersion by vm.mapVersion.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.items.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val shownTrack by vm.shownTrack.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Waypoint?>(null) }
    var selectedWaypointId by remember { mutableStateOf<String?>(null) }
    var askStop by remember { mutableStateOf(false) }
    val plan by vm.plan.collectAsStateWithLifecycle()
    val pace by vm.pace.collectAsStateWithLifecycle()
    val routingReady by vm.routingReady.collectAsStateWithLifecycle()
    val shownRoute by vm.shownRoute.collectAsStateWithLifecycle()
    val shownRoutes by vm.shownRoutes.collectAsStateWithLifecycle()
    val routeFollow by vm.following.collectAsStateWithLifecycle()
    val satSelect by vm.satSelect.collectAsStateWithLifecycle()
    val measure by vm.measure.collectAsStateWithLifecycle()
    val goTo by vm.goTo.collectAsStateWithLifecycle()
    val heading by vm.compass.heading.collectAsStateWithLifecycle()
    var waterFor by remember { mutableStateOf<Pair<String, List<DoubleArray>>?>(null) }
    var peaksFor by remember { mutableStateOf<Triple<String, Double, Double>?>(null) }
    // Weather: (saved-forecast key, title, spots).
    var weatherFor by remember { mutableStateOf<Triple<String, String, List<Triple<String, Double, Double>>>?>(null) }
    var daysFor by remember { mutableStateOf<SavedRoute?>(null) }
    // Where a long-press started a route, so it can be a waypoint instead.
    var longPressSpot by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var longPressPoints by remember { mutableStateOf(0) }
    var editingNote by remember { mutableStateOf<com.trailguidegps.app.data.TrailNote?>(null) }
    var viewingPhoto by remember { mutableStateOf<String?>(null) }
    val trailNotes by vm.trailNotes.items.collectAsStateWithLifecycle()
    fun newNote(lat: Double, lon: Double) { editingNote = com.trailguidegps.app.data.TrailNote(text = "", lat = lat, lon = lon) }
    var panelHidden by rememberSaveable { mutableStateOf(false) }
    // The "waypoint instead" offer goes once you add more points or stop planning.
    LaunchedEffect(plan.active, plan.points.size) {
        if (!plan.active || plan.points.size > longPressPoints) longPressSpot = null
    }
    // The compass runs only while a Go to target is set.
    DisposableEffect(goTo != null) {
        if (goTo != null) vm.compass.start()
        onDispose { if (goTo != null) vm.compass.stop() }
    }
    val satVersion by vm.satellite.version.collectAsStateWithLifecycle()
    val satAreas by vm.satellite.areas.collectAsStateWithLifecycle()
    // The box you frame when saving satellite imagery, in screen pixels, and the map area it covers.
    var satFrame by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var satBounds by remember { mutableStateOf<DoubleArray?>(null) } // west, south, east, north
    val settings by vm.settings.value.collectAsStateWithLifecycle()
    // Keep the screen awake while following, if that's switched on in Settings.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(routeFollow != null, settings.keepScreenOn) {
        view.keepScreenOn = routeFollow != null && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    var showPace by remember { mutableStateOf(false) }
    var showWaypointPicker by remember { mutableStateOf(false) }
    var askSaveRoute by remember { mutableStateOf(false) }
    var askDiscardRoute by remember { mutableStateOf(false) }
    // The spot you're pressing on an elevation graph, shown on the map.
    var profileMark by remember { mutableStateOf<DoubleArray?>(null) }
    androidx.activity.compose.BackHandler(enabled = plan.active) {
        if (plan.points.size >= 2) askDiscardRoute = true else vm.cancelPlanning()
    }

    val mapView = remember { MapView(context).also { it.onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var following by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var cameraPlaced by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val waterNotes by vm.waterNotes.notes.collectAsStateWithLifecycle()
    var editingNoteFor by remember { mutableStateOf<Selected?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(density)
    // Layer switches are remembered between launches.
    val layerPrefs = remember { context.getSharedPreferences("layers", android.content.Context.MODE_PRIVATE) }
    val layerOn = remember {
        mutableStateMapOf<String, Boolean>().apply {
            MapStyle.GROUPS.keys.forEach { put(it, layerPrefs.getBoolean(it, it !in MapStyle.DEFAULT_OFF)) }
        }
    }
    // The base map. Before 1.3 satellite was a switch that drew imagery under roads and names, which is now "Hybrid".
    var baseMap by remember {
        mutableStateOf(layerPrefs.getString("base", null)
            ?: if (layerPrefs.getBoolean("satellite", false)) MapStyle.BASE_HYBRID else MapStyle.BASE_TOPO)
    }
    val sectionOpen = remember { mutableStateMapOf<String, Boolean>() }
    // Quick sets you made yourself: name and the overlays it turns on.
    var customPresets by remember {
        mutableStateOf(runCatching {
            val arr = org.json.JSONArray(layerPrefs.getString("custom_presets", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val l = o.getJSONArray("layers")
                o.getString("name") to (0 until l.length()).map { l.getString(it) }.toSet()
            }
        }.getOrDefault(emptyList()))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isRotateGesturesEnabled = true
            m.setMinZoomPreference(5.0)
            m.setMaxZoomPreference(17.5)
            m.addOnCameraIdleListener {
                val t = m.cameraPosition.target
                if (t != null) vm.mapCenter = t.latitude to t.longitude
            }
            // Long-press starts a route to that spot: from where you are if you're nearby, otherwise
            // starting there. While planning it adds a point, like a tap.
            m.addOnMapLongClickListener { latLng ->
                if (vm.satSelect.value != null || vm.measure.value != null) return@addOnMapLongClickListener true
                if (vm.plan.value.active) {
                    vm.addPlanPoint(latLng.latitude, latLng.longitude)
                    return@addOnMapLongClickListener true
                }
                val me = vm.tracker.fix.value
                vm.startPlanning()
                selectedWaypointId = null
                val fromMe = me != null && Geo.distanceM(me.lat, me.lon, latLng.latitude, latLng.longitude) in 30.0..40_000.0
                if (fromMe) vm.addPlanPoint(me!!.lat, me.lon)
                longPressPoints = if (fromMe) 2 else 1
                vm.addPlanPoint(latLng.latitude, latLng.longitude)
                longPressSpot = latLng.latitude to latLng.longitude
                true
            }
            m.addOnMapClickListener { latLng ->
                if (vm.satSelect.value != null) return@addOnMapClickListener true
                if (vm.measure.value != null) {
                    vm.addMeasurePoint(latLng.latitude, latLng.longitude)
                    return@addOnMapClickListener true
                }
                val point = m.projection.toScreenLocation(latLng)
                if (vm.plan.value.active) {
                    // Tapping one of the route's own points picks it to move or remove.
                    val hitPoint = m.queryRenderedFeatures(android.graphics.RectF(point.x - 24, point.y - 24, point.x + 24, point.y + 24), MapStyle.LAYER_PLAN_POINTS)
                        .firstOrNull()?.takeIf { it.hasProperty("i") }?.getNumberProperty("i")?.toInt()
                    if (hitPoint != null && vm.plan.value.selected == null) {
                        vm.selectPlanPoint(hitPoint)
                        return@addOnMapClickListener true
                    }
                    // Planning: each tap adds a point (or moves the picked one). Tapping one of your waypoints uses its exact spot.
                    val wp = m.queryRenderedFeatures(point, MapStyle.LAYER_WAYPOINTS).firstOrNull()
                        ?.takeIf { it.hasProperty("id") }?.let { vm.waypoints.get(it.getStringProperty("id")) }
                    if (wp != null) vm.addPlanPoint(wp.lat, wp.lon, wp.name) else vm.addPlanPoint(latLng.latitude, latLng.longitude)
                    return@addOnMapClickListener true
                }
                val wpHit = m.queryRenderedFeatures(point, MapStyle.LAYER_WAYPOINTS).firstOrNull()
                if (wpHit != null && wpHit.hasProperty("id")) {
                    selectedWaypointId = wpHit.getStringProperty("id")
                    vm.select(null)
                    return@addOnMapClickListener true
                }
                selectedWaypointId = null
                // One of your trail notes.
                m.queryRenderedFeatures(point, MapStyle.LAYER_NOTES).firstOrNull()?.takeIf { it.hasProperty("id") }
                    ?.let { vm.trailNotes.get(it.getStringProperty("id")) }?.let { n ->
                        vm.select(noteSelection(n))
                        return@addOnMapClickListener true
                    }
                // A wildfire: name, size, containment, and where closures are posted.
                val fireHit = m.queryRenderedFeatures(point, MapStyle.LAYER_FIRE_POINTS, MapStyle.LAYER_FIRE_FILL).firstOrNull()
                if (fireHit != null) {
                    fun fp(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                        if (fireHit.hasProperty(k) && !fireHit.getProperty(k).isJsonNull) fireHit.getProperty(k).asString else null
                    }
                    val name = (fp("IncidentName", "poly_IncidentName") ?: "Unnamed").trim()
                    val acres = fp("poly_GISAcres", "IncidentSize")?.toDoubleOrNull()
                    val pct = fp("PercentContained", "attr_PercentContained")?.toDoubleOrNull()
                    val updated = fp("ModifiedOnDateTime_dt", "attr_ModifiedOnDateTime_dt")?.toLongOrNull()
                    val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                    val parts = listOfNotNull(
                        "Wildfire",
                        acres?.let { "%,d acres".format(it.roundToInt()) },
                        pct?.let { "${it.roundToInt()}% contained" },
                        updated?.let { "updated ${fmt.format(java.util.Date(it))}" },
                        vm.fires.data.value?.let { "data as of ${fmt.format(java.util.Date(it.fetchedAt))}" },
                        "closures often reach well beyond the fire",
                    )
                    vm.select(Selected("$name Fire", parts.joinToString(" · "), latLng.latitude, latLng.longitude,
                        links = listOf("Fire closures on InciWeb" to com.trailguidegps.app.data.FireStore.INCIWEB_OREGON)))
                    return@addOnMapClickListener true
                }
                // Tapping one of the routes on the map shows its details.
                val routeHit = m.queryRenderedFeatures(point, MapStyle.LAYER_SHOWN_ROUTE_HIT).firstOrNull()
                if (routeHit != null && routeHit.hasProperty("id")) {
                    vm.focusRoute(routeHit.getStringProperty("id"))
                    vm.select(null)
                    return@addOnMapClickListener true
                }
                val hit = m.queryRenderedFeatures(point, *MapStyle.all(MapStyle.TAP_LAYERS.toList()).toTypedArray()).firstOrNull()
                if (hit == null) {
                    // A Forest Service road: who can drive it and when.
                    m.queryRenderedFeatures(point, *MapStyle.all(MapStyle.LAYER_ROAD_HIT)).firstOrNull()?.let { r ->
                        fun rp(k: String) = if (r.hasProperty(k) && !r.getProperty(k).isJsonNull) r.getProperty(k).asString else null
                        val access = when (rp("access")) {
                            "car" -> "Passenger cars OK"
                            "high" -> "High-clearance vehicles"
                            else -> "Not open to cars (OHV only or closed)"
                        }
                        val parts = listOfNotNull(
                            access, rp("level")?.let { "maintenance level $it" }, rp("surface"),
                            rp("car_dates")?.let { "cars ${it.replace("-", "–")}" } ?: rp("high_dates")?.let { "open ${it.replace("-", "–")}" },
                            if (rp("seasonal") == "true") "seasonal" else null, rp("forest"),
                        )
                        vm.select(Selected(rp("name") ?: "Forest road ${rp("id") ?: ""}".trim(), parts.joinToString(" · "),
                            latLng.latitude, latLng.longitude,
                            note = "From the Forest Service's motor vehicle use map. Conditions change; snow and washouts aren't shown."))
                        return@addOnMapClickListener true
                    }
                    // A past wildfire.
                    m.queryRenderedFeatures(point, *MapStyle.all(MapStyle.LAYER_BURN_FILL)).maxByOrNull {
                        if (it.hasProperty("year")) it.getNumberProperty("year").toInt() else 0
                    }?.let { b ->
                        fun bp(k: String) = if (b.hasProperty(k) && !b.getProperty(k).isJsonNull) b.getProperty(k).asString else null
                        vm.select(Selected("${bp("name") ?: "Unnamed"} Fire (${bp("year") ?: "?"})",
                            listOfNotNull("Past wildfire", bp("acres")?.toDoubleOrNull()?.let { "%,d acres".format(it.roundToInt()) }).joinToString(" · "),
                            latLng.latitude, latLng.longitude,
                            note = "Burned areas can have standing dead trees that fall without warning, blowdown across the trail, and washouts. Check recent trip reports."))
                        return@addOnMapClickListener true
                    }
                    // Nothing else here: say whose land it is, if a land layer is on.
                    fun propsOf(layer: String): Map<String, String?>? = m.queryRenderedFeatures(point, *MapStyle.all(layer))
                        .maxByOrNull { f -> if (f.hasProperty("acres")) -(f.getNumberProperty("acres")?.toDouble() ?: 0.0) else 0.0 }
                        ?.let { f ->
                            listOf("name", "mgr", "mgr_type", "des", "access").associateWith { k ->
                                if (f.hasProperty(k) && !f.getProperty(k).isJsonNull) f.getProperty(k).asString else null
                            }
                        }
                    val bProps = propsOf(MapStyle.LAYER_BOUNDARY_HIT)
                    val lProps = propsOf(MapStyle.LAYER_LAND_FILL)
                    val sProps = propsOf(MapStyle.LAYER_SPECIAL_FILL)
                    val land = com.trailguidegps.app.data.PublicLand.describe(sProps, bProps, lProps)
                    vm.select(land?.let { (title, sub) ->
                        Selected(title, sub, latLng.latitude, latLng.longitude,
                            links = listOfNotNull(com.trailguidegps.app.data.PublicLand.alertsLink(bProps, lProps)),
                            note = com.trailguidegps.app.data.PublicLand.campingNote(sProps, bProps, lProps))
                    })
                    return@addOnMapClickListener land != null
                }
                vm.select(hit.let { f ->
                    fun prop(key: String): String? =
                        if (f.hasProperty(key) && !f.getProperty(key).isJsonNull) f.getProperty(key).asString else null
                    val kind = prop("kind") ?: when {
                        f.hasProperty("ele") -> "peak"
                        prop("class") == "track" -> "track"
                        else -> "trail"
                    }
                    val name = prop("name") ?: kindLabels[kind] ?: "Unnamed"
                    val isWater = f.hasProperty("drinking_water") || kind in com.trailguidegps.app.data.Search.WATER_KINDS
                    val parts = mutableListOf(kindLabels[kind] ?: kind)
                    prop("ele_ft")?.toDoubleOrNull()?.let { parts += "%,d ft".format(it.roundToInt()) }
                    if (prop("seasonal") == "true") parts += "may be seasonal"
                    if (f.hasProperty("derived")) parts += "where the trail meets a road"
                    val geomPoint = f.geometry() as? org.maplibre.geojson.Point
                    vm.features.value?.nearTown(
                        geomPoint?.latitude() ?: latLng.latitude, geomPoint?.longitude() ?: latLng.longitude,
                        exclude = if (kind == "town") name else null,
                    )?.let { parts += it }
                    prop("last_edit")?.let { parts += "map data edited $it" }
                    // Links: the place's own page, the land manager's alerts, and a web search.
                    fun landProps(layer: String): Map<String, String?>? = m.queryRenderedFeatures(point, *MapStyle.all(layer)).firstOrNull()?.let { lf ->
                        listOf("name", "mgr", "des").associateWith { k ->
                            if (lf.hasProperty(k) && !lf.getProperty(k).isJsonNull) lf.getProperty(k).asString else null
                        }
                    }
                    val agency = com.trailguidegps.app.data.PublicLand.alertsLink(landProps(MapStyle.LAYER_BOUNDARY_HIT), landProps(MapStyle.LAYER_LAND_FILL))
                    Selected(
                        name, parts.joinToString(" · "),
                        geomPoint?.latitude() ?: latLng.latitude, geomPoint?.longitude() ?: latLng.longitude,
                        osmId = prop("osm_id"), isWater = isWater,
                        links = com.trailguidegps.app.data.PublicLand.placeLinks(
                            prop("name"), kindLabels[kind] ?: kind, prop("website"), agency,
                        ),
                    )
                })
                true
            }
            map = m
        }
    }

    // The map's north arrow sits under the search bar on the left, clear of the status bar.
    LaunchedEffect(map, statusTopPx) {
        val m = map ?: return@LaunchedEffect
        val d = context.resources.displayMetrics.density
        m.uiSettings.compassGravity = android.view.Gravity.TOP or android.view.Gravity.START
        m.uiSettings.setCompassMargins((16 * d).toInt(), statusTopPx + (100 * d).toInt(), 0, 0)
    }

    LaunchedEffect(satSelect, satFrame, map) {
        while (satSelect != null) {
            val r = satFrame
            val m = map
            if (r != null && m != null) {
                val tl = m.projection.fromScreenLocation(android.graphics.PointF(r.left, r.top))
                val br = m.projection.fromScreenLocation(android.graphics.PointF(r.right, r.bottom))
                satBounds = doubleArrayOf(tl.longitude, br.latitude, br.longitude, tl.latitude)
            }
            kotlinx.coroutines.delay(250)
        }
    }

    // (Re)load the style whenever new map files are installed.
    LaunchedEffect(map, mapVersion, satVersion, Look.dark, Look.sun) {
        val m = map ?: return@LaunchedEffect
        style = null
        val areas = vm.satellite.areas.value.filter { it.ready && vm.satellite.file(it).exists() }.map { it.id to vm.satellite.file(it) }
        m.setStyle(Style.Builder().fromJson(MapStyle.forLook(MapStyle.build(vm.mapSources(), areas), Look.dark, Look.sun))) { s ->
            MapIcons.addAll(context, s)
            MapStyle.GROUPS.keys.filter { it != "satellite" }.forEach { group -> setVisible(s, groupLayers(s, group), layerOn[group] != false) }
            applyBase(s, baseMap)
            if (!cameraPlaced) {
                (vm.store.bbox() ?: vm.catalog.value.firstOrNull { it.region == com.trailguidegps.app.BuildConfig.MAPS_REGION }?.bbox
                    ?: doubleArrayOf(-124.7, 41.95, -116.45, 46.3)).let { b ->
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(b[1], b[0])).include(LatLng(b[3], b[2])).build()
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
                }
                cameraPlaced = true
            }
            style = s
        }
    }

    // Blue dot with heading arrow, driven by our own GPS reader.
    LaunchedEffect(style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        enableLocationComponent(context, m, s) {
            following = false
            headingUp = false
        }
        vm.tracker.fix.value?.let { m.locationComponent.forceLocationUpdate(it.location) }
    }
    var zoomedToMe by remember { mutableStateOf(false) }
    LaunchedEffect(fix, style) {
        val m = map ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        // Zoom to you once, if you're in a state the app has maps for (downloaded or online).
        if (!zoomedToMe && style != null && (vm.regionAt(f.lat, f.lon) != null || vm.stores.any { s ->
                s.bbox()?.let { b -> f.lon >= b[0] && f.lat >= b[1] && f.lon <= b[2] && f.lat <= b[3] } == true })
        ) {
            zoomedToMe = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), 13.0))
        }
        if (style != null && m.locationComponent.isLocationComponentActivated) {
            m.locationComponent.forceLocationUpdate(f.location)
        }
    }

    // Your own data on the map: waypoints, the track being recorded, a saved track.
    LaunchedEffect(style, waypoints) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_WAYPOINTS)?.setGeoJson(vm.waypoints.geoJson())
    }
    LaunchedEffect(style, trailNotes) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_NOTES)?.setGeoJson(com.trailguidegps.app.data.TrailNoteStore.geoJson(trailNotes))
    }
    LaunchedEffect(style, recording?.points?.size) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_RECORDING)
            ?.setGeoJson(TrackStore.lineGeoJson(recording?.points.orEmpty()))
    }
    LaunchedEffect(style, plan.legs, plan.points, plan.selected) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PLAN)?.setGeoJson(RouteStore.legsGeoJson(plan.legs))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PLAN_POINTS)?.setGeoJson(RouteStore.pointsGeoJson(plan.points, plan.selected))
    }
    LaunchedEffect(style, shownRoutes, shownRoute?.id) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_SHOWN_ROUTE)
            ?.setGeoJson(RouteStore.routesGeoJson(shownRoutes, shownRoute?.id))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_CAMPS)?.setGeoJson(RouteStore.campsGeoJson(shownRoutes))
    }
    LaunchedEffect(shownRoute?.id, plan.active) { profileMark = null }
    LaunchedEffect(style, measure) {
        val pts = measure.orEmpty()
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_MEASURE)?.setGeoJson(RouteStore.lineGeoJson(pts))
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_MEASURE_POINTS)?.setGeoJson(
            RouteStore.pointsGeoJson(pts.map { com.trailguidegps.app.data.RoutePoint(it[0], it[1], false) }))
    }
    LaunchedEffect(style, goTo, fix) {
        val t = goTo
        val me = fix
        me?.let { vm.compass.setPosition(it.lat, it.lon, it.altitudeM) }
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_GOTO)?.setGeoJson(
            RouteStore.lineGeoJson(if (t != null && me != null) listOf(doubleArrayOf(me.lat, me.lon), doubleArrayOf(t.lat, t.lon)) else emptyList()))
    }
    val fireData by vm.fires.data.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshFires() }
    LaunchedEffect(style, fireData) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_FIRE_PERIMS)?.setGeoJson(fireData?.perimeters ?: com.trailguidegps.app.data.FireStore.EMPTY)
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_FIRE_POINTS)?.setGeoJson(fireData?.points ?: com.trailguidegps.app.data.FireStore.EMPTY)
    }
    // While off route, a dashed line from you back to the nearest point on it.
    LaunchedEffect(style, routeFollow?.offRoute, routeFollow?.nearest, fix) {
        val f = routeFollow
        val me = fix
        val line = if (f != null && f.offRoute && f.nearest != null && me != null)
            listOf(doubleArrayOf(me.lat, me.lon), f.nearest) else emptyList()
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_OFF_ROUTE)?.setGeoJson(RouteStore.lineGeoJson(line))
    }
    LaunchedEffect(style, selected) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_SELECTED)?.setGeoJson(
            RouteStore.pointGeoJson(selected?.let { doubleArrayOf(it.lat, it.lon) }))
    }
    LaunchedEffect(style, profileMark) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_PROFILE_MARK)?.setGeoJson(RouteStore.pointGeoJson(profileMark))
        // Bring the marked spot into view if it's off screen.
        val m = profileMark ?: return@LaunchedEffect
        map?.let { mm ->
            val ll = org.maplibre.android.geometry.LatLng(m[0], m[1])
            if (mm.projection.visibleRegion.latLngBounds.contains(ll) == false) {
                mm.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(ll), 300)
            }
        }
    }
    LaunchedEffect(style, shownTrack) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_SHOWN_TRACK)
            ?.setGeoJson(TrackStore.lineGeoJson(shownTrack?.second.orEmpty()))
    }
    LaunchedEffect(focus, style) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        following = false
        headingUp = false
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        if (f.points.size == 1) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.points[0].first, f.points[0].second), 15.0))
        } else {
            val b = LatLngBounds.Builder()
            f.points.forEach { b.include(LatLng(it.first, it.second)) }
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80)) }
        }
    }

    fun dropPinHere() {
        val f = fix
        if (f == null) {
            Toast.makeText(context, "Waiting for GPS. You can also tap a place and choose Pin, or long-press the map and choose Waypoint.", Toast.LENGTH_LONG).show()
            return
        }
        editing = Waypoint(name = vm.waypoints.nextName(), lat = f.lat, lon = f.lon, eleM = f.altitudeM)
    }

    fun centerOnMe() {
        if (!hasLocationPermission) { onRequestPermission(); return }
        val m = map ?: return
        if (!m.locationComponent.isLocationComponentActivated) return
        following = true
        m.locationComponent.cameraMode = if (headingUp) CameraMode.TRACKING_COMPASS else CameraMode.TRACKING
        if (m.cameraPosition.zoom < 13.5) m.locationComponent.zoomWhileTracking(14.5)
    }

    fun toggleHeading() {
        val m = map ?: return
        headingUp = !headingUp
        if (headingUp) {
            if (!hasLocationPermission || !m.locationComponent.isLocationComponentActivated) {
                headingUp = false; onRequestPermission(); return
            }
            following = true
            m.locationComponent.cameraMode = CameraMode.TRACKING_COMPASS
        } else {
            m.locationComponent.cameraMode = if (following) CameraMode.TRACKING else CameraMode.NONE
            m.animateCamera(CameraUpdateFactory.bearingTo(0.0))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFEFEEE2))) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (satSelect != null) {
            // The box to save: whatever of the map is inside it.
            Box(
                Modifier.fillMaxSize().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 96.dp, bottom = 340.dp)
                    .border(3.dp, Color(0xFF1F4FB4), RoundedCornerShape(6.dp))
                    .onGloballyPositioned { satFrame = it.boundsInRoot() },
            )
        }

        // Top: search and layers buttons, plus a notice when you're off the map
        // The map buttons above the bottom panel rise with it; when they'd run into the top ones, they step aside.
        var topControlsBottom by remember { mutableFloatStateOf(0f) }
        var sideControlsTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
        val crowded = sideControlsTop < topControlsBottom + with(LocalDensity.current) { 8.dp.toPx() }
        val bbox = remember(mapVersion) { vm.store.bbox() }
        // Off the downloaded maps: outside every downloaded state's area.
        val installedBoxes = remember(mapVersion) { vm.stores.mapNotNull { it.bbox() } }
        val outside = fix != null && installedBoxes.isNotEmpty() && installedBoxes.none { b ->
            fix!!.lon >= b[0] && fix!!.lat >= b[1] && fix!!.lon <= b[2] && fix!!.lat <= b[3]
        }
        Column(Modifier.statusBarsPadding().padding(16.dp).fillMaxWidth().onGloballyPositioned { topControlsBottom = it.boundsInRoot().bottom },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (measure != null) {
                Surface(shape = RoundedCornerShape(24.dp), color = TG.Surface, shadowElevation = 4.dp, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.stopMeasure() }) { Icon(Icons.Filled.Close, contentDescription = "Stop measuring", tint = TG.Ink) }
                        Column(Modifier.weight(1f)) {
                            Text("Measure distance", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                            Text("Tap the map to add points", fontSize = 12.sp, color = TG.Muted, maxLines = 1)
                        }
                        IconButton(onClick = { vm.undoMeasure() }, enabled = !measure.isNullOrEmpty()) {
                            Icon(Icons.Filled.Undo, contentDescription = "Undo last point", tint = if (!measure.isNullOrEmpty()) TG.Ink else TG.Line)
                        }
                    }
                }
            } else if (satSelect != null) {
                Surface(shape = RoundedCornerShape(24.dp), color = TG.Surface, shadowElevation = 4.dp, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.cancelSatSelect() }) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = TG.Ink) }
                        Column(Modifier.weight(1f)) {
                            Text(if (satSelect?.replacing != null) "Change satellite area" else "Save satellite imagery",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                            Text("Move and zoom so the blue box covers it", fontSize = 12.sp, color = TG.Muted, maxLines = 1)
                        }
                    }
                }
            } else if (plan.active) {
                Surface(shape = RoundedCornerShape(24.dp), color = TG.Surface, shadowElevation = 4.dp, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (plan.points.size >= 2) askDiscardRoute = true else vm.cancelPlanning() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Stop planning", tint = TG.Ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(plan.editing?.let { "Edit ${it.name}" } ?: "Plan a route", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                            Text(when {
                                plan.selected != null -> "Tap the map to move the point"
                                plan.editing != null || plan.points.size >= 2 -> "Tap the map to add · tap a point to change it"
                                else -> "Tap the map to add points"
                            }, fontSize = 12.sp, color = TG.Muted, maxLines = 1)
                        }
                        IconButton(onClick = { vm.undoPlanPoint() }, enabled = plan.points.isNotEmpty() && !plan.busy) {
                            Icon(Icons.Filled.Undo, contentDescription = "Undo last point", tint = if (plan.points.isNotEmpty()) TG.Ink else TG.Line)
                        }
                    }
                }
            } else {
                // Just a button, so the map gets the top of the screen.
                RoundButton(onClick = onOpenSearch, description = "Search trails, water, places or coordinates") {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TG.Ink)
                }
                Spacer(Modifier.weight(1f))
            }
            val topButtons: @Composable () -> Unit = {
                RoundButton(onClick = { showLayers = true }, description = "Map layers") {
                    Icon(Icons.Filled.Layers, contentDescription = null, tint = TG.Ink)
                }
                RoundButton(onClick = onOpenSettings, description = "Settings") {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = TG.Ink)
                }
                SosButton(onClick = onEmergency)
            }
            // Across the top beside the search button; stacked beside a measuring or planning bar.
            if (measure == null && satSelect == null && !plan.active) topButtons()
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { topButtons() }
        }
        if (plan.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Follow trails", plan.followTrails) { vm.setFollowTrails(true) }
                ModeChip("Straight line", !plan.followTrails) { vm.setFollowTrails(false) }
            }
        }
        val update by vm.update.collectAsStateWithLifecycle()
        val updateDismissed by vm.updateDismissed.collectAsStateWithLifecycle()
        // Which states have maps (for the online map and updates); quietly does nothing offline.
        LaunchedEffect(Unit) { vm.refreshCatalog() }
        val dl = download
        if (dl is DownloadState.Downloading) {
            Surface(shape = RoundedCornerShape(14.dp), color = TG.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Downloading ${dl.region?.let { com.trailguidegps.app.data.MapStore.displayName(it) + " " } ?: ""}maps: ${percent(dl.done, dl.total)}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                        modifier = Modifier.fillMaxWidth(), color = TG.Accent,
                    )
                    Text("Keep the app open. The map works as usual meanwhile.", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else if (update != null && !updateDismissed && vm.settings.value.value.checkUpdatesOnLaunch) {
            val u = update!!
            Surface(shape = RoundedCornerShape(14.dp), color = TG.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Map update available", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                        Text("${u.built} · ${formatBytes(u.bytes)} download · use Wi-Fi", fontSize = 12.sp, color = TG.Muted)
                    }
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("Later", color = TG.Muted) }
                    TextButton(onClick = { vm.downloadMaps() }) { Text("Update", color = TG.Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (outside && bbox != null) {
            Surface(
                onClick = {
                    following = false
                    headingUp = false
                    map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(bbox[1], bbox[0])).include(LatLng(bbox[3], bbox[2])).build()
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
                },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFF4E5),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("You're outside your downloaded maps", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Warn)
                    Text("Tap to show the area your maps cover.", fontSize = 13.sp, color = TG.Warn)
                }
            }
        }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(
                Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp)
                    .onGloballyPositioned { sideControlsTop = it.boundsInRoot().top }
                    .alpha(if (crowded) 0f else 1f)
                    .then(if (crowded) Modifier.pointerInput(Unit) {
                        // Hidden: swallow taps so the invisible buttons can't be pressed.
                        awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
                    } else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (measure == null && !plan.active && satSelect == null) {
                    RoundButton(onClick = { vm.startMeasure() }, description = "Measure distance") {
                        Icon(Icons.Filled.Straighten, contentDescription = null, tint = TG.Ink)
                    }
                }
                RoundButton(onClick = { toggleHeading() }, description = if (headingUp) "Switch to north up" else "Switch to heading up", active = headingUp) {
                    Icon(if (headingUp) Icons.Filled.Navigation else Icons.Filled.Explore, contentDescription = null, tint = if (headingUp) Color.White else TG.Accent)
                }
                RoundButton(onClick = { centerOnMe() }, description = "Center on my location", active = following && !headingUp) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, tint = if (following && !headingUp) Color.White else TG.Blue)
                }
            }
            if (measure != null) {
                Surface(
                    color = TG.Surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        MeasurePanel(measure.orEmpty(), onUndo = { vm.undoMeasure() }, onClear = { vm.clearMeasure() }, onDone = { vm.stopMeasure() })
                    }
                }
            } else if (satSelect != null) {
                Surface(
                    color = TG.Surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        SatSelectPanel(
                            replacing = satSelect?.replacing,
                            bounds = satBounds,
                            suggestName = { lat, lon ->
                                vm.features.value?.nearTown(lat, lon)?.substringAfter(" of ", "")?.takeIf { it.isNotBlank() }?.let { "Near $it" }
                            },
                            freeBytes = context.filesDir.usableSpace,
                            onCancel = { vm.cancelSatSelect() },
                            onSave = { name, b, z -> vm.saveSatArea(name, b[0], b[1], b[2], b[3], z) },
                        )
                    }
                }
            } else if (plan.active) {
                Surface(
                    color = TG.Surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        longPressSpot?.let { (la, lo) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                Text("Wanted something else here?", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    longPressSpot = null
                                    vm.cancelPlanning()
                                    editing = Waypoint(name = vm.waypoints.nextName(), lat = la, lon = lo, eleM = vm.elevationAt(la, lo))
                                }) { Text("Waypoint", fontSize = 13.sp, color = TG.Accent) }
                                TextButton(onClick = {
                                    longPressSpot = null
                                    vm.cancelPlanning()
                                    newNote(la, lo)
                                }) { Text("Note", fontSize = 13.sp, color = TG.Accent) }
                                IconButton(onClick = { longPressSpot = null }) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TG.Muted) }
                            }
                        }
                        RoutePlannerPanel(
                            plan = plan, pace = pace, routingReady = routingReady,
                            onAddWaypoint = { showWaypointPicker = true },
                            onSave = { askSaveRoute = true },
                            onEditPace = { showPace = true },
                            onScrub = { d ->
                                profileMark = d?.let { RouteStore.pointAt(plan.legs.flatMap { leg -> leg.coords }, it) }
                            },
                            onWater = { waterFor = "this route" to plan.legs.flatMap { leg -> leg.coords } },
                            onRemoveSelected = { plan.selected?.let { vm.removePlanPoint(it) } },
                            onClearSelected = { vm.selectPlanPoint(null) },
                            onAddAtStart = { vm.setAddAtStart(it) },
                        )
                    }
                }
            } else BottomPanel(
                hidden = panelHidden,
                onHide = { panelHidden = it },
                onNoteAt = { la, lo -> newNote(la, lo) },
                onEditTrailNote = { id -> vm.trailNotes.get(id)?.let { editingNote = it } },
                onOpenPhoto = { viewingPhoto = it },
                onTrackToRoute = { shownTrack?.first?.let { vm.trackToRoute(it) } },
                onPinAt = { name, la, lo ->
                    editing = Waypoint(name = name.takeIf { it != "your location" } ?: vm.waypoints.nextName(), lat = la, lon = lo,
                        eleM = vm.elevationAt(la, lo))
                },
                fix = fix,
                hasLocationPermission = hasLocationPermission,
                gpsOn = vm.tracker.isGpsEnabled(),
                selected = selected,
                selectedWaypoint = selectedWaypointId?.let { id -> waypoints.firstOrNull { it.id == id } },
                shownTrackName = shownTrack?.first?.let { id -> vm.tracks.summaries.value.firstOrNull { it.id == id }?.name },
                recording = recording,
                onCloseSelected = { vm.select(null); selectedWaypointId = null },
                waterNote = selected?.osmId?.let { waterNotes[it] },
                onEditNote = { editingNoteFor = selected },
                onEditWaypoint = { editing = it },
                onHideTrack = { vm.hideTrack() },
                onRequestPermission = onRequestPermission,
                onRecord = {
                    if (!hasLocationPermission) onRequestPermission() else onStartRecording()
                },
                onPause = { vm.setPaused(it) },
                onStop = { askStop = true },
                onPin = { dropPinHere() },
                onSaved = onOpenSaved,
                onRoute = { vm.startPlanning() },
                shownRoute = shownRoute,
                shownRouteCount = shownRoutes.size,
                onHideAllRoutes = { vm.hideAllRoutes() },
                pace = pace,
                onHideRoute = { vm.hideRoute() },
                onRouteScrub = { d -> profileMark = d?.let { dist -> shownRoute?.let { RouteStore.pointAt(it.coords, dist) } } },
                following = routeFollow,
                goTo = goTo,
                heading = heading,
                onGoTo = { name, la, lo -> vm.startGoTo(name, la, lo) },
                onStopGoTo = { vm.stopGoTo() },
                onWater = { name, coords -> waterFor = name to coords },
                onReverse = { id -> vm.reverseRoute(id) },
                onPeaks = { name, la, lo -> peaksFor = Triple(name, la, lo) },
                onWeather = { name, la, lo ->
                    weatherFor = Triple("place:%.3f,%.3f".format(java.util.Locale.US, la, lo), name, listOf(Triple(name, la, lo)))
                },
                onDays = { r -> daysFor = r },
                onRouteWeather = { r -> weatherFor = Triple("route:${r.id}", r.name, vm.weatherSpots(r)) },
                tripVm = vm,
                onFollow = onStartFollowing,
                onStopFollowing = { vm.stopFollowing() },
                placeDescription = fix?.let { f -> features?.describe(f.lat, f.lon) },
            )
        }
    }

    editingNoteFor?.let { sel ->
        WaterNoteDialog(
            placeName = sel.title,
            initial = vm.waterNotes.get(sel.osmId)?.text.orEmpty(),
            onSave = { text -> sel.osmId?.let { vm.waterNotes.set(it, text) }; editingNoteFor = null },
            onDismiss = { editingNoteFor = null },
        )
    }

    editing?.let { w ->
        WaypointEditor(
            waypoint = w,
            isNew = vm.waypoints.get(w.id) == null,
            onSave = { vm.waypoints.upsert(it); editing = null; selectedWaypointId = it.id },
            onDelete = { vm.deleteWaypoint(w.id); editing = null; selectedWaypointId = null },
            onDismiss = { editing = null },
            folders = vm.folders.folders.collectAsStateWithLifecycle().value,
            folderId = vm.folders.members.value[com.trailguidegps.app.data.FolderStore.waypointKey(w.id)]
                ?: if (vm.waypoints.get(w.id) == null) vm.lastFolder() else null,
            onCreateFolder = { vm.folders.create(it) },
            onFolder = { f ->
                vm.folders.put(com.trailguidegps.app.data.FolderStore.waypointKey(w.id), f)
                if (f != null) vm.rememberFolder(f)
            },
            vm = vm,
        )
    }
    editingNote?.let { n ->
        val isNew = vm.trailNotes.get(n.id) == null
        NoteEditor(
            vm, n, isNew,
            folders = vm.folders.folders.collectAsStateWithLifecycle().value,
            folderId = vm.folders.members.value[com.trailguidegps.app.data.FolderStore.noteKey(n.id)] ?: if (isNew) vm.lastFolder() else null,
            onSave = { saved, f ->
                vm.trailNotes.upsert(saved)
                vm.folders.put(com.trailguidegps.app.data.FolderStore.noteKey(saved.id), f)
                if (f != null) vm.rememberFolder(f)
                editingNote = null
                vm.select(noteSelection(saved))
            },
            onDelete = { vm.deleteNote(n.id); editingNote = null; vm.select(null) },
            onDismiss = { editingNote = null },
        )
    }
    viewingPhoto?.let { PhotoViewer(it, vm.photos) { viewingPhoto = null } }

    waterFor?.let { (name, coords) ->
        WaterAlongRouteDialog(
            vm, name, coords, waterNotes,
            onPick = { la, lo -> waterFor = null; vm.focusOn(listOf(la to lo)) },
            onDismiss = { waterFor = null },
        )
    }
    peaksFor?.let { (name, la, lo) ->
        PeaksDialog(vm, name, la, lo, onPick = { pla, plo -> peaksFor = null; vm.focusOn(listOf(pla to plo)) }, onDismiss = { peaksFor = null })
    }
    weatherFor?.let { (key, title, spots) ->
        WeatherDialog(vm, key, title, spots, onDismiss = { weatherFor = null })
    }
    daysFor?.let { r -> TripDaysDialog(vm, r, pace, onDismiss = { daysFor = null }) }

    if (showPace) {
        PaceDialog(pace, onSave = { vm.setPace(it); showPace = false }, onDismiss = { showPace = false })
    }
    if (showWaypointPicker) {
        val last = plan.points.lastOrNull()
        WaypointPickerDialog(
            waypoints, last?.lat ?: fix?.lat, last?.lon ?: fix?.lon,
            onPick = { w -> showWaypointPicker = false; vm.addPlanPoint(w.lat, w.lon, w.name) },
            onDismiss = { showWaypointPicker = false },
        )
    }
    if (askSaveRoute) {
        val first = plan.points.firstOrNull()?.waypointName
        val last = plan.points.lastOrNull()?.waypointName
        val suggested = when {
            first != null && last != null && first != last -> "$first to $last"
            plan.via.isNotEmpty() -> plan.via.first()
            else -> "Route ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date())}"
        }
        val ed = plan.editing
        SaveRouteDialog(
            ed?.name ?: suggested, onSave = { name, color, folder, asNew -> askSaveRoute = false; vm.savePlan(name, color, folder, asNew) },
            onDismiss = { askSaveRoute = false },
            folders = vm.folders.folders.collectAsStateWithLifecycle().value,
            folderId = ed?.let { vm.folders.members.value[com.trailguidegps.app.data.FolderStore.routeKey(it.id)] } ?: vm.lastFolder(),
            onCreateFolder = { vm.folders.create(it) },
            editing = ed != null, initialColor = ed?.color ?: com.trailguidegps.app.data.RouteColors.DEFAULT,
        )
    }
    if (askDiscardRoute) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askDiscardRoute = false },
            title = { Text("Discard this route?") },
            text = { Text("The points you've placed will be lost.") },
            confirmButton = { TextButton(onClick = { askDiscardRoute = false; vm.cancelPlanning() }) { Text("Discard", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { askDiscardRoute = false }) { Text("Keep planning") } },
        )
    }

    if (askStop) {
        StopRecordingDialog(
            defaultName = vm.defaultTrackName(recording?.startedAt ?: System.currentTimeMillis()),
            onSave = { name, folder -> askStop = false; vm.stopRecording(name, save = true, folderId = folder) },
            onDiscard = { askStop = false; vm.stopRecording(null, save = false) },
            onDismiss = { askStop = false },
            folders = vm.folders.folders.collectAsStateWithLifecycle().value, folderId = vm.lastFolder(),
            onCreateFolder = { vm.folders.create(it) },
        )
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }, containerColor = TG.Surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text("Map layers", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                Spacer(Modifier.height(8.dp))
                fun setGroup(group: String, on: Boolean) {
                    layerOn[group] = on
                    layerPrefs.edit().putBoolean(group, on).apply()
                    style?.let { s -> setVisible(s, groupLayers(s, group), on) }
                }
                LayersContent(
                    base = baseMap,
                    onBase = { b ->
                        baseMap = b
                        layerPrefs.edit().putString("base", b).apply()
                        style?.let { s -> applyBase(s, b) }
                    },
                    online = isOnline(context),
                    satelliteAreaCount = satAreas.count { it.ready },
                    layerOn = layerOn,
                    onToggle = { g, on -> setGroup(g, on) },
                    onPreset = { set -> OVERLAY_GROUPS.forEach { g -> if ((layerOn[g] == true) != (g in set)) setGroup(g, g in set) } },
                    customPresets = customPresets,
                    onCustomPresets = { list ->
                        customPresets = list
                        layerPrefs.edit().putString("custom_presets", org.json.JSONArray().apply {
                            list.forEach { (n, set) -> put(org.json.JSONObject().put("name", n).put("layers", org.json.JSONArray(set.toList()))) }
                        }.toString()).apply()
                    },
                    sectionOpen = { id -> sectionOpen[id] ?: layerPrefs.getBoolean("sec_$id", false) },
                    onSection = { id, open -> sectionOpen[id] = open; layerPrefs.edit().putBoolean("sec_$id", open).apply() },
                    firesFooter = {
                        val fStatus by vm.firesStatus.collectAsStateWithLifecycle()
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fStatus ?: fireData?.let {
                                    "Wildfires: ${it.count} in and around your map area, as of " +
                                        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(it.fetchedAt))
                                } ?: "Wildfires: not downloaded yet. Connect to update.",
                                fontSize = 12.sp, color = TG.Muted, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.refreshFires(force = true) }) { Text("Update", color = TG.Accent, fontSize = 13.sp) }
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = TG.Line)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hiking time estimates", fontSize = 16.sp, color = TG.Ink)
                        Text("%.1f mph · %d min per 1,000 ft · %d min breaks per hour".format(pace.mph, pace.climbMinPer1000Ft, pace.breakMinPerHour), fontSize = 12.sp, color = TG.Muted)
                    }
                    TextButton(onClick = { showPace = true }) { Text("Change", color = TG.Accent) }
                }
                val routeCount = vm.routes.summaries.collectAsStateWithLifecycle().value.size
                if (routeCount > 0) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Saved routes", fontSize = 16.sp, color = TG.Ink)
                            Text("${shownRoutes.size} of $routeCount on the map · tap one for details", fontSize = 12.sp, color = TG.Muted)
                        }
                        if (shownRoutes.size < routeCount) TextButton(onClick = { vm.showAllRoutes() }) { Text("Show all", color = TG.Accent) }
                        if (shownRoutes.isNotEmpty()) TextButton(onClick = { vm.hideAllRoutes() }) { Text("Hide all", color = TG.Accent) }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Offline satellite areas", fontSize = 16.sp, color = TG.Ink)
                        val ready = satAreas.filter { it.ready }
                        Text(
                            if (satAreas.isEmpty()) "None saved yet. Save an area before a trip."
                            else "${ready.size} saved · ${"%.0f".format(ready.sumOf { it.bytes } / 1e6)} MB" +
                                (satAreas.count { !it.ready }.takeIf { it > 0 }?.let { " · $it in progress" } ?: ""),
                            fontSize = 12.sp, color = TG.Muted,
                        )
                    }
                    TextButton(onClick = { showLayers = false; onOpenSatellite() }) { Text("Manage", color = TG.Accent) }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("All settings", fontSize = 16.sp, color = TG.Ink)
                        Text("Off-route alerts, screen, map updates", fontSize = 12.sp, color = TG.Muted)
                    }
                    TextButton(onClick = { showLayers = false; onOpenSettings() }) { Text("Open", color = TG.Accent) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = TG.Line)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Maps for each state", fontSize = 16.sp, color = TG.Ink)
                        Text(if (vm.stores.isEmpty()) "None downloaded · showing the online map"
                            else vm.stores.joinToString(" · ") { "${it.name}, built ${it.builtDate() ?: "?"}" }, fontSize = 12.sp, color = TG.Muted)
                    }
                    TextButton(onClick = { showLayers = false; onOpenMaps() }) { Text("Manage", color = TG.Accent) }
                }
                Spacer(Modifier.height(10.dp))
                val status = when (val d = download) {
                    DownloadState.Idle -> null
                    DownloadState.Checking -> "Checking for newer map files…"
                    is DownloadState.Downloading -> "Downloading ${d.file}: ${percent(d.done, d.total)}%"
                    is DownloadState.UpToDate -> "Map files are up to date."
                    is DownloadState.Error -> d.message
                }
                OutlinedButton(
                    onClick = { vm.downloadMaps() },
                    enabled = download !is DownloadState.Checking && download !is DownloadState.Downloading,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Check for map updates") }
                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = if (download is DownloadState.Error) TG.Danger else TG.Muted)
                }
                Spacer(Modifier.height(4.dp))
                Text("Updates need Wi-Fi or data. Everything else works offline.", fontSize = 12.sp, color = TG.Muted)
            }
        }
    }
}

@Composable
private fun BottomPanel(
    onNoteAt: (Double, Double) -> Unit,
    onEditTrailNote: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onTrackToRoute: () -> Unit,
    hidden: Boolean,
    onHide: (Boolean) -> Unit,
    onPinAt: (String, Double, Double) -> Unit,
    fix: Fix?,
    hasLocationPermission: Boolean,
    gpsOn: Boolean,
    selected: Selected?,
    waterNote: com.trailguidegps.app.data.WaterNotes.Note?,
    onEditNote: () -> Unit,
    selectedWaypoint: Waypoint?,
    shownTrackName: String?,
    recording: Recording?,
    onCloseSelected: () -> Unit,
    onEditWaypoint: (Waypoint) -> Unit,
    onHideTrack: () -> Unit,
    onRequestPermission: () -> Unit,
    onRecord: () -> Unit,
    onPause: (Boolean) -> Unit,
    onStop: () -> Unit,
    onPin: () -> Unit,
    onSaved: () -> Unit,
    onRoute: () -> Unit,
    shownRoute: SavedRoute?,
    shownRouteCount: Int,
    onHideAllRoutes: () -> Unit,
    pace: Pace,
    onHideRoute: () -> Unit,
    onRouteScrub: (Double?) -> Unit,
    following: com.trailguidegps.app.route.Following?,
    goTo: AppViewModel.GoTo?,
    heading: Float?,
    onGoTo: (String, Double, Double) -> Unit,
    onStopGoTo: () -> Unit,
    onWater: (String, List<DoubleArray>) -> Unit,
    onReverse: (String) -> Unit,
    onPeaks: (String, Double, Double) -> Unit,
    onWeather: (String, Double, Double) -> Unit,
    onDays: (SavedRoute) -> Unit,
    onRouteWeather: (SavedRoute) -> Unit,
    tripVm: AppViewModel,
    onFollow: () -> Unit,
    onStopFollowing: () -> Unit,
    placeDescription: String?,
    startExpanded: Boolean = false,
) {
    // Something new to show brings the panel back up.
    LaunchedEffect(selected, selectedWaypoint?.id, recording != null, shownRoute?.id, goTo, following != null) {
        if (selected != null || selectedWaypoint != null || shownRoute != null || goTo != null || following != null) onHide(false)
    }
    var drag by remember { mutableFloatStateOf(0f) }
    val hasPlace = selected != null || selectedWaypoint != null
    var placeExpanded by remember(selected, selectedWaypoint?.id) { mutableStateOf(startExpanded) }
    val onPlaceExpanded: (Boolean) -> Unit = { placeExpanded = it }
    // With a route shown, a compact card (name, Follow, elevation graph) leaves more map to see. Remembered.
    val panelPrefs = LocalContext.current.let { c -> remember { c.getSharedPreferences("panel", android.content.Context.MODE_PRIVATE) } }
    var routeCompact by remember { mutableStateOf(panelPrefs.getBoolean("route_compact", true)) }
    val setRouteCompact: (Boolean) -> Unit = { routeCompact = it; panelPrefs.edit().putBoolean("route_compact", it).apply() }
    val routeOnly = shownRoute != null && following == null && goTo == null && !hasPlace && recording == null
    val compactRoute = routeOnly && routeCompact
    Surface(
        color = TG.Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
        // Swipe down to hide the panel, up to bring it back.
        // With a place card open, swipe up for its details and down to shrink it again.
        modifier = Modifier.fillMaxWidth().animateContentSize().pointerInput(hidden, hasPlace, placeExpanded, routeOnly, routeCompact) {
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    when {
                        drag < -60f && hidden -> onHide(false)
                        drag < -60f && hasPlace && !placeExpanded -> placeExpanded = true
                        drag > 60f && hasPlace && placeExpanded -> placeExpanded = false
                        drag < -60f && routeOnly && routeCompact -> setRouteCompact(false)
                        drag > 60f && routeOnly && !routeCompact -> setRouteCompact(true)
                        drag > 60f && !hidden -> onHide(true)
                    }
                },
            ) { _, dy -> drag += dy }
        },
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (hidden) 6.dp else 16.dp)) {
            // The handle: tap or swipe to hide and show.
            Box(
                Modifier.fillMaxWidth().height(28.dp).clickable(onClickLabel = if (hidden) "Show panel" else "Hide panel") { onHide(!hidden) }
                    .semantics { contentDescription = if (hidden) "Show panel" else "Hide panel" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(TG.Line, RoundedCornerShape(2.dp)))
            }
            if (hidden) {
                Row(Modifier.fillMaxWidth().clickable { onHide(false) }.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val line = when {
                        recording != null -> (if (recording.paused) "Paused · " else "Recording · ") + Geo.formatMiles(recording.stats.distanceM)
                        fix != null -> Geo.decimal(fix.lat, fix.lon).let { (la, lo) -> "$la, $lo" } + (fix.altitudeM?.let { " · ${Geo.formatFeet(it)}" } ?: "")
                        else -> "Map"
                    }
                    if (recording != null) Box(Modifier.size(8.dp).background(if (recording.paused) TG.Muted else Color(0xFFE53935), CircleShape))
                    Text(line, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = if (recording != null) 8.dp else 0.dp))
                    Text("Show ▲", fontSize = 13.sp, color = TG.Accent)
                }
                return@Column
            }

            // Details for whatever was tapped on the map: a short card, pulled up for more.
            if (selectedWaypoint != null || selected != null) {
                val context = LocalContext.current
                val title = selectedWaypoint?.name ?: selected!!.title
                val lat = selectedWaypoint?.lat ?: selected!!.lat
                val lon = selectedWaypoint?.lon ?: selected!!.lon
                Row(verticalAlignment = Alignment.Top) {
                    selectedWaypoint?.let { w ->
                        Box(Modifier.padding(top = 6.dp, end = 10.dp).size(14.dp).background(Color(android.graphics.Color.parseColor(w.color)), CircleShape))
                    }
                    Column(Modifier.weight(1f).clickable { onPlaceExpanded(!placeExpanded) }) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TG.Ink, maxLines = if (placeExpanded) 3 else 1)
                        val sub = if (selectedWaypoint != null) {
                            "Waypoint" + (selectedWaypoint.eleM?.let { " · ${Geo.formatFeet(it)}" } ?: "")
                        } else selected!!.subtitle
                        Text(sub, fontSize = 13.sp, color = TG.Muted, maxLines = if (placeExpanded) 4 else 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        fix?.let { f ->
                            val d = Geo.distanceM(f.lat, f.lon, lat, lon)
                            val dir = Geo.compassPoint(Geo.bearingDeg(f.lat, f.lon, lat, lon))
                            Text("${Geo.formatDistance(d)} $dir of you (straight line)", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (selectedWaypoint != null) {
                        IconButton(onClick = { onEditWaypoint(selectedWaypoint) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit waypoint", tint = TG.Ink) }
                    }
                    IconButton(onClick = onCloseSelected) { Icon(Icons.Filled.Close, contentDescription = "Close details", tint = TG.Muted) }
                }
                val coords = "%.6f, %.6f".format(java.util.Locale.US, lat, lon)
                val primary = listOf(
                    Action(Icons.Filled.Navigation, "Go to") { onGoTo(title, lat, lon) },
                    Action(Icons.Filled.DirectionsCar, "Drive") { openDirections(context, lat, lon, title) },
                    Action(Icons.Filled.ContentCopy, "Copy") { copyCoords(context, title, coords) },
                    when {
                        selectedWaypoint != null -> Action(Icons.Filled.Edit, "Edit") { onEditWaypoint(selectedWaypoint) }
                        selected?.noteId != null -> Action(Icons.Filled.Edit, "Edit") { onEditTrailNote(selected.noteId) }
                        else -> Action(Icons.Filled.PushPin, "Pin") { onPinAt(title, lat, lon) }
                    },
                )
                val photoNames = selectedWaypoint?.photos ?: selected?.photos.orEmpty()
                if (photoNames.isNotEmpty()) {
                    Box(Modifier.padding(top = 8.dp)) { PhotoStrip(photoNames, tripVm.photos, onOpen = onOpenPhoto) }
                }
                val more = listOfNotNull(
                    Action(Icons.Filled.Landscape, "Peaks in view") { onPeaks(title, lat, lon) },
                    Action(Icons.Filled.WbSunny, "Weather") { onWeather(title, lat, lon) },
                    if (selected?.isWater == true && selected.osmId != null) Action(Icons.Filled.EditNote, if (waterNote == null) "Water note" else "Edit water note") { onEditNote() } else null,
                    if (selected?.noteId == null) Action(Icons.Filled.StickyNote2, "Trail note") { onNoteAt(lat, lon) } else null,
                )
                if (!placeExpanded) {
                    ActionGrid(primary, Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().clickable { onPlaceExpanded(true) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                        Text("More ▲", fontSize = 12.sp, color = TG.Accent)
                    }
                } else {
                    Column(Modifier.padding(top = 4.dp)) {
                        Text(coords + (selectedWaypoint?.eleM?.let { " · ${Geo.formatFeet(it)}" } ?: ""), fontSize = 13.sp, color = TG.Muted)
                        selectedWaypoint?.note?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp)) }
                        selected?.note?.let { Text(it, fontSize = 13.sp, color = TG.Ink, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
                        if (selected?.isWater == true) {
                            if (waterNote != null) {
                                Text("Your note (${waterNote.date}): ${waterNote.text}", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                Text("Map data can't tell you if it's flowing. Check recent trip reports, and add a note when you visit.", fontSize = 13.sp, color = TG.Warn, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    ActionGrid(primary + more, Modifier.padding(top = 8.dp))
                    if (selected != null && selected.links.isNotEmpty()) {
                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                        selected.links.forEach { (label, url) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { runCatching { uri.openUri(url) } }, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = TG.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(label, fontSize = 14.sp, color = TG.Ink, maxLines = 1)
                            }
                        }
                        Text("Links need a connection.", fontSize = 11.sp, color = TG.Muted)
                    }
                    Row(Modifier.fillMaxWidth().clickable { onPlaceExpanded(false) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                        Text("Less ▼", fontSize = 12.sp, color = TG.Accent)
                    }
                }
                if (recording == null && goTo == null && following == null) return@Column
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = TG.Line)
            }

            if (recording != null) {
                // Live recording totals replace the coordinates while a track records.
                val st = recording.stats
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (recording.paused) TG.Muted else Color(0xFFE53935), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (recording.paused) "Paused" else "Recording",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${Geo.formatDuration(st.totalMs)} total", fontSize = 13.sp, color = TG.Muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCell(Geo.formatMiles(st.distanceM).removeSuffix(" mi"), "miles", Modifier.weight(1f))
                    StatCell("%,d".format((st.gainM * Geo.FT_PER_M).roundToInt()), "ft climbed", Modifier.weight(1f))
                    StatCell(Geo.formatDuration(st.movingMs), "moving", Modifier.weight(1f))
                    StatCell(
                        if (st.movingMs > 60_000) "%.1f".format(st.distanceM / Geo.M_PER_MI / (st.movingMs / 3_600_000.0)) else "–",
                        "mph", Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Keeps recording with the screen off.", fontSize = 12.sp, color = TG.Muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onPause(!recording.paused) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                        Text(if (recording.paused) "Resume" else "Pause", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    }
                    OutlinedButton(onClick = onPin, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("Pin", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    }
                    Button(
                        onClick = onStop, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TG.Ink, contentColor = Color.White),
                        modifier = Modifier.weight(1.4f).height(48.dp),
                    ) { Text("Stop", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                return@Column
            }

            if (!compactRoute) when {
                !hasLocationPermission -> {
                    Text("Allow location to see where you are on the map.", fontSize = 14.sp, color = TG.Ink)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRequestPermission, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Allow location", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                !gpsOn && fix == null -> {
                    Text("Location is turned off in your phone's settings. Turn it on to use GPS; it works without signal.", fontSize = 14.sp, color = TG.Ink)
                }
                fix == null -> {
                    Text("Finding your position…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    Text("Under open sky a first fix takes up to a minute.", fontSize = 13.sp, color = TG.Muted)
                }
                else -> {
                    val (la, lo) = Geo.decimal(fix.lat, fix.lon)
                    Text("$la, $lo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    Text(statsLine(fix), fontSize = 13.sp, color = TG.Muted)
                    daylightLine(fix.lat, fix.lon)?.let { Text(it, fontSize = 13.sp, color = TG.Muted) }
                    placeDescription?.let { Text(it, fontSize = 13.sp, color = TG.Ink, maxLines = 2) }
                    if (selected == null && selectedWaypoint == null && shownRoute == null && goTo == null && following == null) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(Icons.Filled.Landscape, "Peaks in view") { onPeaks("your location", fix.lat, fix.lon) }
                            PillButton(Icons.Filled.WbSunny, "Weather") { onWeather("your location", fix.lat, fix.lon) }
                            PillButton(Icons.Filled.StickyNote2, "Note") { onNoteAt(fix.lat, fix.lon) }
                        }
                    }
                }
            }
            goTo?.let { t ->
                Spacer(Modifier.height(8.dp))
                GoToPanel(t, fix, heading, pace, elevationAt = { la, lo -> tripVm.elevationAt(la, lo) }, onStop = onStopGoTo)
            }
            following?.let { f ->
                FollowPanel(f, pace, onStop = onStopFollowing, modifier = Modifier.padding(top = 8.dp))
            }
            shownRoute?.takeIf { following == null }?.let { r ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 18.dp, height = 4.dp).background(routeColor(r.color), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                        Text(
                            "%.1f mi · +%,d ft · %s".format(r.stats.distanceM / Geo.M_PER_MI, (r.stats.gainM * Geo.FT_PER_M).roundToInt(),
                                Pace.format(pace.minutes(r.stats.distanceM, r.stats.gainM))),
                            fontSize = 12.sp, color = TG.Muted,
                        )
                    }
                    TextButton(onClick = onHideRoute) { Text("Hide", color = TG.Muted) }
                    Button(
                        onClick = onFollow, shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = routeColor(r.color), contentColor = Color.White),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(40.dp),
                    ) { Text("Follow", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
                var showDifficulty by remember(r.id) { mutableStateOf(false) }
                var showTripPlan by remember(r.id) { mutableStateOf(false) }
                val difficulty = remember(r, pace) {
                    com.trailguidegps.app.data.Difficulty.of(r.stats, r.terrain,
                        if (r.camps.isNotEmpty()) com.trailguidegps.app.data.TripDays.days(r, r.camps, pace) else emptyList())
                }
                if (!compactRoute) DifficultyLine(difficulty, onClick = { showDifficulty = true }, showLink = false)
                if (showDifficulty) DifficultyDialog(difficulty) { showDifficulty = false }
                if (r.stats.profile.size >= 2) {
                    ElevationProfile(r.stats, Modifier.padding(top = 2.dp), color = routeColor(r.color), onScrub = onRouteScrub,
                        camps = r.camps.map { it.alongM })
                }
                if (compactRoute) {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Show route tools") { setRouteCompact(false) }.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.Center) {
                        Text("More ▲", fontSize = 13.sp, color = TG.Accent)
                    }
                    return@Column
                }
                r.coords.firstOrNull()?.let { s ->
                    afterDarkWarning(s[0], s[1], System.currentTimeMillis(), pace.minutes(r.stats.distanceM, r.stats.gainM))?.let {
                        Text("If you start now: $it", fontSize = 12.sp, color = TG.Warn, lineHeight = 16.sp)
                    }
                }
                daysLine(r, pace)?.let { Text(it, fontSize = 12.sp, color = TG.Ink) }
                tripVm.areas.collectAsStateWithLifecycle().value["r:${r.id}"]?.line?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = TG.Muted, maxLines = 2, lineHeight = 15.sp)
                }
                var editingNotes by remember(r.id) { mutableStateOf(false) }
                if (r.notes.isNotBlank()) {
                    Text("Notes: ${r.notes}", fontSize = 12.sp, color = TG.Ink, lineHeight = 16.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { editingNotes = true }.padding(top = 2.dp))
                }
                if (editingNotes) RouteNotesDialog(r.name, r.notes, onSave = { tripVm.setRouteNotes(r.id, it); editingNotes = false },
                    onDismiss = { editingNotes = false })
                if (shownRouteCount > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$shownRouteCount routes on the map · tap a line to switch", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = onHideAllRoutes) { Text("Hide all", fontSize = 12.sp, color = TG.Accent) }
                }
                ActionGrid(listOf(
                    Action(Icons.Filled.Info, "Details") { showDifficulty = true },
                    Action(Icons.Filled.Edit, "Edit") { tripVm.editRoute(r.id) },
                    Action(Icons.Filled.WaterDrop, "Water") { onWater(r.name, r.coords) },
                    Action(Icons.Filled.NightsStay, "Days") { onDays(r) },
                    Action(Icons.Filled.WbSunny, "Weather") { onRouteWeather(r) },
                    Action(Icons.Filled.SwapHoriz, "Reverse") { onReverse(r.id) },
                    Action(Icons.Filled.Description, "Trip plan") { showTripPlan = true },
                    Action(Icons.Filled.StickyNote2, "Notes") { editingNotes = true },
                ), Modifier.padding(top = 4.dp), columns = 4)
                if (routeOnly) Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Show less") { setRouteCompact(true) }.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center) {
                    Text("Less ▼ (more map)", fontSize = 12.sp, color = TG.Accent)
                }
                if (showTripPlan) TripPlanDialog(tripVm, r) { showTripPlan = false }
            }
            shownTrackName?.let { name ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 18.dp, height = 4.dp).background(Color(0xFF8E3218), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("Showing $name", fontSize = 13.sp, color = TG.Ink, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = onTrackToRoute) { Text("Make route", color = TG.Accent) }
                    TextButton(onClick = onHideTrack) { Text("Hide", color = TG.Accent) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                Button(
                    onClick = onRecord, shape = RoundedCornerShape(12.dp), contentPadding = pad,
                    colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                    modifier = Modifier.weight(1.25f).height(48.dp),
                ) {
                    Box(Modifier.size(9.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("Record", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(onClick = onRoute, shape = RoundedCornerShape(12.dp), contentPadding = pad, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Route", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RoutePurple, maxLines = 1)
                }
                OutlinedButton(onClick = onPin, shape = RoundedCornerShape(12.dp), contentPadding = pad, modifier = Modifier.weight(0.85f).height(48.dp)) {
                    Text("Pin", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                }
                OutlinedButton(onClick = onSaved, shape = RoundedCornerShape(12.dp), contentPadding = pad, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Saved", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        Text(label, fontSize = 12.sp, color = TG.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = if (selected) RoutePurple else TG.Surface, shadowElevation = 3.dp,
        modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = "$label${if (selected) ", selected" else ""}" },
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else TG.Ink)
        }
    }
}

/** A trail note shown in the place card. */
private fun noteSelection(n: com.trailguidegps.app.data.TrailNote) = Selected(
    n.title, "Your trail note · " + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(n.createdAt)),
    n.lat, n.lon, note = n.text.takeIf { it.trim() != n.title }, noteId = n.id, photos = n.photos,
)

/** The bottom panel with fixed contents, for the screenshot tests. */
@Composable
internal fun BottomPanelForTests(vm: AppViewModel, fix: Fix, selected: Selected?, expanded: Boolean) {
    BottomPanel(
        hidden = false, onHide = {}, onPinAt = { _, _, _ -> }, fix = fix, hasLocationPermission = true, gpsOn = true,
        selected = selected, waterNote = null, onEditNote = {}, selectedWaypoint = null, shownTrackName = null, recording = null,
        onCloseSelected = {}, onEditWaypoint = {}, onHideTrack = {}, onRequestPermission = {}, onRecord = {}, onPause = {},
        onStop = {}, onPin = {}, onSaved = {}, onRoute = {}, shownRoute = null, shownRouteCount = 0, onHideAllRoutes = {},
        pace = Pace(), onHideRoute = {}, onRouteScrub = {}, following = null, goTo = null, heading = null,
        onGoTo = { _, _, _ -> }, onStopGoTo = {}, onWater = { _, _ -> }, onReverse = {}, onPeaks = { _, _, _ -> },
        onWeather = { _, _, _ -> }, onDays = {}, onRouteWeather = {}, tripVm = vm, onFollow = {}, onStopFollowing = {},
        onNoteAt = { _, _ -> }, onEditTrailNote = {}, onOpenPhoto = {}, onTrackToRoute = {},
        placeDescription = "On Pacific Crest Trail, 0.4 mi N of Lost Creek Spring", startExpanded = expanded,
    )
}

/** Driving directions in Google Maps (or the phone's maps app). Needs a signal, or Google's own offline maps. */
private fun openDirections(context: android.content.Context, lat: Double, lon: Double, label: String) {
    val dest = android.net.Uri.encode("%.6f,%.6f".format(java.util.Locale.US, lat, lon))
    val google = android.content.Intent(android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=driving"))
    val any = android.content.Intent(android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("geo:0,0?q=$dest(${android.net.Uri.encode(label)})"))
    runCatching { context.startActivity(google) }.recoverCatching { context.startActivity(any) }
}

/** Copies coordinates to paste anywhere. */
private fun copyCoords(context: android.content.Context, label: String, coords: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, coords))
    // Android 13+ shows its own "copied" message.
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Copied $coords", android.widget.Toast.LENGTH_SHORT).show()
}

/** A one-line color key under a layer switch. */
@Composable
fun LayerLegend(group: String) {
    val items = when (group) {
        "slope" -> listOf(0xFFF2E205 to "25°", 0xFFF59B00 to "30°", 0xFFE3241B to "35°", 0xFF9C27B0 to "45°", 0xFF3B0B59 to "60°+")
        "roadaccess" -> listOf(0xFF2E7D32 to "Car", 0xFFE65100 to "High clearance", 0xFFC62828 to "OHV/closed", 0xFF6A1B9A to "Rough/4WD")
        "burns" -> listOf(0x5E6D4C41 to "Older", 0xC06D4C41 to "Recent")
        else -> return
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 8.dp).background(Color(c.toLong() and 0xFFFFFFFFL), RoundedCornerShape(2.dp)))
                Text(" $label", fontSize = 11.sp, color = TG.Muted)
            }
        }
    }
    if (group == "roadaccess") Text("Forest Service roads; dashed = open only part of the year. Rough/4WD from map data, where tagged.",
        fontSize = 11.sp, color = TG.Muted, modifier = Modifier.padding(bottom = 6.dp))
}

/** Small red round button for the emergency location screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SosButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White,
        border = BorderStroke(2.dp, TG.Danger),
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Emergency: show my location" }) {
            Text("SOS", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = TG.Danger)
        }
    }
}

private fun statsLine(f: Fix): String {
    val parts = mutableListOf<String>()
    f.altitudeM?.let { parts += Geo.formatFeet(it) }
    f.accuracyM?.let { parts += "GPS ±${(it * Geo.FT_PER_M).roundToInt()} ft" }
    if (f.satellitesUsed > 0) parts += "${f.satellitesUsed} satellites"
    if (f.ageSeconds > 30) parts += "last fix ${f.ageSeconds}s ago"
    return parts.joinToString(" · ")
}

private fun formatBytes(b: Long): String =
    if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(maxOf(1.0, b / 1e6))

private fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundButton(onClick: () -> Unit, description: String, active: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) TG.Accent else TG.Surface,
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) { content() }
    }
}

/** Shows one base map: topo, satellite imagery alone, or imagery with roads and names. */
private fun applyBase(style: Style, base: String) {
    setVisible(style, groupLayers(style, "satellite"), base != MapStyle.BASE_TOPO)
    setVisible(style, MapStyle.all(MapStyle.TOPO_FILLS), base == MapStyle.BASE_TOPO)
    setVisible(style, MapStyle.all(MapStyle.TOPO_DETAIL), base != MapStyle.BASE_SATELLITE)
}

private fun isOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** A switch's layers; satellite also covers each saved area's layer. */
private fun groupLayers(style: Style, group: String): List<String> =
    if (group == "satellite") style.layers.map { it.id }.filter { it.startsWith("satellite") }
    else MapStyle.all(MapStyle.GROUPS[group].orEmpty())

private fun setVisible(style: Style, ids: List<String>, on: Boolean) {
    ids.forEach { id ->
        style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }
}

@SuppressLint("MissingPermission")
private fun enableLocationComponent(context: Context, map: MapLibreMap, style: Style, onTrackingDismissed: () -> Unit) {
    val lc = map.locationComponent
    lc.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            .useDefaultLocationEngine(false)
            .build()
    )
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.COMPASS
    lc.cameraMode = CameraMode.NONE
    lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() = onTrackingDismissed()
        override fun onCameraTrackingChanged(currentMode: Int) = Unit
    })
}
