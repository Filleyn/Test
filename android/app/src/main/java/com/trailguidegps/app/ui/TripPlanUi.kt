package com.trailguidegps.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.TripForm
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Fill in a trip plan for a route, then share it as a PDF by email or text. */
@Composable
fun TripPlanDialog(vm: AppViewModel, route: SavedRoute, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { TripForm.remembered(context) }
    val pace = vm.pace.value
    val estMin = pace.minutes(route.stats.distanceM, route.stats.gainM)
    // Default start: the next whole hour; back: start + the estimate; call for help 3 hours after that.
    val defaultStart = remember {
        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    var party by remember { mutableStateOf(saved.party) }
    var phones by remember { mutableStateOf(saved.phones) }
    var vehicle by remember { mutableStateOf(saved.vehicle) }
    var parkedAt by remember { mutableStateOf(route.points.firstOrNull()?.waypointName ?: "") }
    var notes by remember { mutableStateOf(saved.notes) }
    var startAt by remember { mutableLongStateOf(defaultStart) }
    var backBy by remember { mutableLongStateOf(defaultStart + estMin * 60_000L) }
    var alarmAt by remember { mutableLongStateOf(defaultStart + estMin * 60_000L + 3 * 3_600_000L) }
    var busy by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

    fun pick(current: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(context, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(context, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    @Composable
    fun TimeRow(label: String, value: Long, onChange: (Long) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = TG.Muted)
                Text(fmt.format(Date(value)), fontSize = 15.sp, color = TG.Ink)
            }
            TextButton(onClick = { pick(value, onChange) }) { Text("Change", color = TG.Accent) }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Trip plan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A one-page PDF for someone at home: your route on a map, when you'll be back, and when to call for help.",
                    fontSize = 13.sp, color = TG.Muted)
                OutlinedTextField(party, { party = it }, label = { Text("Who's going") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phones, { phones = it }, label = { Text("Phone numbers") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                TimeRow("Start", startAt) { v ->
                    val shift = v - startAt
                    startAt = v; backBy += shift; alarmAt += shift
                }
                TimeRow("Expected back", backBy) { v -> alarmAt += v - backBy; backBy = v }
                TimeRow("Call for help if no word by", alarmAt) { alarmAt = it }
                OutlinedTextField(vehicle, { vehicle = it }, label = { Text("Vehicle (color, make, plate)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(parkedAt, { parkedAt = it }, label = { Text("Parked at (trailhead)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes: camp plans, gear, alternate route") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                if (busy) Text("Making the PDF…", fontSize = 13.sp, color = TG.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                val form = TripForm(party.trim(), phones.trim(), startAt, backBy, alarmAt, vehicle.trim(), parkedAt.trim(), notes.trim())
                vm.makeTripPlan(route, form) { uri, subject, body ->
                    ShareFile.send(context, uri, "application/pdf", "Send trip plan", subject, body)
                    busy = false
                    onDismiss()
                }
            }) { Text("Make PDF and send") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
