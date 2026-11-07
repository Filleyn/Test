package com.trailguidegps.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Free-text notes for a whole route: road to the trailhead, bugs, permits, water. Blank clears them. */
@Composable
fun RouteNotesDialog(routeName: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(routeName) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route notes") },
        text = {
            Column {
                Text(routeName, fontSize = 13.sp, color = TG.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Rough road the last 3 mi to the trailhead. Mosquitoes bad in July. Permit needed.", color = TG.Muted) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Text("Shown on the route and printed on its trip plan.", fontSize = 12.sp, color = TG.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = TG.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TG.Muted) } },
    )
}
