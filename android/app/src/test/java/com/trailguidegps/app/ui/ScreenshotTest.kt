package com.trailguidegps.app.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.location.Fix
import com.trailguidegps.app.map.MapStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the main panels, drawn on the build machine so changes can be checked
 * without a phone. Run with `gradle recordRoborazziDebug`; images land in app/build/outputs/roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    @Test
    fun layersScreen() {
        compose.setContent {
            val on = remember {
                mutableStateMapOf<String, Boolean>().apply {
                    LAYER_PRESETS.first().second.forEach { put(it, true) }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White).padding(20.dp)) {
                LayersContent(
                    base = MapStyle.BASE_TOPO, onBase = {}, thumbs = emptyMap(), online = false, satelliteAreaCount = 1,
                    layerOn = on, onToggle = { g, v -> on[g] = v }, onPreset = {},
                    sectionOpen = { it == "terrain" || it == "hazards" }, onSection = { _, _ -> }, firesFooter = {},
                )
            }
        }
        compose.onRoot().captureRoboImage(out("layers"))
    }

    @Test
    fun routeActions() {
        compose.setContent {
            Box(Modifier.background(Color.White).padding(16.dp)) {
                ActionGrid(listOf(
                    Action(Icons.Filled.WaterDrop, "Water") {}, Action(Icons.Filled.NightsStay, "Days") {},
                    Action(Icons.Filled.WbSunny, "Weather") {}, Action(Icons.Filled.SwapHoriz, "Reverse") {},
                    Action(Icons.Filled.Description, "Trip plan") {},
                ), columns = 5)
            }
        }
        compose.onRoot().captureRoboImage(out("route-actions"))
    }

    private fun panel(name: String, selected: AppViewModel.SelectedPlace?, expanded: Boolean) {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        val loc = Location("gps").apply { latitude = 44.1; longitude = -121.7; altitude = 1500.0; accuracy = 5f; time = System.currentTimeMillis() }
        compose.setContent {
            BottomPanelForTests(vm, Fix(loc, 9, 14), selected, expanded)
        }
        compose.onRoot().captureRoboImage(out(name))
    }

    private val spring = AppViewModel.SelectedPlace(
        "Lost Creek Spring", "Spring · may be seasonal · 6.2 mi W of Sisters", 44.12, -121.74, osmId = "n1", isWater = true,
        links = listOf("Deschutes NF alerts" to "https://example.org", "Search the web" to "https://example.org"),
    )

    @Test fun mainPanel() = panel("panel-main", null, false)
    @Test fun placeCard() = panel("panel-place", spring, false)
    @Test fun placeCardExpanded() = panel("panel-place-expanded", spring, true)
}
