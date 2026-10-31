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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.trailguidegps.app.data.FolderStore
import com.trailguidegps.app.data.Pace
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.heightIn
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
    val shownRoutes by vm.shownRoutes.collectAsStateWithLifecycle()
    var renamingRoute by remember { mutableStateOf<RouteSummary?>(null) }
    var recoloring by remember { mutableStateOf<RouteSummary?>(null) }
    var tripPlanFor by remember { mutableStateOf<com.trailguidegps.app.data.SavedRoute?>(null) }
    var waterFor by remember { mutableStateOf<com.trailguidegps.app.data.SavedRoute?>(null) }
    val waterNotes by vm.waterNotes.notes.collectAsStateWithLifecycle()
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<TrackSummary?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<Pair<String, String>?>(null) } // item key, item name
    var adding by remember { mutableStateOf<String?>(null) } // folder id
    var naming by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<com.trailguidegps.app.data.Folder?>(null) }
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
        val folders by vm.folders.folders.collectAsStateWithLifecycle()
        val members by vm.folders.members.collectAsStateWithLifecycle()
        fun folderName(key: String) = members[key]?.let { fid -> folders.firstOrNull { it.id == fid }?.name }

        @Composable
        fun TrackItem(t: TrackSummary, inFolder: Boolean) {
            TrackRow(
                t, if (inFolder) null else folderName(FolderStore.trackKey(t.id)),
                onShow = { vm.showTrack(t.id); onClose() },
                onRename = { renaming = t },
                onMove = { moving = FolderStore.trackKey(t.id) to t.name },
                onExport = { share(t.name, emptyList(), listOf(t.id)) },
                onDelete = { deleting = t.name to { vm.deleteTrack(t.id); vm.folders.put(FolderStore.trackKey(t.id), null) } },
            )
            HorizontalDivider(color = TG.Line)
        }

        @Composable
        fun RouteItem(r: RouteSummary, inFolder: Boolean) {
            RouteRow(
                r, pace, shownRoutes.any { it.id == r.id }, if (inFolder) null else folderName(FolderStore.routeKey(r.id)),
                onShow = { vm.showRoute(r.id); onClose() },
                onHide = { vm.hideRoute(r.id) },
                onEdit = { vm.editRoute(r.id); onClose() },
                onToggleMap = { on -> if (on) vm.addRouteToMap(r.id) else vm.hideRoute(r.id) },
                onRename = { renamingRoute = r },
                onColor = { recoloring = r },
                onTripPlan = { tripPlanFor = vm.routes.load(r.id) },
                onWater = { waterFor = vm.routes.load(r.id) },
                onReverse = { vm.reverseRoute(r.id) },
                onMove = { moving = FolderStore.routeKey(r.id) to r.name },
                onExport = { share(r.name, emptyList(), emptyList(), listOf(r.id)) },
                onDelete = { deleting = r.name to { vm.deleteRoute(r.id); vm.folders.put(FolderStore.routeKey(r.id), null) } },
            )
            HorizontalDivider(color = TG.Line)
        }

        @Composable
        fun WaypointItem(w: Waypoint, inFolder: Boolean) {
            WaypointRow(
                w, fix?.let { f -> Geo.distanceM(f.lat, f.lon, w.lat, w.lon) }, if (inFolder) null else folderName(FolderStore.waypointKey(w.id)),
                onShow = { vm.focusOn(listOf(w.lat to w.lon)); onClose() },
                onEdit = { onEditWaypoint(w) },
                onMove = { moving = FolderStore.waypointKey(w.id) to w.name },
                onExport = { share(w.name, listOf(w), emptyList()) },
                onDelete = { deleting = w.name to { vm.waypoints.delete(w.id); vm.folders.put(FolderStore.waypointKey(w.id), null) } },
            )
            HorizontalDivider(color = TG.Line)
        }

        val folder = openFolder?.let { id -> folders.firstOrNull { it.id == id } }
        if (folder != null) {
            // Inside one folder: everything in it, by type.
            val keys = members.filterValues { it == folder.id }.keys
            val fRoutes = routes.filter { FolderStore.routeKey(it.id) in keys }
            val fTracks = tracks.filter { FolderStore.trackKey(it.id) in keys }
            val fWps = waypoints.filter { FolderStore.waypointKey(it.id) in keys }.sortedBy { it.name.lowercase() }
            BackHandler { openFolder = null }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openFolder = null }) { Text("‹ Folders", color = TG.Accent) }
                Text(folder.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1, modifier = Modifier.weight(1f))
                RowMenu(listOf(
                    "Show all on map" to { vm.showFolder(folder.id); onClose() },
                    "Add items" to { adding = folder.id },
                    "Export GPX" to { share(folder.name, fWps, fTracks.map { it.id }, fRoutes.map { it.id }) },
                    "Rename" to { renamingFolder = folder },
                    "Delete folder" to { deleting = folder.name to { vm.folders.delete(folder.id); openFolder = null } },
                ))
            }
            HorizontalDivider(color = TG.Line)
            if (keys.isEmpty() || (fRoutes.isEmpty() && fTracks.isEmpty() && fWps.isEmpty())) {
                Empty("Empty folder", "Tap ⋮ → Add items, or choose this folder when you save a route, track or waypoint.")
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    if (fRoutes.isNotEmpty()) { item { Section("Routes") }; items(fRoutes, key = { "r" + it.id }) { RouteItem(it, true) } }
                    if (fTracks.isNotEmpty()) { item { Section("Tracks") }; items(fTracks, key = { "t" + it.id }) { TrackItem(it, true) } }
                    if (fWps.isNotEmpty()) { item { Section("Waypoints") }; items(fWps, key = { "w" + it.id }) { WaypointItem(it, true) } }
                }
            }
        } else {
        TabRow(
            selectedTabIndex = tab, containerColor = Color.White, contentColor = TG.Ink,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = TG.Accent) },
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Folders", maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Routes", maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Tracks", maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Points", maxLines = 1, fontSize = 13.sp) })
        }

        when (tab) {
            0 -> LazyColumn(Modifier.weight(1f)) {
                item {
                    TextButton(onClick = { naming = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("+ New folder", color = TG.Accent, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = TG.Line)
                }
                if (folders.isEmpty()) item {
                    Empty("No folders yet", "Make a folder for a trip, like \"Mt Jefferson 2027\", and keep its routes, tracks and waypoints together. " +
                        "Then show the whole trip on the map with one tap.")
                }
                items(folders, key = { it.id }) { f ->
                    val keys = members.filterValues { it == f.id }.keys
                    val nR = routes.count { FolderStore.routeKey(it.id) in keys }
                    val nT = tracks.count { FolderStore.trackKey(it.id) in keys }
                    val nW = waypoints.count { FolderStore.waypointKey(it.id) in keys }
                    Row(Modifier.fillMaxWidth().clickable { openFolder = f.id }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = TG.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    nR.takeIf { it > 0 }?.let { "$it route${if (it == 1) "" else "s"}" },
                                    nT.takeIf { it > 0 }?.let { "$it track${if (it == 1) "" else "s"}" },
                                    nW.takeIf { it > 0 }?.let { "$it waypoint${if (it == 1) "" else "s"}" },
                                ).joinToString(" · ").ifEmpty { "Empty" },
                                fontSize = 13.sp, color = TG.Muted,
                            )
                        }
                        RowMenu(listOf(
                            "Show all on map" to { vm.showFolder(f.id); onClose() },
                            "Rename" to { renamingFolder = f },
                            "Delete folder" to { deleting = f.name to { vm.folders.delete(f.id) } },
                        ))
                    }
                    HorizontalDivider(color = TG.Line)
                }
            }
            1 -> if (routes.isEmpty()) {
                Empty("No routes yet", "Tap Route on the map, then tap along the trails to plan one. It works offline.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${shownRoutes.size} of ${routes.size} on the map · tap the eye to choose", fontSize = 12.sp, color = TG.Muted,
                            modifier = Modifier.weight(1f))
                        if (shownRoutes.size < routes.size) TextButton(onClick = { vm.showAllRoutes() }) { Text("Show all", fontSize = 13.sp, color = TG.Accent) }
                        if (shownRoutes.isNotEmpty()) TextButton(onClick = { vm.hideAllRoutes() }) { Text("Hide all", fontSize = 13.sp, color = TG.Accent) }
                    }
                    HorizontalDivider(color = TG.Line)
                }
                items(routes, key = { it.id }) { RouteItem(it, false) }
            }
            2 -> if (tracks.isEmpty()) {
                Empty("No tracks yet", "Tap Record on the map to record your hike. It keeps recording with the screen off.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) { items(tracks, key = { it.id }) { TrackItem(it, false) } }
            else -> if (waypoints.isEmpty()) {
                Empty("No waypoints yet", "Tap Pin on the map to mark where you are, or long-press anywhere on the map.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) {
                items(waypoints.sortedByDescending { it.createdAt }, key = { it.id }) { WaypointItem(it, false) }
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

    if (naming) NewFolderDialog(onDismiss = { naming = false }) { name -> naming = false; vm.folders.create(name) }
    renamingFolder?.let { f ->
        NewFolderDialog(initial = f.name, title = "Rename folder", onDismiss = { renamingFolder = null }) { name ->
            vm.folders.rename(f.id, name); renamingFolder = null
        }
    }
    moving?.let { (key, itemName) ->
        MoveToFolderDialog(
            itemName, vm.folders.folders.collectAsStateWithLifecycle().value, vm.folders.members.value[key],
            onPick = { fid -> vm.folders.put(key, fid); if (fid != null) vm.rememberFolder(fid); moving = null },
            onCreate = { vm.folders.create(it) },
            onDismiss = { moving = null },
        )
    }
    adding?.let { fid ->
        AddToFolderDialog(
            routes = routes, tracks = tracks, waypoints = waypoints, members = vm.folders.members.value, folderId = fid,
            onAdd = { keys -> keys.forEach { vm.folders.put(it, fid) }; adding = null },
            onDismiss = { adding = null },
        )
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

    tripPlanFor?.let { r -> TripPlanDialog(vm, r) { tripPlanFor = null } }
    waterFor?.let { r ->
        WaterAlongRouteDialog(vm, r.name, r.coords, waterNotes,
            onPick = { la, lo -> waterFor = null; vm.showRoute(r.id); vm.focusOn(listOf(la to lo)); onClose() },
            onDismiss = { waterFor = null })
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
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Pick which folder an item goes in. */
@Composable
private fun MoveToFolderDialog(
    itemName: String, folders: List<com.trailguidegps.app.data.Folder>, current: String?,
    onPick: (String?) -> Unit, onCreate: (String) -> com.trailguidegps.app.data.Folder, onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NewFolderDialog(onDismiss = { naming = false }) { name -> naming = false; onPick(onCreate(name).id) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"$itemName\"") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    TextButton(onClick = { naming = true }) { Text("+ New folder", color = TG.Accent) }
                }
                items(folders, key = { it.id }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = if (f.id == current) TG.Accent else TG.Muted)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name + if (f.id == current) "  (now)" else "", fontSize = 16.sp, color = TG.Ink)
                    }
                }
                if (current != null) item {
                    TextButton(onClick = { onPick(null) }) { Text("Take out of folder", color = TG.Danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tick routes, tracks and waypoints to add to a folder. */
@Composable
private fun AddToFolderDialog(
    routes: List<RouteSummary>, tracks: List<TrackSummary>, waypoints: List<Waypoint>,
    members: Map<String, String>, folderId: String, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit,
) {
    val choices = remember(routes, tracks, waypoints, members) {
        routes.map { FolderStore.routeKey(it.id) to "Route: ${it.name}" } +
            tracks.map { FolderStore.trackKey(it.id) to "Track: ${it.name}" } +
            waypoints.sortedBy { it.name.lowercase() }.map { FolderStore.waypointKey(it.id) to "Waypoint: ${it.name}" }
    }.filter { members[it.first] != folderId }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to folder") },
        text = {
            if (choices.isEmpty()) Text("Everything is already in this folder.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it.first }) { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { if (key in picked) picked.remove(key) else picked.add(key) },
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = key in picked, onCheckedChange = { on -> if (on) picked.add(key) else picked.remove(key) })
                        Column {
                            Text(label, fontSize = 15.sp, color = TG.Ink, maxLines = 1)
                            if (members[key] != null) Text("Moves it from its current folder", fontSize = 11.sp, color = TG.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onAdd(picked.toList()) }) { Text("Add ${picked.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun TrackRow(t: TrackSummary, folderName: String?, onShow: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(Color(0xFF8E3218), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            Text(
                "${dateFmt.format(Date(t.startedAt))} · ${Geo.formatMiles(t.stats.distanceM)} · +%,d ft · %s moving".format(
                    (t.stats.gainM * Geo.FT_PER_M).roundToInt(), Geo.formatDuration(t.stats.movingMs),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
        }
        RowMenu(listOf("Show on map" to onShow, "Rename" to onRename, "Move to folder" to onMove, "Export GPX" to onExport, "Delete" to onDelete))
    }
}

@Composable
private fun RouteRow(
    r: RouteSummary, pace: Pace, onMap: Boolean, folderName: String?,
    onShow: () -> Unit, onHide: () -> Unit, onEdit: () -> Unit, onToggleMap: (Boolean) -> Unit, onRename: () -> Unit, onColor: () -> Unit, onTripPlan: () -> Unit,
    onWater: () -> Unit, onReverse: () -> Unit,
    onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(routeColor(r.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            Text(
                "%.1f mi · +%,d ft · −%,d ft · est %s".format(
                    r.distanceM / Geo.M_PER_MI, (r.gainM * Geo.FT_PER_M).roundToInt(), (r.lossM * Geo.FT_PER_M).roundToInt(),
                    Pace.format(pace.minutes(r.distanceM, r.gainM)),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
            val level = com.trailguidegps.app.data.Difficulty.rating(r.distanceM, r.gainM).second
            Text(com.trailguidegps.app.data.Difficulty.LABELS[level], fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DifficultyColors[level])
            Text("Planned ${dateFmt.format(Date(r.createdAt))}" + if (onMap) " · on the map" else "", fontSize = 12.sp,
                color = if (onMap) routeColor(r.color) else TG.Muted, fontWeight = if (onMap) FontWeight.SemiBold else FontWeight.Normal)
        }
        // On the map or not, without leaving the list, so several can be picked.
        IconButton(onClick = { onToggleMap(!onMap) }) {
            Icon(if (onMap) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (onMap) "Hide ${r.name} from the map" else "Show ${r.name} on the map",
                tint = if (onMap) routeColor(r.color) else TG.Muted)
        }
        RowMenu(listOfNotNull(
            "Show on map" to onShow,
            if (onMap) "Hide from map" to onHide else null,
            "Edit route" to onEdit,
            "Water along route" to onWater,
            "Reverse for the hike out" to onReverse,
            "Trip plan (PDF)" to onTripPlan,
            "Move to folder" to onMove,
            "Rename" to onRename, "Change color" to onColor, "Export GPX" to onExport, "Delete" to onDelete,
        ))
    }
}

@Composable
private fun WaypointRow(w: Waypoint, distanceM: Double?, folderName: String?, onShow: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(Color(android.graphics.Color.parseColor(w.color)), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            val parts = mutableListOf(dateFmt.format(Date(w.createdAt)))
            distanceM?.let { parts += "${Geo.formatDistance(it)} away" }
            if (w.note.isNotBlank()) parts += w.note
            Text(parts.joinToString(" · "), fontSize = 13.sp, color = TG.Muted, maxLines = 2)
        }
        RowMenu(listOf("Show on map" to onShow, "Edit" to onEdit, "Move to folder" to onMove, "Export GPX" to onExport, "Delete" to onDelete))
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
