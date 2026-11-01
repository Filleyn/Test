package com.trailguidegps.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.ui.EmergencyScreen
import com.trailguidegps.app.ui.SavedScreen
import com.trailguidegps.app.ui.SearchScreen
import com.trailguidegps.app.ui.SettingsScreen
import com.trailguidegps.app.ui.WaypointEditor
import com.trailguidegps.app.ui.MapScreen
import com.trailguidegps.app.ui.SetupScreen
import com.trailguidegps.app.ui.TrailGuideTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Dark clock and status icons over the light map (the emergency screen switches them to light).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        // The look (dark, sunlight) is known before the first screen draws, so it doesn't flash.
        com.trailguidegps.app.data.SettingsStore.get(this).value.value.let { st ->
            com.trailguidegps.app.ui.Look.sun = st.sunlight
            com.trailguidegps.app.ui.Look.dark = st.look == "dark" ||
                (st.look == "system" && (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
        setContent {
            val settings by vm.settings.value.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            LaunchedEffect(settings.look, settings.sunlight, systemDark) {
                val dark = settings.look == "dark" || (settings.look == "system" && systemDark)
                com.trailguidegps.app.ui.Look.dark = dark
                com.trailguidegps.app.ui.Look.sun = settings.sunlight
                val clear = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
                )
            }
            TrailGuideTheme {
                var hasPermission by rememberSaveable { mutableStateOf(locationGranted()) }
                var showEmergency by rememberSaveable { mutableStateOf(false) }
                var showSaved by rememberSaveable { mutableStateOf(false) }
                var showSearch by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showSatellite by rememberSaveable { mutableStateOf(false) }
                // What to do once Android has asked about notifications (recording or following).
                var afterNotifyAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
                var editingFromSaved by remember { mutableStateOf<Waypoint?>(null) }
                val mapsReady by vm.mapsReady.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()

                LaunchedEffect(message) {
                    message?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        vm.messageShown()
                    }
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    hasPermission = result.values.any { it } || locationGranted()
                }
                // Recording shows a notification; Android 13+ asks before allowing it.
                // Recording starts either way; without permission the notification is hidden.
                val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    afterNotifyAsk?.invoke()
                    afterNotifyAsk = null
                }
                fun withNotifications(action: () -> Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        afterNotifyAsk = action
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else action()
                }
                LaunchedEffect(hasPermission) {
                    if (hasPermission) vm.tracker.start()
                }
                LaunchedEffect(Unit) {
                    if (!hasPermission) {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                val requestPermission = {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                val startRecording = { withNotifications { vm.startRecording() } }

                // The map stays alive under the other screens so it doesn't reload.
                Box(Modifier.fillMaxSize()) {
                    if (mapsReady) {
                        MapScreen(
                            vm = vm,
                            hasLocationPermission = hasPermission,
                            onRequestPermission = requestPermission,
                            onEmergency = { showEmergency = true },
                            onStartRecording = startRecording,
                            onOpenSaved = { showSaved = true },
                            onOpenSearch = { showSearch = true },
                            onOpenSettings = { showSettings = true },
                            onStartFollowing = { withNotifications { vm.startFollowing() } },
                            onOpenSatellite = { showSatellite = true },
                        )
                    } else {
                        SetupScreen(vm)
                    }
                    if (showSaved) {
                        SavedScreen(vm, onClose = { showSaved = false }, onEditWaypoint = { editingFromSaved = it })
                        editingFromSaved?.let { w ->
                            WaypointEditor(
                                waypoint = w, isNew = false,
                                onSave = { vm.waypoints.upsert(it); editingFromSaved = null },
                                onDelete = { vm.deleteWaypoint(w.id); editingFromSaved = null },
                                onDismiss = { editingFromSaved = null },
                                folders = vm.folders.folders.collectAsStateWithLifecycle().value,
                                folderId = vm.folders.members.value[com.trailguidegps.app.data.FolderStore.waypointKey(w.id)],
                                onCreateFolder = { vm.folders.create(it) },
                                onFolder = { f -> vm.folders.put(com.trailguidegps.app.data.FolderStore.waypointKey(w.id), f) },
                                vm = vm,
                            )
                        }
                    }
                    if (showSearch) SearchScreen(vm, onClose = { showSearch = false })
                    if (showSettings) SettingsScreen(vm, onClose = { showSettings = false })
                    if (showSatellite) com.trailguidegps.app.ui.SatelliteScreen(vm, onClose = { showSatellite = false })
                    if (showEmergency) EmergencyScreen(vm, onClose = { showEmergency = false })
                    // Quick tips the first time the maps are ready.
                    if (mapsReady && !settings.introSeen && !showEmergency) {
                        com.trailguidegps.app.ui.IntroScreen(onDone = { vm.settings.update { it.copy(introSeen = true) } })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (locationGranted()) vm.tracker.start()
    }

    override fun onStop() {
        super.onStop()
        // The map's GPS reader pauses in the background to save battery.
        // A track being recorded keeps going through its own foreground service.
        vm.tracker.stop()
    }

    private fun locationGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
