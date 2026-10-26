package com.trailguidegps.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()

        setContent {
            TrailGuideTheme {
                var hasPermission by rememberSaveable { mutableStateOf(locationGranted()) }
                var showEmergency by rememberSaveable { mutableStateOf(false) }
                var showSaved by rememberSaveable { mutableStateOf(false) }
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
                    vm.startRecording()
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
                val startRecording = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.startRecording()
                    }
                }

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
                                onDelete = { vm.waypoints.delete(w.id); editingFromSaved = null },
                                onDismiss = { editingFromSaved = null },
                            )
                        }
                    }
                    if (showEmergency) EmergencyScreen(vm, onClose = { showEmergency = false })
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
