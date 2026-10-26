package com.trailguidegps.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.ui.EmergencyScreen
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
                val mapsReady by vm.mapsReady.collectAsStateWithLifecycle()

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    hasPermission = result.values.any { it } || locationGranted()
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

                // The map stays alive under the emergency screen so it doesn't reload.
                Box(Modifier.fillMaxSize()) {
                    if (mapsReady) {
                        MapScreen(
                            vm = vm,
                            hasLocationPermission = hasPermission,
                            onRequestPermission = requestPermission,
                            onEmergency = { showEmergency = true },
                        )
                    } else {
                        SetupScreen(vm)
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
        // Phase 3 adds a foreground service so track recording keeps GPS on with the screen off.
        vm.tracker.stop()
    }

    private fun locationGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
