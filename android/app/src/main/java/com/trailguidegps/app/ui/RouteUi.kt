package com.trailguidegps.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.RouteStats
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.geo.Geo
import kotlin.math.roundToInt

val RoutePurple = Color(0xFF6B3FA0)
private val ProfileFill = Color(0xFFECE3F5)

/** Elevation along the route, with the low and high point labelled in feet. */
@Composable
fun ElevationProfile(stats: RouteStats, modifier: Modifier = Modifier) {
    val pts = stats.profile
    if (pts.size < 2) return
    val minE = pts.minOf { it.second }
    val maxE = pts.maxOf { it.second }
    val maxD = pts.last().first.coerceAtLeast(1.0)
    // At least 100 ft of vertical range so flat routes don't look dramatic.
    val range = (maxE - minE).coerceAtLeast(30.0)
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("${Geo.formatFeet(maxE)} high", fontSize = 11.sp, color = TG.Muted, modifier = Modifier.weight(1f))
            Text("${Geo.formatFeet(minE)} low", fontSize = 11.sp, color = TG.Muted)
        }
        Canvas(
            Modifier.fillMaxWidth().height(56.dp).padding(top = 2.dp)
                .semantics { contentDescription = "Elevation profile from ${Geo.formatFeet(minE)} to ${Geo.formatFeet(maxE)}" },
        ) {
            fun x(d: Double) = (d / maxD * size.width).toFloat()
            fun y(e: Double) = (size.height - ((e - minE) / range) * (size.height - 4f) - 2f).toFloat()
            val line = Path()
            pts.forEachIndexed { i, (d, e) -> if (i == 0) line.moveTo(x(d), y(e)) else line.lineTo(x(d), y(e)) }
            val fill = Path().apply { addPath(line); lineTo(x(maxD), size.height); lineTo(0f, size.height); close() }
            drawPath(fill, ProfileFill)
            drawPath(line, RoutePurple, style = Stroke(width = 2.dp.toPx()))
            drawLine(Color(0xFFD4D7D2), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        }
        Text("0 mi", fontSize = 11.sp, color = TG.Muted)
    }
}

/** Miles, climb, descent and estimated time in one row. */
@Composable
fun RouteStatsRow(stats: RouteStats, pace: Pace) {
    Row(Modifier.fillMaxWidth()) {
        StatBox("%.1f".format(stats.distanceM / Geo.M_PER_MI), "miles", Modifier.weight(1f))
        StatBox("+%,d".format((stats.gainM * Geo.FT_PER_M).roundToInt()), "ft climb", Modifier.weight(1f))
        StatBox("−%,d".format((stats.lossM * Geo.FT_PER_M).roundToInt()), "ft descent", Modifier.weight(1f))
        StatBox(Pace.format(pace.minutes(stats.distanceM, stats.gainM)), "est. time", Modifier.weight(1.1f))
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TG.Ink, maxLines = 1)
        Text(label, fontSize = 12.sp, color = TG.Muted)
    }
}

