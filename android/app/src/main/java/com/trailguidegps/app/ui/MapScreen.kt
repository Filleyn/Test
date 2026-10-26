package com.trailguidegps.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
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

private val layerNames = linkedMapOf(
    "hillshade" to "Hillshade",
    "contours" to "Contour lines (40 ft)",
    "trails" to "Trails and tracks",
    "water" to "Water sources",
    "places" to "Campsites, trailheads and peaks",
)

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
    val layerOn = remember { mutableStateMapOf<String, Boolean>().apply { MapStyle.GROUPS.keys.forEach { put(it, true) } } }

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
            m.addOnMapLongClickListener { latLng ->
                editing = Waypoint(name = vm.waypoints.nextName(), lat = latLng.latitude, lon = latLng.longitude)
                true
            }
            m.addOnMapClickListener { latLng ->
                val point = m.projection.toScreenLocation(latLng)
                val wpHit = m.queryRenderedFeatures(point, MapStyle.LAYER_WAYPOINTS).firstOrNull()
                if (wpHit != null && wpHit.hasProperty("id")) {
                    selectedWaypointId = wpHit.getStringProperty("id")
                    vm.select(null)
                    return@addOnMapClickListener true
                }
                selectedWaypointId = null
                val hit = m.queryRenderedFeatures(point, "water-points", "places", "peaks", "trail-hit", "trail-hit-unnamed").firstOrNull()
                vm.select(hit?.let { f ->
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
                    prop("last_edit")?.let { parts += "map data edited $it" }
                    val geomPoint = f.geometry() as? org.maplibre.geojson.Point
                    Selected(
                        name, parts.joinToString(" · "),
                        geomPoint?.latitude() ?: latLng.latitude, geomPoint?.longitude() ?: latLng.longitude,
                        osmId = prop("osm_id"), isWater = isWater,
                    )
                })
                hit != null
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

    // (Re)load the style whenever new map files are installed.
    LaunchedEffect(map, mapVersion) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.build(vm.store))) { s ->
            MapIcons.addAll(context, s)
            MapStyle.GROUPS.forEach { (group, ids) -> setVisible(s, ids, layerOn[group] != false) }
            if (!cameraPlaced) {
                vm.store.bbox()?.let { b ->
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
        val b = vm.store.bbox()
        if (!zoomedToMe && style != null && b != null &&
            f.lon >= b[0] && f.lat >= b[1] && f.lon <= b[2] && f.lat <= b[3]
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
    LaunchedEffect(style, recording?.points?.size) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_RECORDING)
            ?.setGeoJson(TrackStore.lineGeoJson(recording?.points.orEmpty()))
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
            Toast.makeText(context, "Waiting for GPS. You can also long-press the map to drop a pin.", Toast.LENGTH_LONG).show()
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

        // Top: offline badge and layers button, plus a notice when you're off the map
        val bbox = remember(mapVersion) { vm.store.bbox() }
        val outside = fix != null && bbox != null &&
            (fix!!.lon < bbox[0] || fix!!.lat < bbox[1] || fix!!.lon > bbox[2] || fix!!.lat > bbox[3])
        Column(Modifier.statusBarsPadding().padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(onClick = onOpenSearch, shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp, modifier = Modifier.weight(1f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TG.Muted)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Search trails, water, places", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                        Text("Offline · ${vm.store.title().substringBefore(" (")} maps, ${vm.store.builtDate() ?: ""}", fontSize = 12.sp, color = TG.Muted, maxLines = 1)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(onClick = { showLayers = true }, description = "Map layers") {
                    Icon(Icons.Filled.Layers, contentDescription = null, tint = TG.Ink)
                }
                SosButton(onClick = onEmergency)
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
                    Text("You're outside your downloaded maps", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6B3A00))
                    Text("Tap to show the area your maps cover.", fontSize = 13.sp, color = Color(0xFF6B3A00))
                }
            }
        }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(
                Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundButton(onClick = { toggleHeading() }, description = if (headingUp) "Switch to north up" else "Switch to heading up", active = headingUp) {
                    Icon(if (headingUp) Icons.Filled.Navigation else Icons.Filled.Explore, contentDescription = null, tint = if (headingUp) Color.White else TG.Accent)
                }
                RoundButton(onClick = { centerOnMe() }, description = "Center on my location", active = following && !headingUp) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, tint = if (following && !headingUp) Color.White else TG.Blue)
                }
            }
            BottomPanel(
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
            onDelete = { vm.waypoints.delete(w.id); editing = null; selectedWaypointId = null },
            onDismiss = { editing = null },
        )
    }

    if (askStop) {
        StopRecordingDialog(
            defaultName = vm.defaultTrackName(recording?.startedAt ?: System.currentTimeMillis()),
            onSave = { name -> askStop = false; vm.stopRecording(name, save = true) },
            onDiscard = { askStop = false; vm.stopRecording(null, save = false) },
            onDismiss = { askStop = false },
        )
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }, containerColor = Color.White) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Map layers", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                Spacer(Modifier.height(8.dp))
                layerNames.forEach { (group, label) ->
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 16.sp, color = TG.Ink, modifier = Modifier.weight(1f))
                        Switch(
                            checked = layerOn[group] != false,
                            onCheckedChange = { on ->
                                layerOn[group] = on
                                style?.let { s -> setVisible(s, MapStyle.GROUPS[group].orEmpty(), on) }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = TG.Accent),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = TG.Line)
                Text("Map data", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${vm.store.title()}, built ${vm.store.builtDate() ?: "unknown"}",
                    fontSize = 14.sp, color = TG.Ink,
                )
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
    placeDescription: String?,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).background(TG.Line, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(10.dp))

            // Details for whatever was tapped on the map.
            if (selectedWaypoint != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 4.dp).size(14.dp).background(Color(android.graphics.Color.parseColor(selectedWaypoint.color)), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selectedWaypoint.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                        val (la, lo) = Geo.decimal(selectedWaypoint.lat, selectedWaypoint.lon)
                        Text("Waypoint · $la, $lo" + (selectedWaypoint.eleM?.let { " · ${Geo.formatFeet(it)}" } ?: ""), fontSize = 13.sp, color = TG.Muted)
                        if (selectedWaypoint.note.isNotBlank()) Text(selectedWaypoint.note, fontSize = 14.sp, color = TG.Ink)
                        fix?.let { f ->
                            val d = Geo.distanceM(f.lat, f.lon, selectedWaypoint.lat, selectedWaypoint.lon)
                            val dir = Geo.compassPoint(Geo.bearingDeg(f.lat, f.lon, selectedWaypoint.lat, selectedWaypoint.lon))
                            Text("${Geo.formatDistance(d)} $dir of you (straight line)", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    IconButton(onClick = { onEditWaypoint(selectedWaypoint) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit waypoint", tint = TG.Ink) }
                    IconButton(onClick = onCloseSelected) { Icon(Icons.Filled.Close, contentDescription = "Close details", tint = TG.Muted) }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = TG.Line)
            } else if (selected != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                        Text(selected.subtitle, fontSize = 13.sp, color = TG.Muted)
                        if (selected.isWater) {
                            if (waterNote != null) {
                                Text("Your note (${waterNote.date}): ${waterNote.text}", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                Text("Map data can't tell you if it's flowing. Check recent trip reports, and add a note when you visit.", fontSize = 13.sp, color = Color(0xFF6B3A00), modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        fix?.let { f ->
                            val d = Geo.distanceM(f.lat, f.lon, selected.lat, selected.lon)
                            val dir = Geo.compassPoint(Geo.bearingDeg(f.lat, f.lon, selected.lat, selected.lon))
                            Text("${Geo.formatDistance(d)} $dir of you (straight line)", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (selected.isWater && selected.osmId != null) {
                        TextButton(onClick = onEditNote) { Text(if (waterNote == null) "Add note" else "Edit note", color = TG.Accent) }
                    }
                    IconButton(onClick = onCloseSelected) { Icon(Icons.Filled.Close, contentDescription = "Close details", tint = TG.Muted) }
                }
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

            when {
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
                    placeDescription?.let { Text(it, fontSize = 13.sp, color = TG.Ink, maxLines = 2) }
                }
            }
            shownTrackName?.let { name ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 18.dp, height = 4.dp).background(Color(0xFF8E3218), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("Showing $name", fontSize = 13.sp, color = TG.Ink, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = onHideTrack) { Text("Hide", color = TG.Accent) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRecord, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                    modifier = Modifier.weight(1.4f).height(48.dp),
                ) {
                    Box(Modifier.size(10.dp).background(Color.White, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("Record", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onPin, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Pin", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                }
                OutlinedButton(onClick = onSaved, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Saved", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
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

private fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundButton(onClick: () -> Unit, description: String, active: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) TG.Accent else Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) { content() }
    }
}

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
