package com.trailguidegps.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Checklist
import com.trailguidegps.app.data.ChecklistStore
import com.trailguidegps.app.data.SavedRoute

/** A packing list for a route: suggested from its nights, heat and water, then yours to tick, trim and add to. */
@Composable
fun ChecklistDialog(vm: AppViewModel, route: SavedRoute, onDismiss: () -> Unit) {
    var suggested by remember(route.id) { mutableStateOf<List<Checklist.Item>?>(null) }
    var state by remember(route.id) { mutableStateOf(vm.checklists.get(route.id)) }
    var newItem by remember { mutableStateOf("") }
    LaunchedEffect(route.id) { suggested = vm.checklistItems(route) }
    fun update(s: ChecklistStore.State) { state = s; vm.checklists.put(route.id, s) }

    val items = suggested?.let { list -> list.filter { it.id !in state.removed } + state.added.map { Checklist.Item(it.first, Checklist.YOURS, it.second) } }
    val packed = items?.count { it.id in state.checked } ?: 0
    FullScreen(onDismiss) {
        ScreenFrame("Checklist", route.name + (items?.let { " · $packed of ${it.size} packed" } ?: ""), onDismiss,
            actions = { if (state.checked.isNotEmpty()) TextButton(onClick = { update(state.copy(checked = emptySet())) }) { Text("Untick all", color = TG.Accent) } }) {
            if (items == null) { Text("Putting the list together…", fontSize = 15.sp, color = TG.Ink); return@ScreenFrame }
            Text("Suggested from this route's days, nights, heat and water. Tick things as you pack; what's ticked is listed on the trip plan.",
                fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
            Checklist.GROUPS.forEach { group ->
                val rows = items.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                Text(group, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                rows.forEach { item ->
                    val on = item.id in state.checked
                    Row(Modifier.fillMaxWidth().clickable { update(state.copy(checked = if (on) state.checked - item.id else state.checked + item.id)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = TG.Accent),
                            modifier = Modifier.padding(end = 10.dp, top = 8.dp, bottom = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.label, fontSize = 15.sp, color = if (on) TG.Muted else TG.Ink,
                                textDecoration = if (on) TextDecoration.LineThrough else null)
                            item.why?.let { Text(it, fontSize = 12.sp, color = TG.Muted, lineHeight = 15.sp) }
                        }
                        IconButton(onClick = {
                            update(if (group == Checklist.YOURS) state.copy(added = state.added.filter { it.first != item.id }, checked = state.checked - item.id)
                                else state.copy(removed = state.removed + item.id, checked = state.checked - item.id))
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Take ${item.label} off the list", tint = TG.Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newItem, { newItem = it }, placeholder = { Text("Add your own item", color = TG.Muted) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent), modifier = Modifier.weight(1f))
                TextButton(enabled = newItem.isNotBlank(), onClick = {
                    update(state.copy(added = state.added + ("x:${System.currentTimeMillis()}" to newItem.trim())))
                    newItem = ""
                }) { Text("Add", color = if (newItem.isNotBlank()) TG.Accent else TG.Muted) }
            }
            if (state.removed.isNotEmpty()) {
                TextButton(onClick = { update(state.copy(removed = emptySet())) }) {
                    Text("Put back ${state.removed.size} removed item" + (if (state.removed.size == 1) "" else "s"), color = TG.Accent)
                }
            }
            Text("A starting point, not a complete list: add what your trip, the season and the area's rules call for.",
                fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
