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
import com.trailguidegps.app.data.Difficulty
import com.trailguidegps.app.data.RouteTerrain
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.drawText
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
fun ElevationProfile(stats: RouteStats, modifier: Modifier = Modifier, color: Color = RoutePurple, onScrub: ((Double?) -> Unit)? = null,
                     camps: List<Double> = emptyList(), dayLabels: List<String>? = null) {
    val pts = stats.profile
    if (pts.size < 2) return
    val dayText = androidx.compose.ui.text.rememberTextMeasurer()
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
            // A multi-day trip: every other day lightly shaded, a dashed line at each camp, and the day numbers.
            if (camps.isNotEmpty()) {
                val marks = (listOf(0.0) + camps.filter { it > 0 && it < maxD }.sorted() + listOf(maxD)).distinct()
                marks.zipWithNext().forEachIndexed { i, (a, b) ->
                    if (i % 2 == 1) drawRect(TG.Ink.copy(alpha = 0.05f), Offset(x(a), 0f), androidx.compose.ui.geometry.Size(x(b) - x(a), size.height))
                    if (x(b) - x(a) > 14.dp.toPx()) drawText(dayText, dayLabels?.getOrNull(i) ?: "${i + 1}", Offset(x(a) + 3.dp.toPx(), 0f),
                        androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = TG.Muted, fontWeight = FontWeight.SemiBold))
                }
                marks.drop(1).dropLast(1).forEach { a ->
                    drawLine(TG.Muted, Offset(x(a), 0f), Offset(x(a), size.height), strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    // A little tent at the top of the line.
                    val t = Path().apply { moveTo(x(a), 1f); lineTo(x(a) - 4.dp.toPx(), 8.dp.toPx()); lineTo(x(a) + 4.dp.toPx(), 8.dp.toPx()); close() }
                    drawPath(t, TG.Ink.copy(alpha = 0.7f))
                }
            }
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

val DifficultyColors = listOf(Color(0xFF2E7D32), Color(0xFF558B2F), Color(0xFFB07D00), Color(0xFFD84315), Color(0xFFB71C1C))

/** One line: the rating, the steepest stretch and the high point. Tap for the full breakdown. */
@Composable
fun DifficultyLine(d: Difficulty, onClick: () -> Unit, modifier: Modifier = Modifier, showLink: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onClick)
            .semantics { contentDescription = "Difficulty: ${d.label}. Tap for details." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.background(DifficultyColors[d.level], RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(d.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        val bits = buildList {
            d.hardestDay?.let { add("${d.days.size} days, hardest is day ${it.day}") }
            d.steepestGradePct?.let { add("steepest ${it.roundToInt()}%") }
            d.highPointFt?.let { add("top %,d ft".format(it.roundToInt())) }
            d.terrain?.takeIf { it.sacMax > 0 }?.let { add("T${it.sacMax}") }
        }
        Text(bits.joinToString(" · "), fontSize = 12.sp, color = TG.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (showLink) Text("Details", fontSize = 12.sp, color = TG.Accent, fontWeight = FontWeight.SemiBold)
    }
}

/** The full difficulty breakdown and how it's worked out. */
@Composable
fun DifficultyDialog(d: Difficulty, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Difficulty: ${d.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (d.days.isNotEmpty()) {
                    DetailRow("Rated by the hardest day", "Day ${d.hardestDay?.day}: " + "%.0f".format(d.score) + " (Shenandoah National Park scale)")
                    Column {
                        Text("Each day", fontSize = 12.sp, color = TG.Muted)
                        d.days.forEach { r -> DayRatingRow(r) }
                    }
                    DetailRow("As a single day", Difficulty.LABELS[d.wholeLevel] + " (" + "%.0f".format(d.wholeScore) + ")")
                } else DetailRow("Rating", "%.0f".format(d.score) + " (Shenandoah National Park scale)")
                Text(
                    "√(feet of climb × 2 × miles). Under 50 easiest · 50–100 moderate · 100–150 moderately strenuous · " +
                        "150–200 strenuous · over 200 very strenuous.",
                    fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp,
                )
                d.steepestGradePct?.let { g ->
                    val where = d.steepestAtM?.let { " at mile %.1f".format(it / Geo.M_PER_MI) } ?: ""
                    val feel = when { g < 8 -> "gentle"; g < 15 -> "moderate"; g < 25 -> "steep"; else -> "very steep" }
                    DetailRow("Steepest stretch", "${g.roundToInt()}% ($feel), over a tenth of a mile$where")
                }
                d.highPointFt?.let { h ->
                    DetailRow("Highest point", "%,d ft".format(h.roundToInt()) +
                        if (h >= 8000) ". Thinner air: expect to go slower and watch for altitude sickness." else "")
                }
                d.terrain?.let { t ->
                    val total = (t.trailM + t.trackM + t.roadM + t.offTrailM).coerceAtLeast(1.0)
                    fun share(m: Double) = "%.1f mi".format(m / Geo.M_PER_MI) + " (${(m / total * 100).roundToInt()}%)"
                    val parts = buildList {
                        if (t.trailM > 0) add("trail ${share(t.trailM)}")
                        if (t.trackM > 0) add("dirt road ${share(t.trackM)}")
                        if (t.roadM > 0) add("road ${share(t.roadM)}")
                        if (t.offTrailM > 0) add("off trail ${share(t.offTrailM)}")
                    }
                    if (parts.isNotEmpty()) DetailRow("Surface", parts.joinToString(", "))
                    if (t.offTrailM > 160) {
                        Text("Off-trail parts are straight lines on the map; the real ground may be rougher and longer.",
                            fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
                    }
                    val ratedPct = (t.sacRatedM / total * 100).roundToInt()
                    DetailRow("Technical (SAC scale)",
                        if (t.sacMax == 0) "Not rated: none of these trails have a difficulty tag in the map data."
                        else Difficulty.SAC[t.sacMax] + if (ratedPct < 90) " (only $ratedPct% of the route is rated)" else "")
                } ?: DetailRow("Technical (SAC scale)", "Unknown for this route. Routes saved before version 0.7 don't have it.")
                Text("Ratings are a guide from map data. Check current conditions, snow and closures before you go.",
                    fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** "Day 2 · 11.2 mi · +2,850 ft · steepest 18%" with its rating chip. */
@Composable
fun DayRatingRow(r: Difficulty.DayRating, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Day ${r.day}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.width(52.dp))
        Text("%.1f mi · +%,d ft".format(r.distanceM / Geo.M_PER_MI, (r.gainM * Geo.FT_PER_M).roundToInt()) +
            (r.steepestPct?.let { " · steepest ${it.roundToInt()}%" } ?: ""), fontSize = 13.sp, color = TG.Ink, modifier = Modifier.weight(1f))
        DifficultyChip(r.level)
    }
}

/** The colored rating label, e.g. "Moderate". */
@Composable
fun DifficultyChip(level: Int) {
    Box(Modifier.background(DifficultyColors[level], RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(Difficulty.LABELS[level], fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = TG.Muted)
        Text(value, fontSize = 15.sp, color = TG.Ink, lineHeight = 20.sp)
    }
}

/** Progress along a route you're following, what's left, and an off-route warning. */
@Composable
fun FollowPanel(f: com.trailguidegps.app.route.Following, pace: Pace, onStop: () -> Unit, modifier: Modifier = Modifier,
                onWaysOut: (() -> Unit)? = null) {
    val color = routeColor(f.route.color)
    Column(modifier) {
        if (f.offRoute && f.offByM != null) {
            Box(Modifier.fillMaxWidth().background(Color(0xFFC62828), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Off route by ${Geo.formatDistance(f.offByM)}. Follow the red dashed line back to it.",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 18.dp, height = 4.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                if (f.arrived) "Arrived: ${f.route.name}" else "Following ${f.route.name}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onWaysOut != null) TextButton(onClick = onWaysOut) { Text("Ways out", color = TG.Accent, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onStop) { Text("Stop", color = TG.Danger, fontWeight = FontWeight.SemiBold) }
        }
        if (f.gpsQuiet) {
            Text(if (f.lastFixAt == 0L) "Still no GPS fix. Off-route alerts can't work until there is one."
                else "No good GPS fix since ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(f.lastFixAt))}. " +
                    "The numbers below are from then, and off-route alerts are paused.",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Danger, lineHeight = 17.sp)
        }
        if (f.lastFixAt == 0L) {
            if (!f.gpsQuiet) Text("Waiting for GPS…", fontSize = 13.sp, color = TG.Muted)
        } else {
            val mins = pace.minutes(f.remainingM, f.climbLeftM)
            val arrive = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + mins * 60_000L))
            Row(Modifier.fillMaxWidth()) {
                FollowStat("%.1f".format(f.remainingM / Geo.M_PER_MI), "miles left", Modifier.weight(1f))
                FollowStat("+%,d".format((f.climbLeftM * Geo.FT_PER_M).roundToInt()), "ft climb left", Modifier.weight(1f))
                FollowStat(Pace.format(mins), "about $arrive", Modifier.weight(1.1f))
            }
            val total = (f.alongM + f.remainingM).coerceAtLeast(1.0)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).background(Color(0xFFE3E5E0), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth((f.alongM / total).toFloat().coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            }
            Text("%.1f of %.1f mi".format(f.alongM / Geo.M_PER_MI, total / Geo.M_PER_MI), fontSize = 11.sp, color = TG.Muted,
                modifier = Modifier.padding(top = 2.dp))
            // On a multi-day route, what matters today is tonight's camp, not the end of the trip.
            val camp = remember(f.route.id, (f.alongM / 50).toInt()) {
                f.route.camps.sortedBy { it.alongM }.firstOrNull { it.alongM > f.alongM + 50 }?.let { c ->
                    val prof = f.route.stats.profile
                    val raw = com.trailguidegps.app.data.TripDays.gainLoss(prof, 0.0, f.route.stats.distanceM).first
                    val scale = if (raw > 0) f.route.stats.gainM / raw else 0.0
                    Triple(c, c.alongM - f.alongM, com.trailguidegps.app.data.TripDays.gainLoss(prof, f.alongM, c.alongM).first * scale)
                }
            }
            val campMins = camp?.let { (_, dist, gain) -> pace.minutes(dist, gain) }
            if (camp != null && campMins != null) {
                val at = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + campMins * 60_000L))
                Text("To tonight's camp" + (camp.first.name?.let { " ($it)" } ?: "") + ": %.1f mi · +%,d ft · %s, about %s".format(
                    camp.second / Geo.M_PER_MI, (camp.third * Geo.FT_PER_M).roundToInt(), Pace.format(campMins), at),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
            f.nearest?.let { n ->
                val late = afterDarkWarning(n[0], n[1], System.currentTimeMillis(), campMins ?: mins)
                if (late != null) {
                    Text((if (campMins != null) "Camp: " else "") + late.replaceFirst("Back about", if (campMins != null) "there about" else "Back about"),
                        fontSize = 12.sp, color = TG.Warn, lineHeight = 16.sp)
                } else if (!f.arrived) {
                    // Getting there in daylight: how much light is left and how much of it is spare.
                    daylightToSpare(n[0], n[1], campMins ?: mins)?.let { Text(it, fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun FollowStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TG.Ink, maxLines = 1)
        Text(label, fontSize = 12.sp, color = TG.Muted, maxLines = 1)
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
    plan: com.trailguidegps.app.route.PlanState,
    pace: Pace,
    routingReady: Boolean?,
    onAddWaypoint: () -> Unit,
    onSave: () -> Unit,
    onEditPace: () -> Unit,
    onScrub: (Double?) -> Unit = {},
    onWater: () -> Unit = {},
    onRemoveSelected: () -> Unit = {},
    onClearSelected: () -> Unit = {},
    onAddAtStart: (Boolean) -> Unit = {},
    onFollowTrails: (Boolean) -> Unit = {},
    onOutAndBack: (Boolean) -> Unit = {},
) {
    var showDifficulty by remember { mutableStateOf(false) }
    // Compact by default, so the map gets most of the screen while you tap out a route. Remembered.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("panel", android.content.Context.MODE_PRIVATE) }
    var compact by remember { mutableStateOf(prefs.getBoolean("plan_compact", true)) }
    fun setCompact(v: Boolean) { compact = v; prefs.edit().putBoolean("plan_compact", v).apply() }
    val difficulty = remember(plan.stats, plan.legs) { Difficulty.of(plan.stats, RouteTerrain.of(plan.legs)) }
    if (showDifficulty) DifficultyDialog(difficulty) { showDifficulty = false }

    @Composable
    fun ModeToggle() {
        Row(Modifier.background(TG.Card, RoundedCornerShape(10.dp)).padding(2.dp)) {
            listOf(true to "Trails", false to "Straight").forEach { (on, label) ->
                val sel = plan.followTrails == on
                Box(Modifier.background(if (sel) RoutePurple else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onFollowTrails(on) }.padding(horizontal = 10.dp, vertical = 7.dp)
                    .semantics { contentDescription = if (on) "Follow trails" else "Straight line" }) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else TG.Ink)
                }
            }
        }
    }

    /** "Out & back": the route comes back to the start the same way. */
    @Composable
    fun OutBackToggle() {
        val on = plan.outAndBack
        Box(Modifier.padding(start = 6.dp).background(if (on) RoutePurple else TG.Card, RoundedCornerShape(10.dp))
            .clickable { onOutAndBack(!on) }.padding(horizontal = 10.dp, vertical = 9.dp)
            .semantics { contentDescription = if (on) "Out and back is on" else "Come back the same way" }) {
            Text("⇄ Out & back", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else TG.Ink)
        }
    }

    Column {
        // A point picked on the map: move it with the next tap, or take it out.
        plan.selected?.let { i ->
            Surface(color = TG.CardYellow, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when (i) { 0 -> "Start point"; plan.points.size - 1 -> "End point"; else -> "Point ${i + 1}" } + " picked",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                        Text("Tap the map where it should go", fontSize = 12.sp, color = TG.Muted)
                    }
                    TextButton(onClick = onRemoveSelected) { Text("Remove", color = TG.Danger) }
                    TextButton(onClick = onClearSelected) { Text("Done", color = TG.Accent) }
                }
            }
        }
        if (compact) {
            if (plan.points.isEmpty()) {
                Text("Tap the map where your route starts.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                if (routingReady == false) Text("The trail network isn't downloaded yet, so routes use straight lines.", fontSize = 12.sp, color = TG.Muted)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%.1f mi · +%,d ft · −%,d ft · %s".format(plan.stats.distanceM / Geo.M_PER_MI, (plan.stats.gainM * Geo.FT_PER_M).roundToInt(),
                            (plan.stats.lossM * Geo.FT_PER_M).roundToInt(), Pace.format(pace.minutes(plan.stats.distanceM, plan.stats.gainM))) +
                            if (plan.busy) " · finding trail…" else "",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (plan.points.size >= 2) Box(Modifier.clickable { showDifficulty = true }) { DifficultyChip(difficulty.level) }
                }
                if (plan.stats.profile.size >= 2) ElevationProfile(plan.stats, Modifier.padding(top = 4.dp), onScrub = onScrub)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeToggle()
                OutBackToggle()
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSave, enabled = plan.points.size >= 2 && !plan.busy, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RoutePurple, contentColor = Color.White),
                    modifier = Modifier.height(42.dp),
                ) { Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
            Row(Modifier.fillMaxWidth().clickable { setCompact(false) }, horizontalArrangement = Arrangement.Center) {
                Text("More ▲", fontSize = 13.sp, color = TG.Accent, modifier = Modifier.padding(top = 2.dp))
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeToggle()
            OutBackToggle()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { setCompact(true) }) { Text("Less ▼ (more map)", fontSize = 12.sp, color = TG.Accent) }
        }
        if (plan.points.size >= 2 && plan.selected == null) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("New points go at the", fontSize = 13.sp, color = TG.Muted)
                androidx.compose.material3.FilterChip(selected = !plan.addAtStart, onClick = { onAddAtStart(false) }, label = { Text("End") },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = RoutePurple, selectedLabelColor = Color.White))
                androidx.compose.material3.FilterChip(selected = plan.addAtStart, onClick = { onAddAtStart(true) }, label = { Text("Start") },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = RoutePurple, selectedLabelColor = Color.White))
            }
        }
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
            if (plan.points.size >= 2) DifficultyLine(difficulty, onClick = { showDifficulty = true }, Modifier.padding(top = 4.dp), showLink = false)
            if (plan.stats.profile.size >= 2) {
                Spacer(Modifier.height(8.dp))
                ElevationProfile(plan.stats, onScrub = onScrub)
            }
            plan.points.firstOrNull()?.let { s ->
                afterDarkWarning(s.lat, s.lon, System.currentTimeMillis(), pace.minutes(plan.stats.distanceM, plan.stats.gainM))?.let {
                    Text("If you start now: $it", fontSize = 12.sp, color = TG.Warn, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (plan.via.isNotEmpty()) {
                Text(
                    "Via " + plan.via.joinToString(" → "), fontSize = 13.sp, color = TG.Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "${plan.points.size} point${if (plan.points.size == 1) "" else "s"}" + if (plan.busy) " · finding the trail…" else "",
                fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.points.size >= 2) {
                    PillButton(Icons.Filled.Info, "Details") { showDifficulty = true }
                    PillButton(Icons.Filled.WaterDrop, "Water") { onWater() }
                }
                PillButton(Icons.Filled.DirectionsWalk, "Pace %.1f mph".format(pace.mph)) { onEditPace() }
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
fun SaveRouteDialog(
    defaultName: String, onSave: (name: String, color: String, folderId: String?, asNew: Boolean) -> Unit, onDismiss: () -> Unit,
    folders: List<com.trailguidegps.app.data.Folder> = emptyList(), folderId: String? = null,
    onCreateFolder: (String) -> com.trailguidegps.app.data.Folder,
    editing: Boolean = false, initialColor: String = RouteColors.DEFAULT,
) {
    var name by remember { mutableStateOf(defaultName) }
    var folder by remember { mutableStateOf(folderId) }
    var color by remember { mutableStateOf(initialColor) }
    var asNew by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Save changes" else "Save route") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Route name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                RouteColorPicker(color) { color = it }
                Text("Folder", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                FolderPicker(folders, folder, { folder = it }, onCreateFolder)
                if (editing) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { asNew = !asNew }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = asNew, onCheckedChange = { asNew = it })
                        Text("Save as a new route and keep the original", fontSize = 14.sp, color = TG.Ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color, folder, asNew) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep planning") } },
    )
}
