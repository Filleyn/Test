package com.trailguidegps.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.data.Folder

/** "Folder: Mt Jefferson 2027 ▾", with a way to make a new folder on the spot. */
@Composable
fun FolderPicker(folders: List<Folder>, selected: String?, onSelect: (String?) -> Unit, onCreate: (String) -> Folder) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val current = folders.firstOrNull { it.id == selected }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = TG.Muted)
            Text("  " + (current?.name ?: "No folder") + "  ▾", color = TG.Ink, fontSize = 14.sp, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No folder") }, onClick = { open = false; onSelect(null) })
            folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; onSelect(f.id) }) }
            DropdownMenuItem(text = { Text("New folder…", color = TG.Accent) }, onClick = { open = false; naming = true })
        }
    }
    if (naming) NewFolderDialog(onDismiss = { naming = false }) { name -> naming = false; onSelect(onCreate(name).id) }
}

@Composable
fun NewFolderDialog(initial: String = "", title: String = "New folder", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Folder name, e.g. Mt Jefferson 2027") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
