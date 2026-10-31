package com.trailguidegps.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Camp
import com.trailguidegps.app.data.CampSpot
import com.trailguidegps.app.data.Features
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val Amber = Color(0xFF8A4B00)
private val WaterBlue = Color(0xFF1F5FA8)
private val CampBrown = Color(0xFFB4491A)
private fun mi(m: Double) = "%.1f mi".format(m / Geo.M_PER_MI)
private fun ft(m: Double) = "%,d ft".format((m * Geo.FT_PER_M).roundToInt())

// ---- A full-screen page over the map ----

/** Opens [content] as a full-screen page over the map; back closes it. */
@Composable
fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
}

/** A page with a back arrow and title at the top, scrolling content, and an optional bar at the bottom. */
@Composable
fun ScreenFrame(
    title: String, subtitle: String?, onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to the map", tint = TG.Ink) }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TG.Ink, maxLines = 1)
                    subtitle?.let { Text(it, fontSize = 13.sp, color = TG.Muted, maxLines = 1) }
                }
                actions()
            }
            HorizontalDivider(color = TG.Line)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) { body() }
            bottom?.let {
                HorizontalDivider(color = TG.Line)
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { it() }
            }
        }
    }
}

// ---- Peaks in view ----

/** Which named peaks can be seen from a spot, with the one you're facing when the compass is on. */
@Composable
fun PeaksDialog(vm: AppViewModel, title: String, lat: Double, lon: Double, onPick: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<PeakView.Result?>(null) }
    var done by remember { mutableStateOf(false) }
    val heading by vm.compass.heading.collectAsStateWithLifecycle()
    LaunchedEffect(lat, lon) { result = vm.peaksFrom(lat, lon); done = true }
    DisposableEffect(Unit) { vm.compass.start(); onDispose { vm.compass.stop() } }
    FullScreen(onDismiss) {
        ScreenFrame("Peaks in view", "From $title" + (result?.let { " · ${ft(it.fromEleM)}" } ?: ""), onDismiss) {
            PeaksContent(result, done, heading, onPick)
        }
    }
}

