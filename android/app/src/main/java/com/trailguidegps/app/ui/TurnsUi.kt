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
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.geo.Geo
import com.trailguidegps.app.route.TurnSheet

/** A route trail by trail: each change of trail with its mile and which way to turn. Tap one to see it on the map. */
@Composable
fun TurnsDialog(vm: AppViewModel, route: SavedRoute, onPick: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    var steps by remember(route.id) { mutableStateOf<List<TurnSheet.Step>?>(null) }
    LaunchedEffect(route.id) { steps = vm.turnSheet(route) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trail by trail") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(route.name, fontSize = 13.sp, color = TG.Muted, maxLines = 1)
                val s = steps
                when {
                    s == null -> Text("Matching the route to the trails…", fontSize = 15.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                    s.isEmpty() -> Text("This needs the trail network for the route's area. Download that state's maps, or check for map updates.",
                        fontSize = 14.sp, color = TG.Ink, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                    else -> {
                        s.forEach { st ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(st.lat, st.lon) }.padding(top = 10.dp)) {
                                Text("%.1f mi".format(st.atM / Geo.M_PER_MI), fontSize = 12.sp, color = TG.Muted)
                                Text("${st.turn} ${st.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, lineHeight = 19.sp)
                                Text("for %.1f mi".format(st.lengthM / Geo.M_PER_MI), fontSize = 12.sp, color = TG.Muted)
                            }
                        }
                        Text("Listed where the trail's name changes; junctions where you stay on the same trail aren't. Tap one to see it on the map. " +
                            "It's printed on the trip plan too.", fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TG.Accent) } },
    )
}
