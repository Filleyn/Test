package com.trailguidegps.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.DownloadState

/** First run: fetch the offline map files once, over Wi-Fi or data. */
@Composable
fun SetupScreen(vm: AppViewModel) {
    val state by vm.download.collectAsStateWithLifecycle()
    val release by vm.release.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.checkRelease() }

    // Keep the screen on while a large download runs.
    val downloading = state is DownloadState.Downloading || state is DownloadState.Checking
    DisposableEffect(downloading) {
        val window = (context as? Activity)?.window
        if (downloading) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val catalogError by vm.catalogError.collectAsStateWithLifecycle()
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    // The state you're in (from GPS) is offered first; you can pick any other.
    var picked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val here = fix?.let { vm.regionAt(it.lat, it.lon) }
    val choice = picked ?: here ?: catalog.firstOrNull { it.region == com.trailguidegps.app.BuildConfig.MAPS_REGION }?.region ?: catalog.firstOrNull()?.region
    val info = catalog.firstOrNull { it.region == choice }
    val size = info?.release?.totalBytes?.let { formatSize(it) } ?: release?.totalBytes?.let { formatSize(it) }
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(TG.Surface).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TrailGuide GPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a state and download its maps once. After that the app works with no signal: topo map, trails, " +
                "water sources and your GPS position. You can add more states later.",
            fontSize = 16.sp, color = TG.Muted, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        // The state picker: a drop-down list.
        Text("State", fontSize = 13.sp, color = TG.Muted)
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.OutlinedButton(onClick = { menu = true }, enabled = catalog.isNotEmpty(),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(info?.name ?: if (catalogError != null) "Can't reach the map list" else "Finding states…", fontSize = 16.sp, color = TG.Ink,
                    modifier = Modifier.weight(1f))
                if (choice != null && choice == here) Text("you're here", fontSize = 12.sp, color = TG.Accent)
                Text("  ▾", fontSize = 16.sp, color = TG.Ink)
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                catalog.forEach { r ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(r.name + "  ·  " + formatSize(r.release.totalBytes) + if (r.region == here) "  · you're here" else "") },
                        onClick = { picked = r.region; menu = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            (size?.let { "Download size: $it. " } ?: "") + "Use Wi-Fi and keep the app open; an interrupted download resumes.",
            fontSize = 14.sp, color = TG.Muted, lineHeight = 20.sp,
        )
        catalogError?.let {
            Text("$it Connect to the internet to see the states.", fontSize = 13.sp, color = TG.Danger, modifier = Modifier.padding(top = 6.dp))
            androidx.compose.material3.TextButton(onClick = { vm.refreshCatalog() }) { Text("Try again", color = TG.Accent) }
        }
        Spacer(Modifier.height(20.dp))
        when (val s = state) {
            is DownloadState.Downloading -> {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                Text("Downloading ${s.file}…", fontSize = 15.sp, color = TG.Ink)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = TG.Accent)
                Spacer(Modifier.height(6.dp))
                Text("${formatSize(s.done)} of ${formatSize(s.total)}", fontSize = 13.sp, color = TG.Muted)
            }
            DownloadState.Checking -> Text("Finding the latest map files…", fontSize = 15.sp, color = TG.Ink)
            else -> {
                if (s is DownloadState.Error) {
                    Text(s.message, fontSize = 15.sp, color = TG.Danger)
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = { choice?.let { vm.downloadRegion(it) } ?: vm.downloadMaps(force = true) },
                    enabled = choice != null || catalog.isEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        when {
                            s is DownloadState.Error -> "Resume download"
                            info != null -> "Download ${info.name} maps" + (size?.let { " ($it)" } ?: "")
                            else -> "Download maps"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (catalog.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = { vm.useOnlineMap() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("Use the online map for now", color = TG.Accent, fontSize = 15.sp)
                    }
                    Text("Shows every state's map while you have signal. Search, routes and offline use need a downloaded state.",
                        fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
