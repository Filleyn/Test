package com.trailguidegps.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Pace
import com.trailguidegps.app.geo.Geo
import com.trailguidegps.app.route.WayOut
import com.trailguidegps.app.route.WaysOut
import kotlin.math.roundToInt

/** The quickest ways out from a spot by trail: nearest trailheads and the nearest road. Tap one to make it a route. */
@Composable
fun WaysOutDialog(vm: AppViewModel, from: String, lat: Double, lon: Double, onShow: (WayOut) -> Unit, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<WaysOut.Result?>(null) }
    LaunchedEffect(lat, lon) { result = vm.waysOut(lat, lon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ways out") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("From $from, along trails, quickest first.", fontSize = 13.sp, color = TG.Muted)
                val r = result
                if (r == null) Text("Working out the ways out…", fontSize = 15.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                else {
                    r.ways.forEach { w -> WayOutRow(w, lat, lon) { onShow(w) } }
                    r.note?.let { Text(it, fontSize = 14.sp, color = TG.Ink, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp)) }
                    if (r.ways.isNotEmpty()) Text("Tap one to save it as a route and show it on the map; then you can Follow it. " +
                        "These come from the map data: a trailhead or road doesn't mean a car, phone signal or help is there.",
                        fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TG.Accent) } },
    )
}

@Composable
private fun WayOutRow(w: WayOut, lat: Double, lon: Double, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 10.dp)) {
        Text((if (w.road) "Road: " else "") + w.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 2)
        Text("%.1f mi · %s · +%,d ft −%,d ft · ends %s of here".format(
            w.stats.distanceM / Geo.M_PER_MI, Pace.format(w.minutes),
            (w.stats.gainM * Geo.FT_PER_M).roundToInt(), (w.stats.lossM * Geo.FT_PER_M).roundToInt(),
            Geo.compassPoint(Geo.bearingDeg(lat, lon, w.endLat, w.endLon))),
            fontSize = 13.sp, color = TG.Muted)
        val via = w.leg.names.filter { it.isNotBlank() }.distinct().take(3)
        if (via.isNotEmpty()) Text("Via " + via.joinToString(", "), fontSize = 12.sp, color = TG.Muted, maxLines = 1)
    }
}
