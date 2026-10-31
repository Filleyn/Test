package com.trailguidegps.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.PmTilesWriter
import com.trailguidegps.app.data.SatArea
import com.trailguidegps.app.data.SatelliteStore
import com.trailguidegps.app.geo.Geo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detail levels: the zoom saved down to, and its label. */
val DETAIL_CHOICES = listOf(16 to "Good · 6 ft", 17 to "Sharp · 3 ft", 18 to "Sharpest · 1.4 ft")

fun detailLabel(z: Int) = when {
    z >= 18 -> "sharpest detail (Oregon 2024)"
    z == 17 -> "sharp detail (Oregon 2024)"
    z == 16 -> "good detail (USGS)"
    else -> "standard detail (USGS)"
}

private fun mb(bytes: Long) = if (bytes >= 1e9) "%.1f GB".format(bytes / 1e9) else "%,d MB".format((bytes / 1e6).toLong().coerceAtLeast(1))

/** Bottom panel while framing an area to save: name, detail level, size estimate. */
@Composable
fun SatSelectPanel(
    replacing: SatArea?,
    bounds: DoubleArray?,
    suggestName: (Double, Double) -> String?,
    freeBytes: Long,
    onCancel: () -> Unit,
    onSave: (String, DoubleArray, Int) -> Unit,
) {
    var name by remember(replacing) { mutableStateOf(replacing?.name ?: "") }
    var named by remember(replacing) { mutableStateOf(replacing != null) }
    var detail by remember(replacing) { mutableIntStateOf(replacing?.maxZoom ?: 17) }
    // Suggest a name from the nearest town until you type one.
    LaunchedEffect(bounds?.let { (it[1] + it[3]) / 2 * 100 }?.toInt(), bounds?.let { (it[0] + it[2]) / 2 * 100 }?.toInt()) {
        if (!named && bounds != null) name = suggestName((bounds[1] + bounds[3]) / 2, (bounds[0] + bounds[2]) / 2) ?: name
    }
    val tiles = bounds?.let { PmTilesWriter.countFor(it[0], it[1], it[2], it[3], SatelliteStore.MIN_ZOOM, detail) } ?: 0
    val bytes = bounds?.let { SatelliteStore.estimateBytes(it[0], it[1], it[2], it[3], detail) } ?: 0
    val tooBig = tiles > SatelliteStore.MAX_TILES
    val noRoom = bytes * 13 / 10 > freeBytes

    Column {
        OutlinedTextField(
            value = name, onValueChange = { name = it; named = true }, singleLine = true,
            label = { Text("Area name") }, modifier = Modifier.fillMaxWidth(),
        )
        Text("Detail", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DETAIL_CHOICES.forEach { (z, label) ->
                FilterChip(
                    selected = detail == z, onClick = { detail = z }, label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White),
                )
            }
        }
        Text(
            when (detail) {
                16 -> "USGS imagery, mixed dates. Quickest to download."
                17 -> "Oregon 2024 imagery (flown summer 2024). Trails and campsites are easy to make out."
                else -> "Oregon 2024 imagery at full detail: individual trees and rocks. About 4 times the size of Sharp."
            },
            fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 2.dp),
        )
        if (bounds != null) {
            val midLat = (bounds[1] + bounds[3]) / 2
            val wMi = Geo.distanceM(midLat, bounds[0], midLat, bounds[2]) / Geo.M_PER_MI
            val hMi = Geo.distanceM(bounds[1], bounds[0], bounds[3], bounds[0]) / Geo.M_PER_MI
            Text(
                "%.1f × %.1f mi · %,d tiles · about %s".format(wMi, hMi, tiles, mb(bytes)),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.padding(top = 8.dp),
            )
            when {
                tooBig -> Text("Too many tiles to save in one go (limit ${"%,d".format(SatelliteStore.MAX_TILES)}). Zoom in, pick less detail, or save it as two areas.",
                    fontSize = 13.sp, color = TG.Danger)
                noRoom -> Text("Not enough free space on the phone (${mb(freeBytes)} free).", fontSize = 13.sp, color = TG.Danger)
                else -> Text("Downloads over your connection; Wi-Fi is best. Tiles you've already saved aren't downloaded again.",
                    fontSize = 12.sp, color = TG.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                Text("Cancel", color = TG.Ink)
            }
            Button(
                onClick = { bounds?.let { onSave(name.trim(), it, detail) } },
                enabled = bounds != null && !tooBig && !noRoom && tiles > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(if (replacing != null) "Save new area" else "Save area", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Saved satellite areas: progress, rename, change boundary, delete. */
@Composable
fun SatelliteScreen(vm: AppViewModel, onClose: () -> Unit) {
    val areas by vm.satellite.areas.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SatArea?>(null) }
    var deleting by remember { mutableStateOf<SatArea?>(null) }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(TG.Surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to map", tint = TG.Ink) }
            Text("Satellite areas", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.startSatSelect(); onClose() }) { Text("Add area", color = TG.Accent, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            "Satellite imagery shows online anywhere when the Satellite layer is on. Areas saved here also show with no signal. " +
                "Imagery: USGS The National Map, and the Oregon Statewide Imagery Program's 1-ft 2024 imagery.",
            fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = TG.Line, modifier = Modifier.padding(top = 8.dp))
        if (areas.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No areas saved yet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                Spacer(Modifier.height(6.dp))
                Text("Tap Add area, then frame the place you're going. A 5 × 5 mile area is about 13 MB at Good detail, 31 MB at Sharp and 120 MB at Sharpest.",
                    fontSize = 14.sp, color = TG.Muted, lineHeight = 20.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(areas, key = { it.id }) { a ->
                    SatRow(
                        a,
                        onShow = { vm.focusOn(listOf(a.north to a.west, a.south to a.east)); onClose() },
                        onRename = { renaming = a },
                        onChange = { vm.startSatSelect(a); onClose() },
                        onPause = { vm.satellite.pause(a.id) },
                        onResume = { vm.resumeSatArea(a.id) },
                        onDelete = { deleting = a },
                    )
                    HorizontalDivider(color = TG.Line)
                }
            }
            val total = areas.filter { it.ready }.sumOf { it.bytes }
            val free = androidx.compose.ui.platform.LocalContext.current.filesDir.usableSpace
            Text("Using ${mb(total)} · ${mb(free)} free on the phone",
                fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(16.dp))
        }
    }

    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename area") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.satellite.rename(a.id, name.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${a.name}\"?") },
            text = { Text("Frees ${mb(a.bytes)}. You can save it again later while online.") },
            confirmButton = { TextButton(onClick = { vm.satellite.delete(a.id); deleting = null }) { Text("Delete", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SatRow(
    a: SatArea, onShow: () -> Unit, onRename: () -> Unit, onChange: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit,
) {
    val date = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(a.createdAt))
    Row(Modifier.fillMaxWidth().clickable(enabled = a.ready, onClick = onShow).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(if (a.ready) Color(0xFF1F4FB4) else TG.Line, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            val detail = detailLabel(a.maxZoom)
            val status = when (a.status) {
                SatArea.STATUS_READY -> "${mb(a.bytes)} · $detail · saved $date"
                SatArea.STATUS_DOWNLOADING -> "Downloading ${if (a.total > 0) a.done * 100 / a.total else 0}% (${a.done} of ${a.total} tiles)"
                SatArea.STATUS_QUEUED -> "Waiting to download"
                SatArea.STATUS_PAUSED -> "Paused at ${if (a.total > 0) a.done * 100 / a.total else 0}%"
                else -> "Couldn't save"
            }
            Text(status, fontSize = 13.sp, color = TG.Muted)
            a.note?.let { Text(it, fontSize = 12.sp, color = if (a.ready) TG.Muted else TG.Danger) }
            if (a.replaces != null && !a.ready) Text("New boundary; the old one stays until this finishes", fontSize = 12.sp, color = TG.Muted)
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TG.Muted) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                buildList {
                    if (a.ready) add("Show on map" to onShow)
                    if (a.status == SatArea.STATUS_DOWNLOADING || a.status == SatArea.STATUS_QUEUED) add("Pause" to onPause)
                    if (a.status == SatArea.STATUS_PAUSED || a.status == SatArea.STATUS_FAILED) add("Resume" to onResume)
                    add("Rename" to onRename)
                    if (a.ready) add("Change area" to onChange)
                    add("Delete" to onDelete)
                }.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (label == "Delete") TG.Danger else TG.Ink) },
                        onClick = { open = false; action() },
                    )
                }
            }
        }
    }
}
