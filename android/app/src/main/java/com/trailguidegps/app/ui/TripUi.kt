package com.trailguidegps.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Camp
import com.trailguidegps.app.data.CampSpot
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.PeakView
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.Search
import com.trailguidegps.app.data.TripDays
import com.trailguidegps.app.data.Weather
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Amber = Color(0xFF8A4B00)
private fun mi(m: Double) = "%.1f mi".format(m / Geo.M_PER_MI)
private fun ft(m: Double) = "%,d ft".format((m * Geo.FT_PER_M).roundToInt())

/** Two or three small text buttons under a place card: peaks in view, weather. */
@Composable
fun PlaceExtras(label: String, lat: Double, lon: Double, onPeaks: (String, Double, Double) -> Unit, onWeather: (String, Double, Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onPeaks(label, lat, lon) }) { Text("Peaks in view", fontSize = 13.sp, color = TG.Accent) }
        TextButton(onClick = { onWeather(label, lat, lon) }) { Text("Weather", fontSize = 13.sp, color = TG.Accent) }
    }
}

// ---- Peaks in view ----

/** Which named peaks can be seen from a spot, with the one you're facing when the compass is on. */
@Composable
fun PeaksDialog(vm: AppViewModel, title: String, lat: Double, lon: Double, onPick: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<PeakView.Result?>(null) }
    var done by remember { mutableStateOf(false) }
    var byDistance by remember { mutableStateOf(false) }
    val heading by vm.compass.heading.collectAsStateWithLifecycle()
    LaunchedEffect(lat, lon) { result = vm.peaksFrom(lat, lon); done = true }
    DisposableEffect(Unit) { vm.compass.start(); onDispose { vm.compass.stop() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Peaks in view", maxLines = 1) },
        text = {
            Column {
                Text("From $title" + (result?.let { " · ${ft(it.fromEleM)}" } ?: ""), fontSize = 13.sp, color = TG.Muted, maxLines = 2)
                val r = result
                when {
                    !done -> Text("Checking sight lines to peaks within 60 miles…", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                    r == null -> Text("This needs the offline terrain data, and a spot inside the map area.", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                    else -> {
                        val f = PeakView.facing(r.visible, heading)
                        if (f != null) {
                            Surface(color = Color(0xFFEFF5EC), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(Modifier.clickable { onPick(f.lat, f.lon) }.padding(10.dp)) {
                                    Text("You're facing", fontSize = 11.sp, color = TG.Muted)
                                    Text(f.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                                    Text("${ft(f.eleM)} · ${mi(f.distanceM)} away", fontSize = 13.sp, color = TG.Ink)
                                }
                            }
                        } else if (heading != null) {
                            Text("Point the top of the phone at a peak to name it.", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))
                        }
                        val list = if (byDistance) r.visible.sortedBy { it.distanceM } else r.visible
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !byDistance, onClick = { byDistance = false }, label = { Text("By direction") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
                            FilterChip(selected = byDistance, onClick = { byDistance = true }, label = { Text("Nearest") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
                        }
                        if (list.isEmpty()) {
                            Text("No named peaks in view. Ridges or trees may block the view, or it's a low spot.", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 8.dp))
                        } else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(list, key = { "${it.name}${it.lat}${it.lon}" }) { p ->
                                Row(Modifier.fillMaxWidth().clickable { onPick(p.lat, p.lon) }.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.width(56.dp)) {
                                        Text(Geo.compassPoint(p.bearingDeg), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Accent)
                                        Text("${p.bearingDeg.roundToInt()}°", fontSize = 11.sp, color = TG.Muted)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontSize = 15.sp, color = TG.Ink, maxLines = 1)
                                        Text("${ft(p.eleM)} · ${mi(p.distanceM)}" + if (p.elevationAngleDeg >= 0) " · %.1f° up".format(p.elevationAngleDeg) else " · %.1f° down".format(-p.elevationAngleDeg),
                                            fontSize = 12.sp, color = TG.Muted)
                                        if (p.marginal) Text(if (p.visible) "Only just clears the ground in front; trees may hide it" else "Just hidden; the top may peek out",
                                            fontSize = 12.sp, color = Amber)
                                    }
                                }
                                HorizontalDivider(color = TG.Line)
                            }
                        }
                        Text("Checked ${r.checked} named peaks within 60 mi; ${r.hiddenCount} are hidden by terrain. Worked out from 90 ft elevation data, " +
                            "allowing for the earth's curve; trees, haze and buildings aren't included. Bearings are true north.",
                            fontSize = 11.sp, color = TG.Muted, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- Multi-night trips ----

/** Splits a route into days: camps suggested by effort, each one adjustable. */
@Composable
fun TripDaysDialog(vm: AppViewModel, route: SavedRoute, pace: Pace, onDismiss: () -> Unit) {
    val features by vm.features.collectAsStateWithLifecycle()
    var spots by remember { mutableStateOf<List<CampSpot>?>(null) }
    var camps by remember { mutableStateOf(route.camps.sortedBy { it.alongM }) }
    val totalMin = pace.minutes(route.stats.distanceM, route.stats.gainM)
    var days by remember { mutableIntStateOf(if (route.camps.isNotEmpty()) route.camps.size + 1 else ceil(totalMin / 360.0).toInt().coerceIn(2, 7)) }
    var choosing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(route.id) {
        val s = vm.campSpots(route)
        spots = s
        if (camps.isEmpty()) camps = TripDays.suggest(route, days, pace, s)
    }
    val total = route.stats.distanceM
    val tripDays = remember(camps, pace) { TripDays.days(route, camps, pace) }
    val waterStops = remember(route.id, features) { vm.waterPlan(route.coords, 0.25 * Geo.M_PER_MI).stops }

    fun setDays(n: Int) {
        days = n.coerceIn(1, 10)
        camps = TripDays.suggest(route, days, pace, spots.orEmpty())
        choosing = null
    }
    fun moveCamp(i: Int, byM: Double) {
        val lo = (camps.getOrNull(i - 1)?.alongM ?: 0.0) + 200
        val hi = (camps.getOrNull(i + 1)?.alongM ?: total) - 200
        if (hi <= lo) return
        val along = (camps[i].alongM + byM).coerceIn(lo, hi)
        // Snap to a campsite or water within 300 ft of where it lands.
        val snap = spots.orEmpty().filter { kotlin.math.abs(it.alongM - along) < 90 }.minByOrNull { it.tier }
        camps = camps.toMutableList().also { it[i] = snap?.let { s -> Camp(s.alongM, s.point.label) } ?: Camp(along, null) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Days and camps", maxLines = 1) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(route.name, fontSize = 13.sp, color = TG.Muted, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Days", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { setDays(days - 1) }, enabled = days > 1, shape = RoundedCornerShape(10.dp)) { Text("−") }
                    Text("$days", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TG.Ink, modifier = Modifier.padding(horizontal = 14.dp))
                    OutlinedButton(onClick = { setDays(days + 1) }, enabled = days < 10, shape = RoundedCornerShape(10.dp)) { Text("+") }
                }
                Text("Camps are placed so each day takes about the same time at your pace, then moved to a campsite " +
                    "(with water within 1,000 ft if possible) or a water source when one is close.", fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
                if (spots == null) Text("Looking for campsites and water…", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))
                tripDays.forEachIndexed { i, d ->
                    HorizontalDivider(color = TG.Line, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Day ${d.day}: ${mi(d.distanceM)} · +${ft(d.gainM)} / −${ft(d.lossM)} · ${Pace.format(d.minutes)}",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    val water = waterStops.count { it.alongM >= d.fromM && it.alongM < d.toM }
                    Text("Miles %.1f–%.1f · %s".format(d.fromM / Geo.M_PER_MI, d.toM / Geo.M_PER_MI,
                        if (water == 0) "no mapped water within ¼ mi" else "$water water source${if (water == 1) "" else "s"} within ¼ mi"),
                        fontSize = 12.sp, color = if (water == 0) Amber else TG.Muted)
                    if (d.endsAtCamp) {
                        val c = camps[i]
                        val p = RouteStore.pointAt(route.coords, c.alongM)
                        Text("Night ${i + 1}: " + (c.name ?: "mile %.1f (no campsite mapped here)".format(c.alongM / Geo.M_PER_MI)),
                            fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                        val w = p?.let { features?.waterNear(it[0], it[1], Search.CAMP_WATER_M) }
                        Text(w?.let { (wp, dist) -> "Water ${if (dist < 15) "right there" else Geo.formatDistance(dist)}: ${wp.label}" + if (wp.seasonal) " (may be seasonal)" else "" }
                            ?: "No mapped water within 1,000 ft of camp", fontSize = 12.sp, color = if (w == null || w.first.seasonal) Amber else Color(0xFF1F5FA8))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { moveCamp(i, -0.5 * Geo.M_PER_MI) }) { Text("◀ ½ mi", fontSize = 13.sp, color = TG.Accent) }
                            TextButton(onClick = { moveCamp(i, 0.5 * Geo.M_PER_MI) }) { Text("½ mi ▶", fontSize = 13.sp, color = TG.Accent) }
                            TextButton(onClick = { choosing = if (choosing == i) null else i }) { Text("Campsites nearby", fontSize = 13.sp, color = TG.Accent) }
                        }
                        if (choosing == i) {
                            val lo = camps.getOrNull(i - 1)?.alongM ?: 0.0
                            val hi = camps.getOrNull(i + 1)?.alongM ?: total
                            val near = TripDays.nearby(spots.orEmpty(), c.alongM, 8000.0).filter { it.alongM > lo + 200 && it.alongM < hi - 200 }.take(12)
                            if (near.isEmpty()) Text("No mapped campsites or water within 5 miles.", fontSize = 12.sp, color = TG.Muted)
                            near.forEach { s ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    camps = camps.toMutableList().also { it[i] = Camp(s.alongM, s.point.label) }; choosing = null
                                }.padding(vertical = 5.dp, horizontal = 4.dp)) {
                                    Text(s.label, fontSize = 13.sp, color = TG.Ink, maxLines = 2)
                                    val delta = (s.alongM - c.alongM) / Geo.M_PER_MI
                                    Text("Mile %.1f (%s%.1f mi)".format(s.alongM / Geo.M_PER_MI, if (delta >= 0) "+" else "−", kotlin.math.abs(delta)) +
                                        if (s.offM > 30) " · ${Geo.formatDistance(s.offM)} off route" else "", fontSize = 11.sp, color = TG.Muted)
                                }
                            }
                        }
                    }
                }
                Text("Dispersed camping: at least 100 ft from water on national forest land, about 200 ft on BLM land.",
                    fontSize = 11.sp, color = TG.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { vm.setCamps(route.id, if (days <= 1) emptyList() else camps); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (route.camps.isNotEmpty()) TextButton(onClick = { vm.setCamps(route.id, emptyList()); onDismiss() }) { Text("Day hike", color = TG.Muted) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "3 days: 8.2 · 9.1 · 6.0 mi" for a route with camps, or null. */
fun daysLine(route: SavedRoute, pace: Pace): String? {
    if (route.camps.isEmpty()) return null
    val d = TripDays.days(route, route.camps, pace)
    return "${d.size} days: " + d.joinToString(" · ") { "%.1f".format(it.distanceM / Geo.M_PER_MI) } + " mi"
}

// ---- Weather ----

/**
 * The National Weather Service forecast for one or more spots, fetched when opened (online),
 * with the last forecast kept for reading on the trail.
 */
@Composable
fun WeatherDialog(vm: AppViewModel, key: String, title: String, spots: List<Triple<String, Double, Double>>, onDismiss: () -> Unit) {
    var forecasts by remember { mutableStateOf(vm.savedWeather(key)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var allDays by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        loading = true; error = null
        scope.launch {
            vm.fetchWeather(key, spots).onSuccess { forecasts = it }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(key) { refresh() }
    val stamp = SimpleDateFormat("EEE h:mm a", Locale.US)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weather", maxLines = 1) },
        text = {
            Column {
                Text(title, fontSize = 13.sp, color = TG.Muted, maxLines = 1)
                if (loading) Text("Getting the forecast…", fontSize = 13.sp, color = TG.Muted)
                error?.let { Text(it + if (forecasts.isNotEmpty()) " Showing the last forecast." else "", fontSize = 13.sp, color = Amber) }
                if (forecasts.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        forecasts.forEachIndexed { i, f ->
                            FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(f.label, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
                        }
                    }
                }
                forecasts.getOrNull(tab.coerceAtMost(forecasts.size - 1))?.let { f ->
                    val sub = listOfNotNull(
                        f.area?.let { "near $it" },
                        f.spotEleM?.let { "your spot ${ft(it)}" },
                        f.gridEleM?.let { "forecast for ${ft(it)}" },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 4.dp))
                    f.tempAdjustF?.let { adj ->
                        Text(if (adj < 0) "You'll be higher than the forecast area: expect about ${-adj}°F colder." else "You'll be lower than the forecast area: expect about ${adj}°F warmer.",
                            fontSize = 12.sp, color = Amber)
                    }
                    Text("As of ${stamp.format(Date(f.fetchedAt))}", fontSize = 11.sp, color = TG.Muted)
                    HorizontalDivider(color = TG.Line, modifier = Modifier.padding(top = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(if (allDays) f.periods else f.periods.take(6), key = { it.name }) { p ->
                            Column(Modifier.fillMaxWidth().clickable { open = if (open == p.name) null else p.name }.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                                    p.tempF?.let {
                                        val shown = it + (f.tempAdjustF ?: 0)
                                        Text("${if (p.isDay) "High" else "Low"} $shown°F", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (p.isDay) Color(0xFFB4491A) else Color(0xFF1F4FB4))
                                    }
                                }
                                Text(p.short, fontSize = 13.sp, color = TG.Ink)
                                Text(listOfNotNull(p.rainChance?.takeIf { it > 0 }?.let { "Rain/snow $it%" }, p.wind.takeIf { it.isNotBlank() }?.let { "Wind $it" })
                                    .joinToString(" · "), fontSize = 12.sp, color = TG.Muted)
                                if (open == p.name) Text(p.detail, fontSize = 12.sp, color = TG.Ink, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            HorizontalDivider(color = TG.Line)
                        }
                    }
                    if (f.tempAdjustF != null) Text("Temperatures are adjusted for your elevation. Tap a period for the full forecast.", fontSize = 11.sp, color = TG.Muted)
                    else Text("Tap a period for the full forecast.", fontSize = 11.sp, color = TG.Muted)
                }
                if (forecasts.isEmpty() && !loading && error == null) Text("No forecast yet.", fontSize = 13.sp, color = TG.Muted)
                Text("Forecast: National Weather Service.", fontSize = 11.sp, color = TG.Muted, modifier = Modifier.padding(top = 2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                if ((forecasts.firstOrNull()?.periods?.size ?: 0) > 6) TextButton(onClick = { allDays = !allDays }) { Text(if (allDays) "3 days" else "7 days") }
                TextButton(onClick = { refresh() }, enabled = !loading) { Text("Refresh") }
            }
        },
    )
}
