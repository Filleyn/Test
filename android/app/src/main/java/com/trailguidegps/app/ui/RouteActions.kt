package com.trailguidegps.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel

/**
 * Everything you can do with a saved route, the same on the map's route card and in Saved.
 * A null action isn't offered in that place (moving to a folder is only in Saved).
 */
class RouteActions(
    val days: () -> Unit,
    val water: () -> Unit,
    val tripPlan: () -> Unit,
    val edit: () -> Unit,
    val weather: () -> Unit,
    val checklist: () -> Unit,
    val turns: () -> Unit,
    val dates: () -> Unit,
    val difficulty: (() -> Unit)? = null,
    val reverse: () -> Unit,
    val outBack: () -> Unit,
    val outAndBack: Boolean,
    val notes: () -> Unit,
    val rename: () -> Unit,
    val color: () -> Unit,
    val move: (() -> Unit)? = null,
    val print: () -> Unit,
    val watch: () -> Unit,
    val gpx: () -> Unit,
    val phone: () -> Unit,
) {
    /** The four used most, shown as buttons on the route card. */
    fun main() = listOf(
        Action(Icons.Filled.Hotel, "Days", onClick = days),
        Action(Icons.Filled.WaterDrop, "Water", onClick = water),
        Action(Icons.Filled.Description, "Trip plan", onClick = tripPlan),
        Action(Icons.Filled.Edit, "Edit", onClick = edit),
    )

    /** The rest, in groups. [withMain] adds the four main ones first, for Saved, which has no route card. */
    fun groups(withMain: Boolean): List<Pair<String, List<Action>>> = listOfNotNull(
        if (withMain) "Trip" to main() else null,
        "Plan" to listOfNotNull(
            Action(Icons.Filled.WbSunny, "Weather", onClick = weather),
            Action(Icons.Filled.TaskAlt, "Checklist", onClick = checklist),
            Action(Icons.Filled.FormatListNumbered, "Turns", onClick = turns),
            Action(Icons.Filled.Event, "Trip dates", onClick = dates),
            difficulty?.let { Action(Icons.Filled.Info, "Difficulty", onClick = it) },
        ),
        "Change" to listOfNotNull(
            Action(Icons.Filled.SwapHoriz, "Reverse", onClick = reverse),
            Action(Icons.Filled.UTurnLeft, if (outAndBack) "One way" else "Out & back", onClick = outBack),
            Action(Icons.Filled.StickyNote2, "Notes", onClick = notes),
            Action(Icons.Filled.DriveFileRenameOutline, "Rename", onClick = rename),
            Action(Icons.Filled.Palette, "Color", onClick = color),
            move?.let { Action(Icons.Filled.DriveFileMove, "Folder", onClick = it) },
        ),
        "Share & print" to listOf(
            Action(Icons.Filled.Print, "Print map", onClick = print),
            Action(Icons.Filled.Watch, "To watch", onClick = watch),
            Action(Icons.Filled.Upload, "Export GPX", onClick = gpx),
            Action(Icons.Filled.PhoneAndroid, "To a phone", onClick = phone),
        ),
    )
}

/** The route's actions, grouped, in one sheet. Picking one closes the sheet first. */
@Composable
fun RouteActionsSheet(routeName: String, actions: RouteActions, withMain: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(routeName, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.groups(withMain).forEach { (title, list) ->
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    ActionGrid(list.map { a -> a.copy(onClick = { onDismiss(); a.onClick() }) }, columns = 4)
                }
                Text("\"To a phone\" sends the route with its camps, notes and checklist to another phone with TrailGuide.",
                    fontSize = 11.sp, color = TG.Muted, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TG.Accent) } },
    )
}

/** Rename a route. */
@Composable
fun RouteRenameDialog(vm: AppViewModel, id: String, current: String, onDismiss: () -> Unit) {
    var name by remember(id) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename route") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.renameRoute(id, name.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a route's color. */
@Composable
fun RouteColorDialog(vm: AppViewModel, id: String, current: String, onDismiss: () -> Unit) {
    var color by remember(id) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route color") },
        text = { RouteColorPicker(color) { color = it } },
        confirmButton = { TextButton(onClick = { vm.setRouteColor(id, color); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