@Composable
fun PeaksContent(result: PeakView.Result?, done: Boolean, heading: Float?, onPick: (Double, Double) -> Unit) {
    var byDistance by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf(emptySet<String>()) }
    var higherOnly by remember { mutableStateOf(false) }
    val r0 = result
    // Filters: kinds of summit (none picked = all), and only ones higher than you.
    val r = r0?.copy(visible = r0.visible.filter { (types.isEmpty() || PeakView.type(it) in types) && (!higherOnly || it.eleM > r0.fromEleM) })
    when {
        !done -> Text("Checking sight lines to peaks within 60 miles…", fontSize = 15.sp, color = TG.Ink)
        r == null -> Text("This needs the offline terrain data, and a spot inside the map area.", fontSize = 15.sp, color = TG.Ink)
        else -> {
            val counts = r0!!.visible.groupingBy { PeakView.type(it) }.eachCount()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White)
                FilterChip(selected = types.isEmpty(), onClick = { types = emptySet() }, label = { Text("All ${r0.visible.size}") }, colors = chip)
                PeakView.TYPES.filter { (counts[it] ?: 0) > 0 }.forEach { t ->
                    FilterChip(selected = t in types, onClick = { types = if (t in types) types - t else types + t },
                        label = { Text("$t ${counts[t]}") }, colors = chip)
                }
                FilterChip(selected = higherOnly, onClick = { higherOnly = !higherOnly }, label = { Text("Higher than me") }, colors = chip)
            }
            val facing = PeakView.facing(r.visible, heading)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PeakDial(r.visible, heading, facing, Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f))
            }
            Text(
                if (heading != null) "The top of the circle is where the phone points. Turn to name a peak."
                else "North is up. Dots further out are further away; bigger dots are higher.",
                fontSize = 12.sp, color = TG.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (facing != null) {
                Surface(color = Color(0xFFEFF5EC), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.clickable { onPick(facing.lat, facing.lon) }.padding(12.dp)) {
                        Text("You're facing", fontSize = 12.sp, color = TG.Muted)
                        Text(facing.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                        Text("${ft(facing.eleM)} · ${mi(facing.distanceM)} away · ${Geo.compassPoint(facing.bearingDeg)}", fontSize = 14.sp, color = TG.Ink)
                    }
                }
            }
            val list = if (byDistance) r.visible.sortedBy { it.distanceM } else r.visible
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${list.size} in view", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f))
                FilterChip(selected = !byDistance, onClick = { byDistance = false }, label = { Text("By direction") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
                FilterChip(selected = byDistance, onClick = { byDistance = true }, label = { Text("Nearest") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
            }
            if (list.isEmpty()) {
                Text(if (r0.visible.isEmpty()) "No named peaks in view. Ridges or trees may block the view, or it's a low spot."
                    else "None of the peaks in view match these filters.", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 8.dp))
            }
            list.forEach { p ->
                HorizontalDivider(color = TG.Line)
                Row(Modifier.fillMaxWidth().clickable { onPick(p.lat, p.lon) }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(58.dp)) {
                        Text(Geo.compassPoint(p.bearingDeg), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (p == facing) TG.Accent else TG.Ink)
                        Text("${p.bearingDeg.roundToInt()}°", fontSize = 12.sp, color = TG.Muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 16.sp, color = TG.Ink, fontWeight = if (p == facing) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        Text("${ft(p.eleM)} · ${mi(p.distanceM)}" + if (p.elevationAngleDeg >= 0) " · %.1f° up".format(p.elevationAngleDeg) else " · %.1f° down".format(-p.elevationAngleDeg),
                            fontSize = 13.sp, color = TG.Muted)
                        if (p.marginal) Text(if (p.visible) "Only just clears the ground in front; trees may hide it" else "Just hidden; the top may peek out",
                            fontSize = 12.sp, color = Amber)
                    }
                }
            }
            Text("Checked ${r.checked} named peaks within 60 mi; ${r.hiddenCount} are hidden by terrain. Worked out from 90 ft elevation data, " +
                "allowing for the earth's curve; trees, haze and buildings aren't included. Bearings are true north.",
                fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Peaks around you on a circle: direction by angle, distance outward (square-root scale so
 * near peaks don't bunch up), dot size by height. Turns with the compass so up is where you face.
 */
@Composable
private fun PeakDial(peaks: List<PeakView.Seen>, heading: Float?, facing: PeakView.Seen?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val maxD = PeakView.MAX_M
    val topEle = peaks.maxOfOrNull { it.eleM } ?: 1.0
    val lowEle = peaks.minOfOrNull { it.eleM } ?: 0.0
    // Label the facing peak and the most prominent few, so the dial stays readable.
    val labelled = (listOfNotNull(facing) + peaks.sortedByDescending { it.elevationAngleDeg }.take(6)).distinct()
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val rMax = size.minDimension / 2 - 30.dp.toPx()
        val rot = -(heading ?: 0f)
        // Rings at 10, 30 and 60 miles.
        listOf(16_093.0, 48_280.0, 96_560.0).forEach { d ->
            val rr = (sqrt(d / maxD) * rMax).toFloat()
            drawCircle(Color(0xFFDDE3D8), rr, c, style = Stroke(1.dp.toPx()))
            drawText(tm, "${(d / Geo.M_PER_MI).roundToInt()} mi", Offset(c.x + 4.dp.toPx(), c.y - rr + 1.dp.toPx()),
                TextStyle(fontSize = 9.sp, color = TG.Muted))
        }
        drawCircle(Color(0xFFF7F8F4), rMax * 0.04f + 6.dp.toPx(), c)
        // Everything is placed by its direction turned by the compass, with names drawn upright,
        // and a name is skipped if it would overlap one already drawn.
        fun at(bearing: Double, radius: Float): Offset {
            val a = Math.toRadians(bearing + rot)
            return Offset(c.x + (sin(a) * radius).toFloat(), c.y - (cos(a) * radius).toFloat())
        }
        val taken = ArrayList<androidx.compose.ui.geometry.Rect>()
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (t, b) ->
            val p = at(b, rMax + 16.dp.toPx())
            val lay = tm.measure(t, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (t == "N") Color(0xFFC62828) else TG.Ink))
            val tl = Offset(p.x - lay.size.width / 2, p.y - lay.size.height / 2)
            taken += androidx.compose.ui.geometry.Rect(tl, Size(lay.size.width.toFloat(), lay.size.height.toFloat()))
            drawText(lay, topLeft = tl)
        }
        val dots = peaks.map { pk ->
            val f = if (topEle > lowEle) ((pk.eleM - lowEle) / (topEle - lowEle)).toFloat() else 0.5f
            Triple(pk, at(pk.bearingDeg, (sqrt(pk.distanceM / maxD) * rMax).toFloat()), 3.dp.toPx() + f * 5.dp.toPx())
        }
        dots.forEach { (pk, p, dot) ->
            val col = if (pk == facing) TG.Accent else Color(0xFF5A4636)
            if (pk.visible) drawCircle(col, dot, p) else drawCircle(col, dot, p, style = Stroke(1.5.dp.toPx()))
            taken += androidx.compose.ui.geometry.Rect(p.x - dot, p.y - dot, p.x + dot, p.y + dot)
        }
        // Names: the facing peak first, then the most prominent, as long as they fit.
        labelled.forEach { pk ->
            val (_, p, dot) = dots.firstOrNull { it.first == pk } ?: return@forEach
            val col = if (pk == facing) TG.Accent else Color(0xFF5A4636)
            val lay = tm.measure(pk.name, TextStyle(fontSize = 10.sp, color = col, fontWeight = if (pk == facing) FontWeight.Bold else FontWeight.Normal))
            val tl = Offset(p.x - lay.size.width / 2, p.y + dot + 1.dp.toPx())
            val box = androidx.compose.ui.geometry.Rect(tl, Size(lay.size.width.toFloat(), lay.size.height.toFloat()))
            // The peak's own dot doesn't count against its name.
            if (pk == facing || taken.none { it.overlaps(box) && !(it.center.x == p.x && it.center.y == p.y) }) {
                drawRect(Color.White.copy(alpha = 0.7f), tl, box.size)
                drawText(lay, topLeft = tl)
                taken += box
            }
        }
        // Where you're looking: a wedge pointing up.
        if (heading != null) {
            val wedge = Path().apply {
                moveTo(c.x, c.y)
                val half = Math.toRadians(20.0)
                lineTo(c.x + (sin(-half) * rMax).toFloat(), c.y - (cos(-half) * rMax).toFloat())
                lineTo(c.x + (sin(half) * rMax).toFloat(), c.y - (cos(half) * rMax).toFloat())
                close()
            }
            drawPath(wedge, TG.Accent.copy(alpha = 0.10f))
        }
        drawCircle(Color(0xFF1E88E5), 6.dp.toPx(), c)
        drawCircle(Color.White, 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    }
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
    LaunchedEffect(route.id) {
        val s = vm.campSpots(route)
        spots = s
        if (camps.isEmpty()) camps = TripDays.suggest(route, days, pace, s)
    }
    val waterAlong = remember(route.id, features) { vm.waterPlan(route.coords, 0.25 * Geo.M_PER_MI).stops.map { it.alongM } }
    FullScreen(onDismiss) {
        ScreenFrame(
            "Days and camps", route.name, onDismiss,
            actions = {
                if (route.camps.isNotEmpty()) TextButton(onClick = { vm.setCamps(route.id, emptyList()); onDismiss() }) { Text("Day hike", color = TG.Muted) }
            },
            bottom = {
                Button(
                    onClick = { vm.setCamps(route.id, if (days <= 1) emptyList() else camps); onDismiss() },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
                ) { Text("Save ${if (days <= 1) "as a day hike" else "$days days"}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            },
        ) {
            DaysContent(
                route, pace, days, onDays = { n -> days = n.coerceIn(1, 10); camps = TripDays.suggest(route, days, pace, spots.orEmpty()) },
                camps = camps, onCamps = { camps = it }, spots = spots,
                waterNear = { la, lo -> features?.waterNear(la, lo, Search.CAMP_WATER_M) }, waterAlong = waterAlong,
            )
        }
    }
}

@Composable
fun DaysContent(
    route: SavedRoute, pace: Pace, days: Int, onDays: (Int) -> Unit,
    camps: List<Camp>, onCamps: (List<Camp>) -> Unit, spots: List<CampSpot>?,
    waterNear: (Double, Double) -> Pair<Features.Point, Double>?, waterAlong: List<Double>,
) {
    val total = route.stats.distanceM
    val tripDays = remember(camps, pace) { TripDays.days(route, camps, pace) }
    var choosing by remember { mutableStateOf<Int?>(null) }

    /** Puts camp [i] at [along], kept between its neighbours; when [snap], onto a campsite or water within about 500 ft. */
    fun placeCamp(i: Int, along: Double, snap: Boolean) {
        val lo = (camps.getOrNull(i - 1)?.alongM ?: 0.0) + 200
        val hi = (camps.getOrNull(i + 1)?.alongM ?: total) - 200
        if (hi <= lo) return
        val a = along.coerceIn(lo, hi)
        val s = if (snap) spots.orEmpty().filter { abs(it.alongM - a) < 150 && it.alongM in lo..hi }.minWithOrNull(compareBy({ it.tier }, { abs(it.alongM - a) })) else null
        onCamps(camps.toMutableList().also { it[i] = s?.let { sp -> Camp(sp.alongM, sp.point.label) } ?: Camp(a, null) })
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Days", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onDays(days - 1); choosing = null }, enabled = days > 1, shape = RoundedCornerShape(10.dp)) { Text("−", fontSize = 18.sp, color = TG.Ink) }
        Text("$days", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TG.Ink, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = { onDays(days + 1); choosing = null }, enabled = days < 10, shape = RoundedCornerShape(10.dp)) { Text("+", fontSize = 18.sp, color = TG.Ink) }
    }
    Text("Camps start where each day takes about the same time at your pace, moved to a campsite (with water within 1,000 ft if possible) " +
        "or water when one is close. Drag a camp along the profile to move it.", fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp,
        modifier = Modifier.padding(top = 4.dp))
    if (spots == null) Text("Looking for campsites and water…", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))

    if (route.stats.profile.size >= 2) {
        DaysProfile(route, camps, spots.orEmpty(), Modifier.padding(top = 10.dp),
            onDrag = { i, a -> placeCamp(i, a, snap = false) }, onDrop = { i, a -> placeCamp(i, a, snap = true) })
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(CampBrown, CircleShape)); Text("campsite", fontSize = 11.sp, color = TG.Muted)
            Box(Modifier.size(9.dp).background(WaterBlue, CircleShape)); Text("water", fontSize = 11.sp, color = TG.Muted)
        }
    }

    tripDays.forEachIndexed { i, d ->
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7F8F4), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).background(TG.Accent, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${d.day}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Day ${d.day}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text(Pace.format(d.minutes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                }
                Text("${mi(d.distanceM)} · +${ft(d.gainM)} / −${ft(d.lossM)} · miles %.1f–%.1f".format(d.fromM / Geo.M_PER_MI, d.toM / Geo.M_PER_MI),
                    fontSize = 13.sp, color = TG.Ink, modifier = Modifier.padding(top = 4.dp))
                val water = waterAlong.count { it >= d.fromM && it < d.toM }
                Text(if (water == 0) "No mapped water within ¼ mi of the trail" else "$water water source${if (water == 1) "" else "s"} within ¼ mi",
                    fontSize = 12.sp, color = if (water == 0) Amber else TG.Muted)
                if (d.endsAtCamp) {
                    val c = camps[i]
                    val p = RouteStore.pointAt(route.coords, c.alongM)
                    HorizontalDivider(color = TG.Line, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Night ${i + 1}: " + (c.name ?: "mile %.1f (no campsite mapped here)".format(c.alongM / Geo.M_PER_MI)),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                    val w = p?.let { waterNear(it[0], it[1]) }
                    Text(w?.let { (wp, dist) -> "Water ${if (dist < 15) "right there" else Geo.formatDistance(dist)}: ${wp.label}" + if (wp.seasonal) " (may be seasonal)" else "" }
                        ?: "No mapped water within 1,000 ft of camp", fontSize = 13.sp, color = if (w == null || w.first.seasonal) Amber else WaterBlue)
                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(Icons.Filled.NightsStay, if (choosing == i) "Hide campsites" else "Campsites nearby") { choosing = if (choosing == i) null else i }
                        TextButton(onClick = { placeCamp(i, c.alongM - 0.5 * Geo.M_PER_MI, snap = true) }) { Text("◀ ½ mi", color = TG.Accent) }
                        TextButton(onClick = { placeCamp(i, c.alongM + 0.5 * Geo.M_PER_MI, snap = true) }) { Text("½ mi ▶", color = TG.Accent) }
                    }
                    if (choosing == i) {
                        val lo = camps.getOrNull(i - 1)?.alongM ?: 0.0
                        val hi = camps.getOrNull(i + 1)?.alongM ?: total
                        val near = TripDays.nearby(spots.orEmpty(), c.alongM, 8000.0).filter { it.alongM > lo + 200 && it.alongM < hi - 200 }.take(12)
                        if (near.isEmpty()) Text("No mapped campsites or water within 5 miles.", fontSize = 12.sp, color = TG.Muted)
                        near.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable { onCamps(camps.toMutableList().also { it[i] = Camp(s.alongM, s.point.label) }); choosing = null }
                                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(if (s.isCampsite) CampBrown else WaterBlue, CircleShape))
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(s.label, fontSize = 14.sp, color = TG.Ink, maxLines = 2)
                                    val delta = (s.alongM - c.alongM) / Geo.M_PER_MI
                                    Text("Mile %.1f (%s%.1f mi)".format(s.alongM / Geo.M_PER_MI, if (delta >= 0) "+" else "−", abs(delta)) +
                                        if (s.offM > 30) " · ${Geo.formatDistance(s.offM)} off route" else "", fontSize = 12.sp, color = TG.Muted)
                                }
                            }
                        }
                    }
                } else {
                    Text("Out at the end of the route.", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    Text("Dispersed camping: at least 100 ft from water on national forest land, about 200 ft on BLM land.",
        fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 10.dp))
}

/** The route's elevation profile split into days, with campsites and water marked along the bottom. Camps can be dragged. */
@Composable
private fun DaysProfile(
    route: SavedRoute, camps: List<Camp>, spots: List<CampSpot>, modifier: Modifier,
    onDrag: (Int, Double) -> Unit, onDrop: (Int, Double) -> Unit,
) {
    val prof = route.stats.profile
    val total = route.stats.distanceM.coerceAtLeast(1.0)
    val tm = rememberTextMeasurer()
    val campsNow by rememberUpdatedState(camps)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val color = routeColor(route.color)
    Canvas(modifier.fillMaxWidth().height(190.dp).pointerInput(total) {
        detectDragGestures(
            onDragStart = { o ->
                val w = size.width.toDouble()
                dragging = campsNow.indices.minByOrNull { abs(campsNow[it].alongM / total * w - o.x) }
                    ?.takeIf { abs(campsNow[it].alongM / total * w - o.x) < 40.dp.toPx() }
            },
            onDragEnd = { dragging?.let { i -> drop(i, campsNow[i].alongM) }; dragging = null },
            onDragCancel = { dragging = null },
            onDrag = { change, _ ->
                dragging?.let { i -> change.consume(); drag(i, (change.position.x / size.width).toDouble() * total) }
            },
        )
    }) {
        val top = 26.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val h = bottom - top
        val minE = prof.minOf { it.second }
        val range = (prof.maxOf { it.second } - minE).coerceAtLeast(30.0)
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(e: Double) = (bottom - (e - minE) / range * (h - 6)).toFloat()
        // Alternate shading per day.
        val marks = listOf(0.0) + camps.map { it.alongM } + listOf(total)
        marks.zipWithNext().forEachIndexed { i, (a, b) ->
            if (i % 2 == 1) drawRect(Color(0xFFF1F3EC), Offset(x(a), top), Size(x(b) - x(a), h))
        }
        val path = Path()
        prof.forEachIndexed { i, (d, e) -> if (i == 0) path.moveTo(x(d), y(e)) else path.lineTo(x(d), y(e)) }
        val fill = Path().apply { addPath(path); lineTo(x(prof.last().first), bottom); lineTo(x(prof.first().first), bottom); close() }
        drawPath(fill, color.copy(alpha = 0.16f))
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        drawLine(TG.Line, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        // Campsites and water under the axis.
        spots.forEach { s ->
            val sx = x(s.alongM)
            drawCircle(if (s.isCampsite) CampBrown else WaterBlue, 3.5.dp.toPx(), Offset(sx, bottom + (if (s.isCampsite) 7 else 15).dp.toPx()))
        }
        // Camps: a line and a numbered marker.
        camps.forEachIndexed { i, c ->
            val cx = x(c.alongM)
            drawLine(TG.Accent, Offset(cx, top), Offset(cx, bottom), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            val r = if (dragging == i) 13.dp.toPx() else 11.dp.toPx()
            drawCircle(TG.Accent, r, Offset(cx, top - 2.dp.toPx()))
            val lay = tm.measure("${i + 1}", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White))
            drawText(lay, topLeft = Offset(cx - lay.size.width / 2, top - 2.dp.toPx() - lay.size.height / 2))
        }
        val st = TextStyle(fontSize = 10.sp, color = TG.Muted)
        drawText(tm, "0 mi", Offset(0f, size.height - 12.dp.toPx()), st)
        val endLay = tm.measure(mi(total), st)
        drawText(endLay, topLeft = Offset(size.width - endLay.size.width, size.height - 12.dp.toPx()))
        val hiLay = tm.measure("%,d ft".format(((minE + range) * Geo.FT_PER_M).roundToInt()), st)
        drawText(hiLay, topLeft = Offset(size.width - hiLay.size.width, top + 2.dp.toPx()))
    }
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
    val scope = rememberCoroutineScope()
    fun refresh() {
        loading = true; error = null
        scope.launch {
            vm.fetchWeather(key, spots).onSuccess { forecasts = it }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(key) { refresh() }
    FullScreen(onDismiss) {
        ScreenFrame("Weather", title, onDismiss, actions = {
            IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh the forecast", tint = TG.Accent) }
        }) {
            WeatherContent(forecasts, loading, error)
        }
    }
}

private data class DayCard(val name: String, val day: Weather.Period?, val night: Weather.Period?)

/** NWS periods come as day, night, day…; pair them into days (the first may be night only). */
private fun pairDays(periods: List<Weather.Period>): List<DayCard> {
    fun short(n: String) = when {
        n.startsWith("This") || n == "Today" -> "Today"
        n == "Tonight" || n == "Overnight" -> "Tonight"
        n.endsWith("day") && !n.contains(" ") -> n.take(3)
        else -> n.removeSuffix(" Night").take(9)
    }
    val out = ArrayList<DayCard>()
    var i = 0
    while (i < periods.size) {
        val p = periods[i]
        if (!p.isDay) { out += DayCard(short(p.name), null, p); i++; continue }
        val n = periods.getOrNull(i + 1)?.takeIf { !it.isDay }
        out += DayCard(short(p.name), p, n)
        i += if (n != null) 2 else 1
    }
    return out
}

/** A cloud with rain falling from it (the stock icons only have an umbrella). */
private val RainCloud: ImageVector by lazy {
    ImageVector.Builder("RainCloud", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
            moveTo(7f, 14.5f)
            horizontalLineTo(17.5f)
            arcTo(3f, 3f, 0f, false, false, 17.5f, 8.5f)
            arcTo(4.6f, 4.6f, 0f, false, false, 8.3f, 9.1f)
            arcTo(2.7f, 2.7f, 0f, false, false, 7f, 14.5f)
            close()
        }
        path(stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
            moveTo(8.8f, 17f); lineTo(7.8f, 20.5f)
            moveTo(12.8f, 17f); lineTo(11.8f, 20.5f)
            moveTo(16.8f, 17f); lineTo(15.8f, 20.5f)
        }
    }.build()
}

private fun weatherIcon(short: String, isDay: Boolean): Pair<ImageVector, Color> {
    val s = short.lowercase()
    return when {
        "thunder" in s -> Icons.Filled.Thunderstorm to Color(0xFF6A1B9A)
        "snow" in s || "flurr" in s || "sleet" in s || "ice" in s -> Icons.Filled.AcUnit to Color(0xFF3A8DC5)
        "rain" in s || "shower" in s || "drizzle" in s -> RainCloud to WaterBlue
        "fog" in s || "haze" in s || "smoke" in s -> Icons.Filled.Dehaze to Color(0xFF78909C)
        "partly" in s || "mostly sunny" in s || "mostly clear" in s -> (if (isDay) Icons.Filled.WbCloudy else Icons.Filled.NightsStay) to Color(0xFF8D9AA5)
        "cloud" in s || "overcast" in s -> Icons.Filled.Cloud to Color(0xFF78909C)
        "wind" in s || "breezy" in s || "blustery" in s -> Icons.Filled.Air to Color(0xFF607D8B)
        else -> if (isDay) Icons.Filled.WbSunny to Color(0xFFE0A100) else Icons.Filled.NightsStay to Color(0xFF3949AB)
    }
}

@Composable
fun WeatherContent(forecasts: List<Weather.Forecast>, loading: Boolean, error: String?) {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    val stamp = SimpleDateFormat("EEE h:mm a", Locale.US)
    if (loading) Text("Getting the forecast…", fontSize = 13.sp, color = TG.Muted)
    error?.let { Text(it + if (forecasts.isNotEmpty()) " Showing the last forecast." else "", fontSize = 13.sp, color = Amber) }
    if (forecasts.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            forecasts.forEachIndexed { i, f ->
                FilterChip(selected = tab == i, onClick = { tab = i; selected = 0 }, label = { Text(f.label, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
            }
        }
    }
    val f = forecasts.getOrNull(tab.coerceAtMost(forecasts.size - 1))
    if (f == null) {
        if (!loading && error == null) Text("No forecast yet.", fontSize = 14.sp, color = TG.Muted)
    } else {
        val adj = f.tempAdjustF ?: 0
        val sub = listOfNotNull(f.area?.let { "Near $it" }, f.spotEleM?.let { "your spot ${ft(it)}" }, f.gridEleM?.let { "forecast for ${ft(it)}" })
            .joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))
        f.tempAdjustF?.let { a ->
            Text(if (a < 0) "You'll be higher than the forecast area, so temperatures here are about ${-a}°F colder than the NWS figures."
                else "You'll be lower than the forecast area, so temperatures here are about ${a}°F warmer than the NWS figures.",
                fontSize = 12.sp, color = Amber, lineHeight = 16.sp)
        }
        Text("As of ${stamp.format(Date(f.fetchedAt))}", fontSize = 12.sp, color = TG.Muted)

        // A strip of days: icon, high and low, chance of rain or snow.
        val cards = pairDays(f.periods)
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.forEachIndexed { i, d ->
                val main = d.day ?: d.night!!
                val (icon, tint) = weatherIcon(main.short, main.isDay)
                val rain = listOfNotNull(d.day?.rainChance, d.night?.rainChance).maxOrNull()
                Column(
                    Modifier.width(76.dp).border(if (i == selected) 2.dp else 1.dp, if (i == selected) TG.Accent else TG.Line, RoundedCornerShape(12.dp))
                        .clickable { selected = i }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
                    Icon(icon, contentDescription = main.short, tint = tint, modifier = Modifier.padding(vertical = 6.dp).size(30.dp))
                    Text(d.day?.tempF?.let { "${it + adj}°" } ?: "–", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB4491A))
                    Text(d.night?.tempF?.let { "${it + adj}°" } ?: "–", fontSize = 14.sp, color = Color(0xFF1F4FB4))
                    Text(rain?.takeIf { it > 0 }?.let { "$it%" } ?: " ", fontSize = 12.sp, color = WaterBlue)
                }
            }
        }
        Text("High in orange, low in blue, chance of rain or snow in %. Tap a day for its forecast.", fontSize = 11.sp, color = TG.Muted,
            modifier = Modifier.padding(top = 4.dp))

        cards.getOrNull(selected)?.let { d ->
            listOfNotNull(d.day, d.night).forEach { p ->
                val (icon, tint) = weatherIcon(p.short, p.isDay)
                HorizontalDivider(color = TG.Line, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.weight(1f))
                            p.tempF?.let {
                                Text("${if (p.isDay) "High" else "Low"} ${it + adj}°F", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (p.isDay) Color(0xFFB4491A) else Color(0xFF1F4FB4))
                            }
                        }
                        Text(listOfNotNull(p.short, p.rainChance?.takeIf { it > 0 }?.let { "rain/snow $it%" }, p.wind.takeIf { it.isNotBlank() }?.let { "wind $it" })
                            .joinToString(" · "), fontSize = 13.sp, color = TG.Muted)
                        Text(p.detail, fontSize = 14.sp, color = TG.Ink, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (f.tempAdjustF != null) Text("Temperatures shown are adjusted for your elevation; the full text is the NWS wording.", fontSize = 11.sp,
            color = TG.Muted, modifier = Modifier.padding(top = 10.dp))
    }
    Text("Forecast: National Weather Service.", fontSize = 11.sp, color = TG.Muted, modifier = Modifier.padding(top = 4.dp))
}
