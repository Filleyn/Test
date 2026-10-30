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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.trailguidegps.app.BuildConfig
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.Settings

/** All the app's settings in one place. */
@Composable
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val s by vm.settings.value.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pace by vm.pace.collectAsStateWithLifecycle()
    var showPace by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to map", tint = TG.Ink) }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Section("Following a route")
            ToggleRow(
                "Off-route alerts",
                "Buzz and notify when you stray from a route you're following, even with the screen off.",
                s.offRouteAlerts,
            ) { on -> vm.settings.update { it.copy(offRouteAlerts = on) } }
            if (s.offRouteAlerts) {
                Text("Alert when more than", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Settings.OFF_ROUTE_CHOICES.forEach { ft ->
                        FilterChip(
                            selected = s.offRouteFt == ft, onClick = { vm.settings.update { it.copy(offRouteFt = ft) } },
                            label = { Text("%,d ft".format(ft)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White),
                        )
                    }
                }
                Text("off the route. Smaller numbers catch wrong turns sooner but can go off from GPS drift in canyons and thick forest.",
                    fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
            }
            ToggleRow("Keep the screen on", "While following a route. Uses more battery.", s.keepScreenOn) { on ->
                vm.settings.update { it.copy(keepScreenOn = on) }
            }

            Section("Battery")
            ToggleRow("Battery saver", "GPS every 15 seconds instead of 5 while recording or following. Tracks are a little less detailed.",
                s.batterySaver) { on -> vm.settings.update { it.copy(batterySaver = on) } }
            ToggleRow("Low battery warning", "A notification at 20% and 10% while recording or following.", s.lowBatteryWarning) { on ->
                vm.settings.update { it.copy(lowBatteryWarning = on) }
            }
            ToggleRow("Switch to battery saver at 20%", "Only while recording or following, and only until you stop.", s.autoSaverAtLow) { on ->
                vm.settings.update { it.copy(autoSaverAtLow = on) }
            }

            Section("Time estimates")
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hiking pace", fontSize = 16.sp, color = TG.Ink)
                    Text("%.1f mph · %d min per 1,000 ft of climb · %d min breaks per hour".format(pace.mph, pace.climbMinPer1000Ft, pace.breakMinPerHour),
                        fontSize = 12.sp, color = TG.Muted)
                }
                TextButton(onClick = { showPace = true }) { Text("Change", color = TG.Accent) }
            }
            val suggestion by vm.paceSuggestion.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.suggestPace() }
            suggestion?.let { sg ->
                val p = sg.pace
                if (p == null) {
                    Text(
                        if (sg.hikes < 3) "Record ${3 - sg.hikes} more hike${if (3 - sg.hikes == 1) "" else "s"} of a mile or more and the app can suggest a pace from how you actually hike."
                        else "Your recorded hikes didn't give a believable pace yet. A few more will help.",
                        fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp),
                    )
                } else if (p != pace) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F4EF), RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Text("From your ${sg.hikes} recorded hikes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                        Text("%.1f mph · %d min per 1,000 ft · %d min breaks per hour".format(p.mph, p.climbMinPer1000Ft, p.breakMinPerHour),
                            fontSize = 14.sp, color = TG.Ink)
                        Text("Example: 5 mi with 1,000 ft of climb would take ${Pace.format(p.minutes(5 * com.trailguidegps.app.geo.Geo.M_PER_MI, 1000 / com.trailguidegps.app.geo.Geo.FT_PER_M))} " +
                            "instead of ${Pace.format(pace.minutes(5 * com.trailguidegps.app.geo.Geo.M_PER_MI, 1000 / com.trailguidegps.app.geo.Geo.FT_PER_M))}.",
                            fontSize = 12.sp, color = TG.Muted)
                        TextButton(onClick = { vm.setPace(p) }) { Text("Use this pace", color = TG.Accent, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    Text("Your pace matches your ${sg.hikes} recorded hikes.", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }

            Section("Backup")
            val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
            val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri -> if (uri != null) vm.backup(uri) }
            val openBackup = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri -> if (uri != null) vm.restore(uri) }
            Text(
                "Saves your routes, tracks, waypoints, water notes and settings to one file. Choose where: this phone, " +
                    "Google Drive, OneDrive or any storage app you have. Maps and satellite areas download again instead.",
                fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Last backup: " + (lastBackup?.let { java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(java.util.Date(it)) } ?: "never"),
                fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                androidx.compose.material3.Button(
                    onClick = {
                        vm.shareBackup { uri ->
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, vm.backupFileName().removeSuffix(".zip"))
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(send, "Back up to"))
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Back up to…") }
                androidx.compose.material3.OutlinedButton(
                    onClick = { saveBackup.launch(vm.backupFileName()) },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Save to a folder", color = TG.Ink) }
            }
            Text("Back up to… lists OneDrive, Google Drive, email and other apps. Save to a folder puts it on this phone or in Drive's folders.",
                fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
            androidx.compose.material3.OutlinedButton(
                onClick = { openBackup.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
            ) { Text("Restore from a backup", color = TG.Ink) }
            Text("Restoring adds to what's on the phone; it doesn't delete anything. To restore from OneDrive, pick it under Browse in the file picker, " +
                "or save the file to the phone from the OneDrive app first.", fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)

            Section("Maps")
            ToggleRow("Check for map updates when the app opens", "Only when you have a connection. You choose when to download.",
                s.checkUpdatesOnLaunch) { on -> vm.settings.update { it.copy(checkUpdatesOnLaunch = on) } }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Installed maps", fontSize = 16.sp, color = TG.Ink)
                    Text("${vm.store.title()} · built ${vm.store.builtDate() ?: "unknown"}", fontSize = 12.sp, color = TG.Muted)
                }
                val dl by vm.download.collectAsStateWithLifecycle()
                TextButton(
                    onClick = { vm.downloadMaps() },
                    enabled = dl !is com.trailguidegps.app.DownloadState.Checking && dl !is com.trailguidegps.app.DownloadState.Downloading,
                ) { Text("Update now", color = TG.Accent) }
            }
            val dl by vm.download.collectAsStateWithLifecycle()
            when (val d = dl) {
                com.trailguidegps.app.DownloadState.Checking -> "Checking for newer map files…"
                is com.trailguidegps.app.DownloadState.Downloading -> "Downloading ${d.file}…"
                is com.trailguidegps.app.DownloadState.UpToDate -> "Map files are up to date."
                is com.trailguidegps.app.DownloadState.Error -> d.message
                else -> null
            }?.let { Text(it, fontSize = 12.sp, color = TG.Muted) }

            Section("About")
            Text("TrailGuide GPS ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, color = TG.Ink)
            Text("Maps © OpenStreetMap contributors. Elevation and public land from USGS.", fontSize = 12.sp, color = TG.Muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 24.dp))
        }
    }
    if (showPace) PaceDialog(pace, onSave = { vm.setPace(it); showPace = false }, onDismiss = { showPace = false })
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Accent)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = TG.Line)
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, color = TG.Ink)
            Text(detail, fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
        }
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = TG.Accent))
    }
}
