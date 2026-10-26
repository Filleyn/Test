package com.trailguidegps.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.data.WAYPOINT_COLORS
import com.trailguidegps.app.data.Waypoint
import com.trailguidegps.app.geo.Geo

private val colorNames = mapOf(
    "#B4491A" to "Orange", "#1A73E8" to "Blue", "#2E8B57" to "Green", "#7B3FA0" to "Purple", "#C62828" to "Red",
)

@Composable
fun WaypointEditor(
    waypoint: Waypoint,
    isNew: Boolean,
    onSave: (Waypoint) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(waypoint.id) { mutableStateOf(waypoint.name) }
    var note by remember(waypoint.id) { mutableStateOf(waypoint.note) }
    var color by remember(waypoint.id) { mutableStateOf(waypoint.color) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${waypoint.name}\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New waypoint" else "Edit waypoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WAYPOINT_COLORS.forEach { c ->
                        val chosen = c == color
                        Row(
                            Modifier.size(36.dp)
                                .background(Color(android.graphics.Color.parseColor(c)), CircleShape)
                                .border(if (chosen) BorderStroke(3.dp, TG.Ink) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                                .clickable { color = c }
                                .semantics { contentDescription = (colorNames[c] ?: c) + if (chosen) ", selected" else "" },
                        ) {}
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                val (la, lo) = Geo.decimal(waypoint.lat, waypoint.lon)
                Text("$la, $lo" + (waypoint.eleM?.let { " · ${Geo.formatFeet(it)}" } ?: ""), fontSize = 13.sp, color = TG.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(waypoint.copy(name = name.ifBlank { waypoint.name }, note = note.trim(), color = color)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = TG.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun StopRecordingDialog(defaultName: String, onSave: (String) -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this track?") },
            text = { Text("The recorded path will be deleted.") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop recording") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Track name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save track") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDiscard = true }) { Text("Discard", color = TG.Danger) }
                TextButton(onClick = onDismiss) { Text("Keep recording") }
            }
        },
    )
}