/** Bottom panel while planning a route. */
@Composable
fun RoutePlannerPanel(
    plan: AppViewModel.PlanState,
    pace: Pace,
    routingReady: Boolean?,
    onAddWaypoint: () -> Unit,
    onSave: () -> Unit,
    onEditPace: () -> Unit,
) {
    Column {
        if (plan.points.isEmpty()) {
            Text("Tap the map where your route starts.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
            Text(
                when (routingReady) {
                    false -> "The trail network isn't downloaded yet, so routes use straight lines. Check for map updates."
                    null -> "Loading the trail network…"
                    else -> "Then tap each place you want to go. With Follow trails on, the route takes the trails between your points."
                },
                fontSize = 13.sp, color = TG.Muted, lineHeight = 18.sp,
            )
        } else {
            RouteStatsRow(plan.stats, pace)
            if (plan.stats.profile.size >= 2) {
                Spacer(Modifier.height(8.dp))
                ElevationProfile(plan.stats)
            }
            if (plan.via.isNotEmpty()) {
                Text(
                    "Via " + plan.via.joinToString(" → "), fontSize = 13.sp, color = TG.Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${plan.points.size} point${if (plan.points.size == 1) "" else "s"}" + if (plan.busy) " · finding the trail…" else "",
                    fontSize = 12.sp, color = TG.Muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEditPace) { Text("Pace: %.1f mph".format(pace.mph), fontSize = 12.sp, color = TG.Accent) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAddWaypoint, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                Text("Add waypoint", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
            }
            Button(
                onClick = onSave, enabled = plan.points.size >= 2 && !plan.busy, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RoutePurple, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Save route", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Pick a saved waypoint to add to the route; nearest to the last point first. */
@Composable
fun WaypointPickerDialog(waypoints: List<Waypoint>, fromLat: Double?, fromLon: Double?, onPick: (Waypoint) -> Unit, onDismiss: () -> Unit) {
    val sorted = remember(waypoints, fromLat, fromLon) {
        if (fromLat == null || fromLon == null) waypoints.sortedByDescending { it.createdAt }
        else waypoints.sortedBy { Geo.distanceM(fromLat, fromLon, it.lat, it.lon) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a waypoint") },
        text = {
            if (sorted.isEmpty()) {
                Text("You have no saved waypoints yet. Drop one with Pin, or long-press the map, then come back.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sorted, key = { it.id }) { w ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).background(Color(android.graphics.Color.parseColor(w.color)), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                                if (fromLat != null && fromLon != null) {
                                    Text("${Geo.formatDistance(Geo.distanceM(fromLat, fromLon, w.lat, w.lon))} from the last point", fontSize = 12.sp, color = TG.Muted)
                                }
                            }
                            TextButton(onClick = { onPick(w) }) { Text("Add", color = RoutePurple, fontWeight = FontWeight.SemiBold) }
                        }
                        HorizontalDivider(color = TG.Line)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Hiking-time settings: pace, extra time for climbing, breaks. */
@Composable
fun PaceDialog(pace: Pace, onSave: (Pace) -> Unit, onDismiss: () -> Unit) {
    var mph by remember { mutableStateOf(pace.mph) }
    var climb by remember { mutableStateOf(pace.climbMinPer1000Ft) }
    var breaks by remember { mutableStateOf(pace.breakMinPerHour) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hiking time estimates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper("Pace on flat trail", "%.1f mph".format(mph),
                    onMinus = { mph = ((mph - 0.1) * 10).roundToInt() / 10.0; if (mph < 1.0) mph = 1.0 },
                    onPlus = { mph = ((mph + 0.1) * 10).roundToInt() / 10.0; if (mph > 3.5) mph = 3.5 })
                Stepper("Climb time per 1,000 ft", "$climb min",
                    onMinus = { climb = (climb - 5).coerceAtLeast(0) }, onPlus = { climb = (climb + 5).coerceAtMost(60) })
                Stepper("Breaks per hour", "$breaks min",
                    onMinus = { breaks = (breaks - 5).coerceAtLeast(0) }, onPlus = { breaks = (breaks + 5).coerceAtMost(20) })
                val example = Pace(mph, climb, breaks)
                Text(
                    "Example: 5 mi with 1,000 ft of climb takes about ${Pace.format(example.minutes(5 * Geo.M_PER_MI, 1000 / Geo.FT_PER_M))}.",
                    fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { mph = 2.0; climb = 30; breaks = 10 }) { Text("Reset to defaults (2.0 mph, 30 min, 10 min)", color = TG.Accent) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Pace(mph, climb, breaks)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = TG.Ink, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, shape = CircleShape, modifier = Modifier.width(44.dp).height(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 18.sp, color = TG.Ink) }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink,
            modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus, shape = CircleShape, modifier = Modifier.width(44.dp).height(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 18.sp, color = TG.Ink) }
    }
}

/** Ask for a name when saving a planned route. */
@Composable
fun SaveRouteDialog(defaultName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save route") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Route name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep planning") } },
    )
}
