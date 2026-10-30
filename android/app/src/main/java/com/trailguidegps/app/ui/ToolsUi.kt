package com.trailguidegps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.data.WaterNotes
import com.trailguidegps.app.geo.Geo
import com.trailguidegps.app.geo.Sun
import com.trailguidegps.app.location.Fix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

private val clock = SimpleDateFormat("h:mm a", Locale.US)

private fun miles(m: Double) = "%.1f mi".format(m / Geo.M_PER_MI)

/**
 * "Back about 7:40 PM, 1h 40m after sunset (5:58 PM)" when a hike of [minutes] started at
 * [startMs] would end after sunset there; null if it ends in daylight.
 */
fun afterDarkWarning(lat: Double, lon: Double, startMs: Long, minutes: Int): String? {
    val end = startMs + minutes * 60_000L
    val sun = Sun.day(lat, lon, startMs)
    val sunset = sun.sunset ?: return null
    if (end <= sunset) return null
    val late = ((end - sunset) / 60_000).toInt()
    return "Back about ${clock.format(Date(end))}, ${Pace.format(late)} after sunset (${clock.format(Date(sunset))})" +
        (sun.dusk?.let { if (end > it) ". Dark by ${clock.format(Date(it))}: bring a headlamp." else "." } ?: ".")
}

/** "Sunset 5:58 PM · 2h 14m of daylight left", or the morning / after-dark equivalent. */
fun daylightLine(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): String? {
    val d = Sun.day(lat, lon, now)
    val rise = d.sunrise ?: return null
    val set = d.sunset ?: return null
    return when {
        now < rise -> "Sunrise ${clock.format(Date(rise))} · sunset ${clock.format(Date(set))}"
        now < set -> "Sunset ${clock.format(Date(set))} · ${Pace.format(((set - now) / 60_000).toInt())} of daylight left"
        d.dusk != null && now < d.dusk -> "Sun set at ${clock.format(Date(set))} · dark by ${clock.format(Date(d.dusk))}"
        else -> "After dark · sunrise ${clock.format(Date(Sun.day(lat, lon, now + 86_400_000L).sunrise ?: rise + 86_400_000L))}"
    }
}

