package com.trailguidegps.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import com.trailguidegps.app.data.RouteColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
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

/**
 * Elevation along the route, with the low and high point labelled in feet.
 * Press or drag on it and [onScrub] gets the distance along the route (metres),
 * so the map can show where that is. It stays marked after you let go.
 */
@Composable
fun ElevationProfile(stats: RouteStats, modifier: Modifier = Modifier, color: Color = RoutePurple, onScrub: ((Double?) -> Unit)? = null) {
    val pts = stats.profile
    if (pts.size < 2) return
    val minE = pts.minOf { it.second }
    val maxE = pts.maxOf { it.second }
    val maxD = pts.last().first.coerceAtLeast(1.0)
    // At least 100 ft of vertical range so flat routes don't look dramatic.
    val range = (maxE - minE).coerceAtLeast(30.0)
    var scrub by remember(pts) { mutableStateOf<Double?>(null) }
    val scrubCallback by rememberUpdatedState(onScrub)
    DisposableEffect(pts) { onDispose { scrubCallback?.invoke(null) } }
    fun elevationAt(d: Double): Double {
        var i = pts.indexOfFirst { it.first >= d }
        if (i <= 0) return pts[maxOf(i, 0)].second
        val (d0, e0) = pts[i - 1]
        val (d1, e1) = pts[i]
        return if (d1 == d0) e1 else e0 + (e1 - e0) * (d - d0) / (d1 - d0)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            val s0 = scrub
            if (s0 != null) {
                Text(
                    "%.1f mi · %s".format(s0 / Geo.M_PER_MI, Geo.formatFeet(elevationAt(s0))),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f),
                )
                Text("marked on map", fontSize = 11.sp, color = TG.Muted)
            } else {
                Text("${Geo.formatFeet(maxE)} high", fontSize = 11.sp, color = TG.Muted, modifier = Modifier.weight(1f))
                Text(if (onScrub != null) "press the graph to find a spot" else "${Geo.formatFeet(minE)} low", fontSize = 11.sp, color = TG.Muted)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(64.dp).padding(top = 2.dp)
                .semantics { contentDescription = "Elevation profile from ${Geo.formatFeet(minE)} to ${Geo.formatFeet(maxE)}" }
                .pointerInput(pts) {
                    if (scrubCallback == null) return@pointerInput
                    awaitEachGesture {
                        fun set(x: Float) {
                            val d = (x / size.width).coerceIn(0f, 1f) * maxD
                            scrub = d
                            scrubCallback?.invoke(d)
                        }
                        val down = awaitFirstDown()
                        set(down.position.x)
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            set(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            fun x(d: Double) = (d / maxD * size.width).toFloat()
            fun y(e: Double) = (size.height - ((e - minE) / range) * (size.height - 4f) - 2f).toFloat()
            val line = Path()
            pts.forEachIndexed { i, (d, e) -> if (i == 0) line.moveTo(x(d), y(e)) else line.lineTo(x(d), y(e)) }
            val fill = Path().apply { addPath(line); lineTo(x(maxD), size.height); lineTo(0f, size.height); close() }
            drawPath(fill, color.copy(alpha = 0.14f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
            drawLine(Color(0xFFD4D7D2), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            scrub?.let { d ->
                val px = x(d)
                drawLine(TG.Ink, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1.5.dp.toPx())
                drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, y(elevationAt(d))))
                drawCircle(TG.Ink, radius = 5.dp.toPx(), center = Offset(px, y(elevationAt(d))), style = Stroke(width = 2.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0 mi", fontSize = 11.sp, color = TG.Muted, modifier = Modifier.weight(1f))
            Text("%.1f mi".format(maxD / Geo.M_PER_MI), fontSize = 11.sp, color = TG.Muted)
        }
    }
}

/** A row of color dots to pick a route color. */
@Composable
fun RouteColorPicker(selected: String, onPick: (String) -> Unit) {
    // The dots share the row's width equally and stay round, however narrow the dialog is.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteColors.ALL.forEach { (name, hex) ->
            val c = Color(android.graphics.Color.parseColor(hex))
            val isSel = hex.equals(selected, true)
            Box(
                Modifier.weight(1f).widthIn(max = 44.dp).aspectRatio(1f)
                    .clip(CircleShape).clickable { onPick(hex) }
                    .semantics { contentDescription = "$name${if (isSel) ", selected" else ""}" }
                    .background(if (isSel) TG.Ink else Color.Transparent, CircleShape)
                    .padding(3.dp)
                    .background(Color.White, CircleShape)
                    .padding(2.dp)
                    .background(c, CircleShape),
            )
        }
    }
}

fun routeColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(RoutePurple)

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
    onScrub: (Double?) -> Unit = {},
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
                ElevationProfile(plan.stats, onScrub = onScrub)
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
fun SaveRouteDialog(defaultName: String, onSave: (name: String, color: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var color by remember { mutableStateOf(RouteColors.DEFAULT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save route") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Route name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                RouteColorPicker(color) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep planning") } },
    )
}
