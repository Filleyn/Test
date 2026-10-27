package com.trailguidegps.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.RouteSummary
import com.trailguidegps.app.data.TrackSummary
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.geo.Geo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Your waypoints and tracks, with show-on-map, rename, delete, and GPX import/export. */
@Composable
fun SavedScreen(vm: AppViewModel, onClose: () -> Unit, onEditWaypoint: (Waypoint) -> Unit) {
    val context = LocalContext.current
    val waypoints by vm.waypoints.items.collectAsStateWithLifecycle()
    val tracks by vm.tracks.summaries.collectAsStateWithLifecycle()
    val routes by vm.routes.summaries.collectAsStateWithLifecycle()
    val pace by vm.pace.collectAsStateWithLifecycle()
    var renamingRoute by remember { mutableStateOf<RouteSummary?>(null) }
    var recoloring by remember { mutableStateOf<RouteSummary?>(null) }
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<TrackSummary?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    BackHandler(onBack = onClose)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    fun share(fileName: String, wps: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList()) {
        val uri = vm.exportGpx(fileName, wps, trackIds, routeIds) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export GPX"))
    }

    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to map", tint = TG.Ink) }
            Text("Saved", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import GPX", color = TG.Accent) }
        }
        TabRow(
            selectedTabIndex = tab, containerColor = Color.White, contentColor = TG.Ink,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = TG.Accent) },
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Tracks (${tracks.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Routes (${routes.size})") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Waypoints (${waypoints.size})", maxLines = 1) })
        }

        if (tab == 0) {
            if (tracks.isEmpty()) {
                Empty("No tracks yet", "Tap Record on the map to record your hike. It keeps recording with the screen off.")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(tracks, key = { it.id }) { t ->
                        TrackRow(
                            t,
                            onShow = { vm.showTrack(t.id); onClose() },
                            onRename = { renaming = t },
                            onExport = { share(t.name, emptyList(), listOf(t.id)) },
                            onDelete = { deleting = t.name to { vm.deleteTrack(t.id) } },
                        )
                        HorizontalDivider(color = TG.Line)
                    }
                }
            }
        } else if (tab == 1) {
            if (routes.isEmpty()) {
                Empty("No routes yet", "Tap Route on the map, then tap along the trails to plan one. It works offline.")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(routes, key = { it.id }) { r ->
                        RouteRow(
                            r, pace,
                            onShow = { vm.showRoute(r.id); onClose() },
                            onRename = { renamingRoute = r },
                            onColor = { recoloring = r },
                            onExport = { share(r.name, emptyList(), emptyList(), listOf(r.id)) },
                            onDelete = { deleting = r.name to { vm.deleteRoute(r.id) } },
                        )
                        HorizontalDivider(color = TG.Line)
                    }
                }
            }
        } else {
            if (waypoints.isEmpty()) {
                Empty("No waypoints yet", "Tap Pin on the map to mark where you are, or long-press anywhere on the map.")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(waypoints.sortedByDescending { it.createdAt }, key = { it.id }) { w ->
                        val dist = fix?.let { f -> Geo.distanceM(f.lat, f.lon, w.lat, w.lon) }
                        WaypointRow(
                            w, dist,
                            onShow = { vm.focusOn(listOf(w.lat to w.lon)); onClose() },
                            onEdit = { onEditWaypoint(w) },
                            onExport = { share(w.name, listOf(w), emptyList()) },
                            onDelete = { deleting = w.name to { vm.waypoints.delete(w.id) } },
                        )
                        HorizontalDivider(color = TG.Line)
                    }
                }
            }
        }

        if (tracks.isNotEmpty() || waypoints.isNotEmpty() || routes.isNotEmpty()) {
            OutlinedButton(
                onClick = { share("TrailGuide backup", waypoints, tracks.map { it.id }, routes.map { it.id }) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
            ) { Text("Export everything as GPX", color = TG.Ink, fontWeight = FontWeight.SemiBold) }
        }
    }

    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename track") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.tracks.rename(t.id, name.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    renamingRoute?.let { r ->
        var name by remember(r.id) { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renamingRoute = null },
            title = { Text("Rename route") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.renameRoute(r.id, name.trim()); renamingRoute = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renamingRoute = null }) { Text("Cancel") } },
        )
    }

    recoloring?.let { r ->
        var color by remember(r.id) { mutableStateOf(r.color) }
        AlertDialog(
            onDismissRequest = { recoloring = null },
            title = { Text("Route color") },
            text = { RouteColorPicker(color) { color = it } },
            confirmButton = { TextButton(onClick = { vm.setRouteColor(r.id, color); recoloring = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { recoloring = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$label\"?") },
            text = { Text("This can't be undone. Export it as GPX first if you want a copy.") },
            confirmButton = { TextButton(onClick = { action(); deleting = null }) { Text("Delete", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 14.sp, color = TG.Muted, lineHeight = 20.sp)
    }
}

private val dateFmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)

@Composable
private fun TrackRow(t: TrackSummary, onShow: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(Color(0xFF8E3218), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            Text(
                "${dateFmt.format(Date(t.startedAt))} · ${Geo.formatMiles(t.stats.distanceM)} · +%,d ft · %s moving".format(
                    (t.stats.gainM * Geo.FT_PER_M).roundToInt(), Geo.formatDuration(t.stats.movingMs),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
        }
        RowMenu(listOf("Show on map" to onShow, "Rename" to onRename, "Export GPX" to onExport, "Delete" to onDelete))
    }
}

@Composable
private fun RouteRow(r: RouteSummary, pace: Pace, onShow: () -> Unit, onRename: () -> Unit, onColor: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(routeColor(r.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            Text(
                "%.1f mi · +%,d ft · −%,d ft · est %s".format(
                    r.distanceM / Geo.M_PER_MI, (r.gainM * Geo.FT_PER_M).roundToInt(), (r.lossM * Geo.FT_PER_M).roundToInt(),
                    Pace.format(pace.minutes(r.distanceM, r.gainM)),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
            Text("Planned ${dateFmt.format(Date(r.createdAt))}", fontSize = 12.sp, color = TG.Muted)
        }
        RowMenu(listOf("Show on map" to onShow, "Rename" to onRename, "Change color" to onColor, "Export GPX" to onExport, "Delete" to onDelete))
    }
}

@Composable
private fun WaypointRow(w: Waypoint, distanceM: Double?, onShow: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(Color(android.graphics.Color.parseColor(w.color)), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            val parts = mutableListOf(dateFmt.format(Date(w.createdAt)))
            distanceM?.let { parts += "${Geo.formatDistance(it)} away" }
            if (w.note.isNotBlank()) parts += w.note
            Text(parts.joinToString(" · "), fontSize = 13.sp, color = TG.Muted, maxLines = 2)
        }
        RowMenu(listOf("Show on map" to onShow, "Edit" to onEdit, "Export GPX" to onExport, "Delete" to onDelete))
    }
}

@Composable
private fun RowMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TG.Muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (label == "Delete") TG.Danger else TG.Ink) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}
