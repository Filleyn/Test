package com.trailguidegps.app.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
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
    val notes by vm.trailNotes.items.collectAsStateWithLifecycle()
    var editingNote by remember { mutableStateOf<com.trailguidegps.app.data.TrailNote?>(null) }
    val tracks by vm.tracks.summaries.collectAsStateWithLifecycle()
    val routes by vm.routes.summaries.collectAsStateWithLifecycle()
    val pace by vm.pace.collectAsStateWithLifecycle()
    val shownRoutes by vm.shownRoutes.collectAsStateWithLifecycle()
    var renamingRoute by remember { mutableStateOf<RouteSummary?>(null) }
    var recoloring by remember { mutableStateOf<RouteSummary?>(null) }
    var tripPlanFor by remember { mutableStateOf<com.trailguidegps.app.data.SavedRoute?>(null) }
    var notesFor by remember { mutableStateOf<com.trailguidegps.app.data.SavedRoute?>(null) }
    var datesFor by remember { mutableStateOf<com.trailguidegps.app.data.SavedRoute?>(null) }
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
    // Search, sort and the area filter. The sort is remembered.
    val areas by vm.areas.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var areaFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val savedPrefs = remember { context.getSharedPreferences("saved", android.content.Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(runCatching { SavedSort.valueOf(savedPrefs.getString("sort", null) ?: "NEWEST") }.getOrDefault(SavedSort.NEWEST)) }
    val filtering = query.isNotBlank() || areaFilter != null
    fun words(): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(name: String, key: String, extra: String = ""): Boolean {
        val a = areas[key]
        if (areaFilter != null && (a == null || areaFilter !in a.names)) return false
        val w = words()
        if (w.isEmpty()) return true
        val hay = "$name ${a?.line.orEmpty()} $extra".lowercase()
        return w.all { it in hay }
    }
    fun away(lat: Double?, lon: Double?): Double =
        fix?.let { f -> if (lat == null || lon == null || lat.isNaN()) null else Geo.distanceM(f.lat, f.lon, lat, lon) } ?: Double.MAX_VALUE
    fun <T> sorted(list: List<T>, created: (T) -> Long, name: (T) -> String, key: (T) -> String,
                   lat: ((T) -> Double)? = null, lon: ((T) -> Double)? = null,
                   length: ((T) -> Double)? = null, climb: ((T) -> Double)? = null, trip: ((T) -> Long)? = null): List<T> = when (sort) {
        SavedSort.NEWEST -> list.sortedByDescending(created)
        SavedSort.OLDEST -> list.sortedBy(created)
        SavedSort.NAME -> list.sortedBy { name(it).lowercase() }
        SavedSort.NEAREST -> list.sortedBy { if (lat != null && lon != null) away(lat(it), lon(it)) else away(areas[key(it)]?.lat, areas[key(it)]?.lon) }
        SavedSort.LONGEST -> if (length != null) list.sortedByDescending(length) else list.sortedByDescending(created)
        SavedSort.CLIMB -> if (climb != null) list.sortedByDescending(climb) else list.sortedByDescending(created)
        SavedSort.TRIP -> list.sortedWith(compareBy<T>({ tripOrder(trip?.invoke(it) ?: 0L) }, { tripKey(trip?.invoke(it) ?: 0L) }))
    }
    BackHandler(onBack = onClose)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGpx(uri)
    }

    fun share(fileName: String, wps: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList(), noteIds: List<String> = emptyList()) {
        val uri = vm.exportGpx(fileName, wps, trackIds, routeIds, noteIds) ?: return
        ShareFile.send(context, uri, "application/gpx+xml", "Export GPX")
    }

    Column(Modifier.fillMaxSize().background(TG.Surface).safeDrawingPadding()) {
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
                t, if (inFolder) null else folderName(FolderStore.trackKey(t.id)), areas["t:${t.id}"]?.line,
                onShow = { vm.showTrack(t.id); onClose() },
                onToRoute = { vm.trackToRoute(t.id); onClose() },
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
                r, pace, shownRoutes.any { it.id == r.id }, if (inFolder) null else folderName(FolderStore.routeKey(r.id)), areas["r:${r.id}"]?.line,
                onShow = { vm.showRoute(r.id); onClose() },
                onHide = { vm.hideRoute(r.id) },
                onEdit = { vm.editRoute(r.id); onClose() },
                onToggleMap = { on -> if (on) vm.addRouteToMap(r.id) else vm.hideRoute(r.id) },
                onRename = { renamingRoute = r },
                onColor = { recoloring = r },
                onNotes = { notesFor = vm.routes.load(r.id) },
                onDates = { datesFor = vm.routes.load(r.id) },
                onOutBack = { vm.setRouteOutAndBack(r.id, !r.outAndBack) },
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
                w, fix?.let { f -> Geo.distanceM(f.lat, f.lon, w.lat, w.lon) }, if (inFolder) null else folderName(FolderStore.waypointKey(w.id)), areas["w:${w.id}"]?.line,
                onShow = { vm.focusOn(listOf(w.lat to w.lon)); onClose() },
                onEdit = { onEditWaypoint(w) },
                onMove = { moving = FolderStore.waypointKey(w.id) to w.name },
                onExport = { share(w.name, listOf(w), emptyList()) },
                onDelete = { deleting = w.name to { vm.deleteWaypoint(w.id) } },
            )
            HorizontalDivider(color = TG.Line)
        }

        @Composable
        fun NoteItem(n: com.trailguidegps.app.data.TrailNote, inFolder: Boolean) {
            Row(Modifier.fillMaxWidth().clickable { vm.focusOn(listOf(n.lat to n.lon)); onClose() }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(0xFFF2B705), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                    if (!inFolder) folderName(FolderStore.noteKey(n.id))?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
                    AreaLine(areas["n:${n.id}"]?.line)
                    val parts = mutableListOf(dateFmt.format(Date(n.createdAt)))
                    fix?.let { f -> parts += "${Geo.formatDistance(Geo.distanceM(f.lat, f.lon, n.lat, n.lon))} away" }
                    if (n.photos.isNotEmpty()) parts += "${n.photos.size} photo${if (n.photos.size == 1) "" else "s"}"
                    Text(parts.joinToString(" · "), fontSize = 13.sp, color = TG.Muted)
                    if (n.text.trim() != n.title) Text(n.text, fontSize = 13.sp, color = TG.Ink, maxLines = 2)
                }
                RowMenu(listOf(
                    "Show on map" to { vm.focusOn(listOf(n.lat to n.lon)); onClose() },
                    "Edit" to { editingNote = n },
                    "Move to folder" to { moving = FolderStore.noteKey(n.id) to n.title },
                    "Export GPX" to { share(n.title, emptyList(), emptyList(), noteIds = listOf(n.id)) },
                    "Delete" to { deleting = n.title to { vm.deleteNote(n.id) } },
                ))
            }
            HorizontalDivider(color = TG.Line)
        }

        val shownR = sorted(routes.filter { matches(it.name, "r:${it.id}", it.notes) }, { it.createdAt }, { it.name }, { "r:${it.id}" },
            length = { it.distanceM }, climb = { it.gainM }, trip = { it.tripStart })
        val shownT = sorted(tracks.filter { matches(it.name, "t:${it.id}") }, { it.startedAt }, { it.name }, { "t:${it.id}" },
            length = { it.stats.distanceM }, climb = { it.stats.gainM })
        val shownW = sorted(waypoints.filter { matches(it.name, "w:${it.id}", it.note) }, { it.createdAt }, { it.name }, { "w:${it.id}" },
            lat = { it.lat }, lon = { it.lon })
        val shownN = sorted(notes.filter { matches(it.title, "n:${it.id}", it.text) }, { it.createdAt }, { it.title }, { "n:${it.id}" },
            lat = { it.lat }, lon = { it.lon })
        val shownF = folders.filter { f -> words().all { it in f.name.lowercase() } }

        val folder = openFolder?.let { id -> folders.firstOrNull { it.id == id } }
        if (folder != null) {
            // Inside one folder: everything in it, by type.
            val keys = members.filterValues { it == folder.id }.keys
            val fRoutes = routes.filter { FolderStore.routeKey(it.id) in keys }
            val fTracks = tracks.filter { FolderStore.trackKey(it.id) in keys }
            val fWps = waypoints.filter { FolderStore.waypointKey(it.id) in keys }.sortedBy { it.name.lowercase() }
            val fNotes = notes.filter { FolderStore.noteKey(it.id) in keys }
            BackHandler { openFolder = null }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openFolder = null }) { Text("‹ Folders", color = TG.Accent) }
                Text(folder.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1, modifier = Modifier.weight(1f))
                RowMenu(listOf(
                    "Show all on map" to { vm.showFolder(folder.id); onClose() },
                    "Add items" to { adding = folder.id },
                    "Export GPX" to { share(folder.name, fWps, fTracks.map { it.id }, fRoutes.map { it.id }, fNotes.map { it.id }) },
                    "Rename" to { renamingFolder = folder },
                    "Delete folder" to { deleting = folder.name to { vm.folders.delete(folder.id); openFolder = null } },
                ))
            }
            HorizontalDivider(color = TG.Line)
            if (keys.isEmpty() || (fRoutes.isEmpty() && fTracks.isEmpty() && fWps.isEmpty() && fNotes.isEmpty())) {
                Empty("Empty folder", "Tap ⋮ → Add items, or choose this folder when you save a route, track or waypoint.")
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    if (fRoutes.isNotEmpty()) { item { Section("Routes") }; items(fRoutes, key = { "r" + it.id }) { RouteItem(it, true) } }
                    if (fTracks.isNotEmpty()) { item { Section("Tracks") }; items(fTracks, key = { "t" + it.id }) { TrackItem(it, true) } }
                    if (fWps.isNotEmpty()) { item { Section("Waypoints") }; items(fWps, key = { "w" + it.id }) { WaypointItem(it, true) } }
                    if (fNotes.isNotEmpty()) { item { Section("Trail notes") }; items(fNotes, key = { "n" + it.id }) { NoteItem(it, true) } }
                }
            }
        } else {
        TabRow(
            selectedTabIndex = tab, containerColor = TG.Surface, contentColor = TG.Ink,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = TG.Accent) },
        ) {
            fun label(base: String, n: Int) = if (filtering) "$base $n" else base
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (query.isNotBlank()) "Folders ${shownF.size}" else "Folders", maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(label("Routes", shownR.size), maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(label("Tracks", shownT.size), maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(label("Points", shownW.size), maxLines = 1, fontSize = 13.sp) })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text(label("Notes", shownN.size), maxLines = 1, fontSize = 13.sp) })
        }

        // Search box and sort.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search name, area, notes", color = TG.Muted, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TG.Muted) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TG.Muted) } },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, unfocusedBorderColor = TG.Line, cursorColor = TG.Accent),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = TG.Ink),
                modifier = Modifier.weight(1f),
            )
            if (tab != 0) {
                var sortOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.Filled.Sort, contentDescription = "Sort: ${sort.label}", tint = TG.Ink) }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        Text("Sort by", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        SavedSort.entries.filter { (tab in 1..2 || !it.lengthy) && (tab == 1 || !it.routesOnly) }.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text((if (opt == sort) "✓ " else "    ") + opt.label + if (opt == SavedSort.NEAREST && fix == null) " (needs GPS)" else "",
                                    color = if (opt == sort) TG.Accent else TG.Ink, fontWeight = if (opt == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                onClick = { sort = opt; savedPrefs.edit().putString("sort", opt.name).apply(); sortOpen = false },
                            )
                        }
                    }
                }
            }
        }
        // Areas in this tab, most used first: tap one to see only what's there.
        if (tab != 0) {
            val tabKeys = when (tab) {
                1 -> routes.map { "r:${it.id}" }
                2 -> tracks.map { "t:${it.id}" }
                3 -> waypoints.map { "w:${it.id}" }
                else -> notes.map { "n:${it.id}" }
            }
            val counts = tabKeys.flatMap { areas[it]?.names.orEmpty() }.groupingBy { it }.eachCount()
            val chips = (listOfNotNull(areaFilter) + counts.entries.sortedByDescending { it.value }.map { it.key }).distinct().take(12)
            if (chips.size > (if (areaFilter == null) 1 else 0)) {
                LazyRow(Modifier.fillMaxWidth().padding(top = 6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it }) { name ->
                        FilterChip(
                            selected = areaFilter == name,
                            onClick = { areaFilter = if (areaFilter == name) null else name },
                            label = { Text(name + (counts[name]?.let { " ($it)" } ?: ""), maxLines = 1, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = androidx.compose.ui.graphics.Color.White),
                        )
                    }
                }
            }
        }
        if (tab != 0 && filtering && sizeFor(tab, shownR.size, shownT.size, shownW.size, shownN.size) == 0 &&
            sizeFor(tab, routes.size, tracks.size, waypoints.size, notes.size) > 0) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Nothing here matches.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                TextButton(onClick = { query = ""; areaFilter = null }) { Text("Clear search and area", color = TG.Accent) }
            }
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
                items(shownF, key = { it.id }) { f ->
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
                items(shownR, key = { it.id }) { RouteItem(it, false) }
            }
            2 -> if (tracks.isEmpty()) {
                Empty("No tracks yet", "Tap Record on the map to record your hike. It keeps recording with the screen off.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) { items(shownT, key = { it.id }) { TrackItem(it, false) } }
            4 -> if (notes.isEmpty()) {
                Empty("No trail notes yet", "Tap Note on the map panel to note conditions where you are, or Trail note on any place's card. " +
                    "Notes can have photos and go in trip folders.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) {
                items(shownN, key = { it.id }) { NoteItem(it, false) }
            }
            else -> if (waypoints.isEmpty()) {
                Empty("No waypoints yet", "Tap Pin on the map to mark where you are, or long-press anywhere on the map.")
                Spacer(Modifier.weight(1f))
            } else LazyColumn(Modifier.weight(1f)) {
                items(shownW, key = { it.id }) { WaypointItem(it, false) }
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
    editingNote?.let { n ->
        NoteEditor(
            vm, n, isNew = false, folders = vm.folders.folders.collectAsStateWithLifecycle().value, folderId = vm.folders.members.value[FolderStore.noteKey(n.id)],
            onSave = { saved, f -> vm.trailNotes.upsert(saved); vm.folders.put(FolderStore.noteKey(saved.id), f); editingNote = null },
            onDelete = { vm.deleteNote(n.id); editingNote = null },
            onDismiss = { editingNote = null },
        )
    }

    adding?.let { fid ->
        AddToFolderDialog(
            routes = routes, tracks = tracks, waypoints = waypoints, notes = notes, members = vm.folders.members.value, folderId = fid,
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
    datesFor?.let { r ->
        TripDatesDialog(r, pace, onSave = { a, b -> vm.setTripDates(r.id, a, b); datesFor = null }, onDismiss = { datesFor = null })
    }
    notesFor?.let { r ->
        RouteNotesDialog(r.name, r.notes, onSave = { vm.setRouteNotes(r.id, it); notesFor = null }, onDismiss = { notesFor = null })
    }
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
    routes: List<RouteSummary>, tracks: List<TrackSummary>, waypoints: List<Waypoint>, notes: List<com.trailguidegps.app.data.TrailNote>,
    members: Map<String, String>, folderId: String, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit,
) {
    val choices = remember(routes, tracks, waypoints, notes, members) {
        routes.map { FolderStore.routeKey(it.id) to "Route: ${it.name}" } +
            tracks.map { FolderStore.trackKey(it.id) to "Track: ${it.name}" } +
            waypoints.sortedBy { it.name.lowercase() }.map { FolderStore.waypointKey(it.id) to "Waypoint: ${it.name}" } +
            notes.map { FolderStore.noteKey(it.id) to "Note: ${it.title}" }
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
private fun TrackRow(t: TrackSummary, folderName: String?, area: String?, onShow: () -> Unit, onToRoute: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(Color(0xFF8E3218), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            AreaLine(area)
            Text(
                "${dateFmt.format(Date(t.startedAt))} · ${Geo.formatMiles(t.stats.distanceM)} · +%,d ft · %s moving".format(
                    (t.stats.gainM * Geo.FT_PER_M).roundToInt(), Geo.formatDuration(t.stats.movingMs),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
        }
        RowMenu(listOf("Show on map" to onShow, "Make a route from it" to onToRoute, "Rename" to onRename, "Move to folder" to onMove,
            "Export GPX" to onExport, "Delete" to onDelete))
    }
}

@Composable
private fun RouteRow(
    r: RouteSummary, pace: Pace, onMap: Boolean, folderName: String?, area: String?,
    onShow: () -> Unit, onHide: () -> Unit, onEdit: () -> Unit, onToggleMap: (Boolean) -> Unit, onRename: () -> Unit, onColor: () -> Unit, onNotes: () -> Unit, onDates: () -> Unit, onOutBack: () -> Unit, onTripPlan: () -> Unit,
    onWater: () -> Unit, onReverse: () -> Unit,
    onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(routeColor(r.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            if (r.outAndBack) Text("Out & back", fontSize = 12.sp, color = TG.Muted, maxLines = 1)
            if (r.tripStart > 0) Text("Trip: " + com.trailguidegps.app.data.TripDates.short(r.tripStart, r.tripEnd), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = TG.Accent, maxLines = 1)
            AreaLine(area)
            Text(
                "%.1f mi · +%,d ft · −%,d ft · est %s".format(
                    r.distanceM / Geo.M_PER_MI, (r.gainM * Geo.FT_PER_M).roundToInt(), (r.lossM * Geo.FT_PER_M).roundToInt(),
                    Pace.format(pace.minutes(r.distanceM, r.gainM)),
                ),
                fontSize = 13.sp, color = TG.Muted,
            )
            val level = r.hardestLevel ?: com.trailguidegps.app.data.Difficulty.rating(r.distanceM, r.gainM).second
            Text(com.trailguidegps.app.data.Difficulty.LABELS[level] + if (r.days > 1) " · ${r.days} days, rated by the hardest" else "",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DifficultyColors[level])
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
            "Reverse direction" to onReverse,
            "Route notes" to onNotes,
            "Trip dates" to onDates,
            (if (r.outAndBack) "Make one way (drop the way back)" else "Make out & back (same way back)") to onOutBack,
            "Trip plan (PDF)" to onTripPlan,
            "Move to folder" to onMove,
            "Rename" to onRename, "Change color" to onColor, "Export GPX" to onExport, "Delete" to onDelete,
        ))
    }
}

@Composable
private fun WaypointRow(w: Waypoint, distanceM: Double?, folderName: String?, area: String?, onShow: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(Color(android.graphics.Color.parseColor(w.color)), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            folderName?.let { Text("In $it", fontSize = 12.sp, color = TG.Accent, maxLines = 1) }
            AreaLine(area)
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

/** How Saved lists are ordered. [lengthy] ones only make sense for routes and tracks. */
private enum class SavedSort(val label: String, val lengthy: Boolean = false, val routesOnly: Boolean = false) {
    NEWEST("Newest"), OLDEST("Oldest"), NAME("Name A–Z"), NEAREST("Nearest to you"),
    LONGEST("Longest", true), CLIMB("Most climbing", true), TRIP("Trip date", routesOnly = true),
}

/** Trip date order: upcoming trips soonest first, then past ones, then routes with no dates. */
private fun tripOrder(start: Long): Int = when {
    start <= 0 -> 2
    start >= System.currentTimeMillis() - 86_400_000L -> 0
    else -> 1
}
private fun tripKey(start: Long): Long = if (start >= System.currentTimeMillis() - 86_400_000L) start else -start

private fun sizeFor(tab: Int, r: Int, t: Int, w: Int, n: Int) = when (tab) { 1 -> r; 2 -> t; 3 -> w; else -> n }

/** Where an item is: "Three Sisters Wilderness · Willamette National Forest · 9 mi W of Sisters". */
@Composable
private fun AreaLine(area: String?) {
    if (area.isNullOrBlank()) return
    Text(area, fontSize = 12.sp, color = TG.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
}
