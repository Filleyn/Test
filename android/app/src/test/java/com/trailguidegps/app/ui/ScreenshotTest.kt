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
                    customPresets = listOf("Elk season" to setOf("trails", "roadaccess")), onCustomPresets = {},
                    sectionOpen = { it == "trails" || it == "hazards" }, onSection = { _, _ -> }, firesFooter = {},
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

    @Test
    fun routePlanner() {
        val prof = (0..40).map { i -> i * 140.0 to 1440.0 + 360.0 * (i / 40.0) + 15 * kotlin.math.sin(i / 3.0) }
        val pts = listOf(com.trailguidegps.app.data.RoutePoint(44.1, -121.7, true), com.trailguidegps.app.data.RoutePoint(44.12, -121.74, true))
        val plan = AppViewModel.PlanState(
            active = true, points = pts,
            legs = listOf(com.trailguidegps.app.data.RouteLeg(listOf(doubleArrayOf(44.1, -121.7), doubleArrayOf(44.12, -121.74)), 5600.0,
                listOf("Obsidian Trail #3528"), true, doubleArrayOf(5600.0, 0.0, 0.0))),
            stats = com.trailguidegps.app.data.RouteStats(5600.0, 354.0, 24.0, prof),
        )
        compose.setContent {
            Box(Modifier.background(Color.White).padding(16.dp)) {
                RoutePlannerPanel(plan = plan, pace = Pace(), routingReady = true, onAddWaypoint = {}, onSave = {}, onEditPace = {})
            }
        }
        compose.onRoot().captureRoboImage(out("route-planner"))
    }

    @Test
    fun peaksScreen() {
        fun seen(n: String, ft: Int, mi: Double, brg: Double, clear: Double = 200.0) =
            com.trailguidegps.app.data.PeakView.Seen(n, 44.0, -121.0, ft / 3.28084, mi * 1609.34, brg, clear, 0.5)
        val r = com.trailguidegps.app.data.PeakView.Result(1850.0, listOf(
            seen("Mount Jefferson", 10497, 24.0, 2.0), seen("Three Fingered Jack", 7841, 9.5, 12.0), seen("Mount Washington", 7794, 4.2, 20.0),
            seen("Black Crater", 7251, 6.0, 70.0, 8.0), seen("Broken Top", 9175, 11.0, 150.0), seen("South Sister", 10358, 8.0, 175.0),
            seen("Middle Sister", 10047, 6.2, 182.0), seen("North Sister", 10085, 5.1, 190.0), seen("Mount Bachelor", 9065, 19.0, 160.0),
            seen("Diamond Peak", 8744, 42.0, 205.0, -10.0), seen("Mount Hood", 11249, 58.0, 8.0),
        ).sortedBy { it.bearingDeg }, 37, 120)
        compose.setContent {
            ScreenFrame("Peaks in view", "From Lost Creek Spring · 6,070 ft", onBack = {}) { PeaksContent(r, true, 178f) {} }
        }
        compose.onRoot().captureRoboImage(out("screen-peaks"))
    }

    @Test
    fun weatherScreen() {
        fun per(n: String, day: Boolean, t: Int, short: String, rain: Int?) =
            com.trailguidegps.app.data.Weather.Period(n, t, day, "5 to 10 mph NW", short, "$short, with a high near $t. Northwest wind 5 to 10 mph.", rain)
        val f = com.trailguidegps.app.data.Weather.Forecast(
            "Start", 44.1, -121.7, 1850.0, 1250.0, null, System.currentTimeMillis(), listOf(
                per("Today", true, 68, "Sunny", null), per("Tonight", false, 38, "Clear", null),
                per("Sunday", true, 64, "Partly Sunny", 10), per("Sunday Night", false, 36, "Mostly Cloudy", 20),
                per("Monday", true, 52, "Rain Showers Likely", 70), per("Monday Night", false, 31, "Rain And Snow Showers", 60),
                per("Tuesday", true, 49, "Chance Thunderstorms", 40), per("Tuesday Night", false, 30, "Patchy Fog", null),
            ), "Sisters, OR")
        compose.setContent {
            ScreenFrame("Weather", "Obsidian loop", onBack = {}) { WeatherContent(listOf(f, f.copy(label = "High point"), f.copy(label = "Night 1: Lost Lake")), false, null) }
        }
        compose.onRoot().captureRoboImage(out("screen-weather"))
    }

    @Test
    fun daysScreen() {
        val n = 60
        val coords = (0..n).map { i -> doubleArrayOf(44.1 + i * 0.0025, -121.7 - i * 0.002) }
        val total = com.trailguidegps.app.data.RouteNear.lengthM(coords)
        val prof = (0..n).map { i -> total * i / n to 1300.0 + 500 * kotlin.math.sin(i / 9.0) + i * 4 }
        val route = com.trailguidegps.app.data.SavedRoute("r", "Obsidian loop", 0L, emptyList(), coords,
            com.trailguidegps.app.data.RouteStats(total, 1100.0, 900.0, prof), listOf("Obsidian Trail"))
        fun pt(name: String, kind: String, i: Int, water: Boolean = false) =
            com.trailguidegps.app.data.Features.Point(name, kind, coords[i][0], coords[i][1], isWater = water)
        val spots = listOf(
            com.trailguidegps.app.data.CampSpot(pt("Sunshine Camp", "campsite", 18), total * 18 / n, 40.0, pt("Glacier Creek", "stream", 18, true) to 60.0),
            com.trailguidegps.app.data.CampSpot(pt("Glacier Creek", "stream", 22, true), total * 22 / n, 0.0, null),
            com.trailguidegps.app.data.CampSpot(pt("Linton Meadows", "campsite", 41), total * 41 / n, 120.0, null),
            com.trailguidegps.app.data.CampSpot(pt("Linton Spring", "spring", 44, true), total * 44 / n, 80.0, null),
        )
        val camps = listOf(com.trailguidegps.app.data.Camp(total * 18 / n, "Sunshine Camp"), com.trailguidegps.app.data.Camp(total * 41 / n, "Linton Meadows"))
        compose.setContent {
            ScreenFrame("Days and camps", route.name, onBack = {}) {
                DaysContent(route, Pace(), 3, {}, camps, {}, spots, { _, _ -> spots[0].water }, listOf(total * 22 / n, total * 44 / n))
            }
        }
        compose.onRoot().captureRoboImage(out("screen-days"))
    }

    @Test fun mainPanel() = panel("panel-main", null, false)
    @Test fun placeCard() = panel("panel-place", spring, false)
    @Test fun placeCardExpanded() = panel("panel-place-expanded", spring, true)
}
