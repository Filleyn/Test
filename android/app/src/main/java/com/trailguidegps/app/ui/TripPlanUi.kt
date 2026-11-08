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
    // Start and back from the route's trip dates when set; otherwise the next whole hour, and back
    // when the walking is done (the last day's, for a trip). Call for help 3 hours after that.
    val defaultStart = remember {
        route.tripStart.takeIf { it > 0 }
            ?: Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    val defaultBack = remember {
        route.tripEnd.takeIf { route.tripStart > 0 && it > route.tripStart } ?: com.trailguidegps.app.data.TripDates.defaultEnd(route, defaultStart, pace)
    }
    var party by remember { mutableStateOf(saved.party) }
    var phones by remember { mutableStateOf(saved.phones) }
    var vehicle by remember { mutableStateOf(saved.vehicle) }
    var parkedAt by remember { mutableStateOf(route.points.firstOrNull()?.waypointName ?: "") }
    var notes by remember { mutableStateOf(saved.notes) }
    var startAt by remember { mutableLongStateOf(defaultStart) }
    var backBy by remember { mutableLongStateOf(defaultBack) }
    var alarmAt by remember { mutableLongStateOf(defaultBack + 3 * 3_600_000L) }
    var busy by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

    fun pick(current: Long, onPicked: (Long) -> Unit) = pickDateTime(context, current, onPicked)

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
                // The times you settle on here become the route's trip dates.
                if (startAt != route.tripStart || backBy != route.tripEnd) vm.setTripDates(route.id, startAt, backBy)
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

/** A date, then a time. */
fun pickDateTime(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d ->
        cal.set(y, m, d)
        TimePickerDialog(context, { _, hh, mm ->
            cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm)
            onPicked(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

/** When you plan to do a route: start and finish. Feeds Days, water, the after-dark warning and the trip plan. */
@Composable
fun TripDatesDialog(route: SavedRoute, pace: com.trailguidegps.app.data.Pace, onSave: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val td = com.trailguidegps.app.data.TripDates
    var start by remember { mutableLongStateOf(route.tripStart.takeIf { it > 0 } ?: td.defaultStart()) }
    var end by remember { mutableLongStateOf(route.tripEnd.takeIf { route.tripStart > 0 && it > route.tripStart } ?: td.defaultEnd(route, start, pace)) }
    val fmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)
    @Composable
    fun Row2(label: String, value: Long, onChange: (Long) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = TG.Muted)
                Text(fmt.format(Date(value)), fontSize = 15.sp, color = TG.Ink)
            }
            TextButton(onClick = { pickDateTime(context, value, onChange) }) { Text("Change", color = TG.Accent) }
        }
    }
    val days = com.trailguidegps.app.data.TripDays.totalDays(route.camps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trip dates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(route.name, fontSize = 13.sp, color = TG.Muted, maxLines = 1)
                Row2("Start", start) { v -> end += v - start; start = v }
                Row2("Finish", end) { v -> end = maxOf(v, start) }
                val cover = td.calendarDays(start, end)
                if (cover != days) {
                    Text("These dates cover $cover day${if (cover == 1) "" else "s"}; the route is planned as " +
                        (if (days == 1) "a day hike" else "$days days") + ". Change the days in Days and camps, or the dates here.",
                        fontSize = 12.sp, color = TG.Warn, lineHeight = 16.sp)
                    TextButton(onClick = { end = td.defaultEnd(route, start, pace) }) { Text("Finish from the planned days", color = TG.Accent) }
                }
                Text("Used for each day's date, the weather to plan water for, the after-dark warning and the trip plan.",
                    fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(start, end) }) { Text("Save", color = TG.Accent) } },
        dismissButton = {
            Row {
                if (route.tripStart > 0) TextButton(onClick = { onSave(0, 0) }) { Text("Clear", color = TG.Muted) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = TG.Muted) }
            }
        },
    )
}
