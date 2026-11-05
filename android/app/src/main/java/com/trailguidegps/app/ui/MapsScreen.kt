package com.trailguidegps.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.trailguidegps.app.DownloadState
import com.trailguidegps.app.data.MapStore

private fun gb(bytes: Long) = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

/**
 * Maps for each state: download, update or delete them. Downloaded states work with no
 * signal; the others still show on the map while you're online.
 */
@Composable
fun MapsScreen(vm: AppViewModel, onClose: () -> Unit, onShow: (DoubleArray) -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val error by vm.catalogError.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { vm.refreshCatalog() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val free = remember { MapStore.root(context).apply { mkdirs() }.usableSpace }
    MapsContent(
        catalog, installed.toSet(), updates.toSet(), dl, error, free,
        onDownload = vm::downloadRegion, onDelete = vm::deleteRegion, onRetry = vm::refreshCatalog,
        onClose = onClose, onShow = onShow,
    )
}

/** The list itself, without the view model (also used by the screenshot tests). */
@Composable
fun MapsContent(
    catalog: List<AppViewModel.RegionInfo>, installed: Set<String>, updates: Set<String>,
    dl: DownloadState, error: String?, free: Long,
    onDownload: (String) -> Unit, onDelete: (String) -> Unit, onRetry: () -> Unit,
    onClose: () -> Unit, onShow: (DoubleArray) -> Unit,
) {
    var deleting by remember { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(TG.Surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to map", tint = TG.Ink) }
            Text("Maps for each state", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        }
        Text(
            "Downloaded states work with no signal, including search, routes and water. Other states still show on the map while you're online. " +
                "${gb(free)} free on this phone.",
            fontSize = 13.sp, color = TG.Muted, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        (dl as? DownloadState.Downloading)?.let { d ->
            Column(Modifier.padding(16.dp)) {
                Text("Downloading ${d.region?.let { MapStore.displayName(it) } ?: "maps"}: ${gb(d.done)} of ${gb(d.total)}", fontSize = 14.sp, color = TG.Ink)
                LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, color = TG.Accent,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text("Keep the app open; an interrupted download resumes.", fontSize = 12.sp, color = TG.Muted)
            }
        }
        (dl as? DownloadState.Error)?.let { Text(it.message, fontSize = 13.sp, color = TG.Danger, modifier = Modifier.padding(16.dp)) }
        if (catalog.isEmpty()) {
            Text(error?.let { "$it Connect to the internet to see which states have maps." } ?: "Finding which states have maps…",
                fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(16.dp))
            if (error != null) TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again", color = TG.Accent) }
        }
        // A search box once the list gets long.
        if (catalog.size > 8) {
            androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find a state") },
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
        }
        val busy = dl is DownloadState.Downloading || dl is DownloadState.Checking
        // Downloaded states first, then the rest grouped by area (or just the matches while searching).
        val q = query.trim().lowercase()
        val shown = catalog.filter { q.isEmpty() || it.name.lowercase().contains(q) || com.trailguidegps.app.data.MapAreas.of(it.region).lowercase().contains(q) }
        val sections: List<Pair<String?, List<AppViewModel.RegionInfo>>> = run {
            val mine = shown.filter { it.region in installed }.sortedBy { it.name }
            val rest = shown.filter { it.region !in installed }
            val grouped = if (catalog.size > 8) {
                rest.groupBy { com.trailguidegps.app.data.MapAreas.of(it.region) }.toList()
                    .sortedBy { com.trailguidegps.app.data.MapAreas.ORDER.indexOf(it.first) }
                    .map { (area, list) -> area to list.sortedBy { it.name } }
            } else listOf((if (mine.isNotEmpty()) "Not downloaded" else null) to rest.sortedBy { it.name })
            listOfNotNull(if (mine.isNotEmpty()) "Downloaded" to mine else null) + grouped.filter { it.second.isNotEmpty() }
        }
        if (q.isNotEmpty() && shown.isEmpty()) {
            Text("No state matches \"$query\".", fontSize = 14.sp, color = TG.Muted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
          sections.forEach { (title, list) ->
            if (title != null) item(key = "h-$title") {
                Text(
                    title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            items(list, key = { it.region }) { r ->
                val have = r.region in installed
                val update = r.region in updates
                HorizontalDivider(color = TG.Line)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                            Text(
                                listOfNotNull(
                                    when { update -> "Downloaded · update available"; have -> "Downloaded"; else -> "Online only" },
                                    gb(r.release.totalBytes), r.built?.let { "built $it" },
                                ).joinToString(" · "),
                                fontSize = 13.sp, color = if (have) TG.Ok else TG.Muted,
                            )
                        }
                        r.bbox?.let { b -> TextButton(onClick = { onShow(b) }) { Text("Show", color = TG.Accent) } }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            !have -> Button(
                                onClick = { onDownload(r.region) }, enabled = !busy, shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                            ) { Text("Download") }
                            update -> Button(
                                onClick = { onDownload(r.region) }, enabled = !busy, shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                            ) { Text("Update") }
                        }
                        if (have) OutlinedButton(onClick = { deleting = r.region }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                            Text("Delete", color = TG.Danger)
                        }
                    }
                }
            }
          }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    deleting?.let { region ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the ${MapStore.displayName(region)} maps?") },
            text = { Text("They'll still show while you're online, and you can download them again. Your routes, tracks and waypoints aren't touched.") },
            confirmButton = { TextButton(onClick = { onDelete(region); deleting = null }) { Text("Delete", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