/** Water sources along a route by mile, and the longest stretches without water. */
@Composable
fun WaterAlongRouteDialog(
    vm: AppViewModel, title: String, coords: List<DoubleArray>, notes: Map<String, WaterNotes.Note>,
    onPick: (Double, Double) -> Unit, onDismiss: () -> Unit,
) {
    var maxOff by remember { mutableDoubleStateOf(0.25 * Geo.M_PER_MI) }
    val plan = remember(coords, maxOff) { vm.waterPlan(coords, maxOff) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Water along $title", maxLines = 2) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(500 / Geo.FT_PER_M to "500 ft", 0.25 * Geo.M_PER_MI to "¼ mi", 0.5 * Geo.M_PER_MI to "½ mi").forEach { (m, label) ->
                        FilterChip(selected = abs(maxOff - m) < 1, onClick = { maxOff = m }, label = { Text("Within $label") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White))
                    }
                }
                val dry = plan.longestDry
                val drySeasonal = plan.longestDryIfSeasonalDry
                if (dry != null) {
                    Text(
                        "Longest stretch without water: ${miles(dry.second - dry.first)} (miles %.1f–%.1f).".format(dry.first / Geo.M_PER_MI, dry.second / Geo.M_PER_MI),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.padding(top = 6.dp),
                    )
                    if (drySeasonal != null && drySeasonal.second - drySeasonal.first > dry.second - dry.first + 100) {
                        Text(
                            "If seasonal sources are dry: ${miles(drySeasonal.second - drySeasonal.first)} (miles %.1f–%.1f).".format(
                                drySeasonal.first / Geo.M_PER_MI, drySeasonal.second / Geo.M_PER_MI),
                            fontSize = 13.sp, color = Color(0xFF8A4B00),
                        )
                    }
                }
                Text("Map data can't say what's flowing. Check recent trip reports, and add notes when you visit.",
                    fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider(color = TG.Line)
                if (plan.stops.isEmpty()) {
                    Text("No mapped water this close to the route.", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(plan.stops, key = { "${it.point.lat},${it.point.lon},${it.point.kind}" }) { s ->
                        val p = s.point
                        Row(Modifier.fillMaxWidth().clickable { onPick(p.lat, p.lon) }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Text("Mile %.1f".format(s.alongM / Geo.M_PER_MI), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = TG.Accent, modifier = Modifier.width(66.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name ?: (Features.KIND_LABELS[p.kind] ?: p.kind), fontSize = 15.sp, color = TG.Ink, maxLines = 1)
                                val bits = listOfNotNull(
                                    if (p.name != null) Features.KIND_LABELS[p.kind] else null,
                                    if (p.seasonal) "seasonal" else null,
                                    if (s.offM < 30) "on the route" else "${Geo.formatDistance(s.offM)} off",
                                )
                                Text(bits.joinToString(" · "), fontSize = 12.sp, color = if (p.seasonal) Color(0xFF8A4B00) else TG.Muted)
                                p.osmId?.let { notes[it] }?.let { Text("Your note (${it.date}): ${it.text}", fontSize = 12.sp, color = TG.Ink, maxLines = 2) }
                            }
                        }
                        HorizontalDivider(color = TG.Line)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Heading toward one place: an arrow that turns with the phone, distance, bearing, and climb. */
@Composable
fun GoToPanel(target: AppViewModel.GoTo, fix: Fix?, heading: Float?, pace: Pace, elevationAt: (Double, Double) -> Double?, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFEFF4FB), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Go to ${target.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop", color = TG.Danger) }
        }
        if (fix == null) {
            Text("Waiting for GPS…", fontSize = 13.sp, color = TG.Muted)
        } else {
            val d = Geo.distanceM(fix.lat, fix.lon, target.lat, target.lon)
            val bearing = Geo.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(Color(0xFF1F4FB4), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Navigation, contentDescription = "Direction to go", tint = Color.White,
                        modifier = Modifier.size(36.dp).rotate(((bearing - (heading ?: 0f)) % 360).toFloat()),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (d < 25) "You're there" else Geo.formatDistance(d), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
                    Text("${bearing.roundToInt()}° ${Geo.compassPoint(bearing)} (true)", fontSize = 13.sp, color = TG.Ink)
                    val myEle = elevationAt(fix.lat, fix.lon)
                    val theirEle = elevationAt(target.lat, target.lon)
                    val climb = if (myEle != null && theirEle != null) theirEle - myEle else null
                    Text(
                        listOfNotNull(
                            climb?.let { (if (it >= 0) "↑ " else "↓ ") + Geo.formatFeet(abs(it)) },
                            "about ${Pace.format(pace.minutes(d, (climb ?: 0.0).coerceAtLeast(0.0)))} straight line",
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = TG.Muted,
                    )
                    if (heading == null) Text("Arrow points relative to north; hold the phone flat for the compass.", fontSize = 11.sp, color = TG.Muted)
                }
            }
        }
    }
}

/** Distance tool: total, last leg, and area if the points make a shape. */
@Composable
fun MeasurePanel(points: List<DoubleArray>, onUndo: () -> Unit, onClear: () -> Unit, onDone: () -> Unit) {
    Column {
        if (points.size < 2) {
            Text(if (points.isEmpty()) "Tap the map where you want to start measuring." else "Now tap the next point.",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
            Text("Straight-line distance between your taps, without making a route.", fontSize = 13.sp, color = TG.Muted)
        } else {
            val legs = points.zipWithNext { a, b -> Geo.distanceM(a[0], a[1], b[0], b[1]) }
            val total = legs.sum()
            val last = points.takeLast(2).let { (a, b) -> Geo.bearingDeg(a[0], a[1], b[0], b[1]) }
            Text(Geo.formatDistance(total), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
            Text(
                "${points.size - 1} leg${if (points.size > 2) "s" else ""} · last ${Geo.formatDistance(legs.last())} toward ${last.roundToInt()}° ${Geo.compassPoint(last)}" +
                    " · ${"%,d".format((total * Geo.FT_PER_M).roundToInt())} ft",
                fontSize = 13.sp, color = TG.Muted,
            )
            if (points.size >= 3) {
                // Area of the shape if you joined the last point back to the first (shoelace, flat-earth).
                val lat0 = points.map { it[0] }.average()
                val kx = cos(lat0 * PI / 180) * 111_320.0
                val ky = 110_540.0
                var a = 0.0
                for (i in points.indices) {
                    val p = points[i]
                    val q = points[(i + 1) % points.size]
                    a += p[1] * kx * q[0] * ky - q[1] * kx * p[0] * ky
                }
                val acres = abs(a) / 2 / 4046.86
                val closing = Geo.distanceM(points.last()[0], points.last()[1], points[0][0], points[0][1])
                Text("Area if closed: " + (if (acres >= 640) "%.2f sq mi".format(acres / 640) else "%,.1f acres".format(acres)) +
                    " · perimeter ${Geo.formatDistance(total + closing)}", fontSize = 13.sp, color = TG.Muted)
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onUndo, enabled = points.isNotEmpty(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Undo", color = TG.Ink)
            }
            OutlinedButton(onClick = onClear, enabled = points.isNotEmpty(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Clear", color = TG.Ink)
            }
            OutlinedButton(onClick = onDone, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Done", color = TG.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
