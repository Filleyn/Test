package com.trailguidegps.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.map.MapStyle

/** What each layer switch is called. */
val layerNames = linkedMapOf(
    "hillshade" to "Hillshade",
    "contours" to "Contour lines (40 ft)",
    "slope" to "Slope angle shading (zoom in)",
    "trails" to "Trails and tracks",
    "trailheads" to "Trailheads",
    "water" to "Water sources",
    "campsites" to "Campsites",
    "peaks" to "Peaks",
    "waterfalls" to "Waterfalls",
    "shelters" to "Shelters and huts",
    "lookouts" to "Fire lookouts",
    "history" to "Historic sites",
    "restrooms" to "Restrooms (when zoomed in)",
    "other" to "Viewpoints, hot springs, passes",
    "landmanager" to "Land manager (Forest Service, BLM, state…)",
    "forests" to "National forest and reservation boundaries",
    "wilderness" to "Wilderness and other special areas",
    "roadaccess" to "Forest road access (car, high clearance, 4WD)",
    "burns" to "Past wildfire burn areas (since 2000)",
    "fires" to "Active wildfires (updates when online)",
)

private data class Section(val id: String, val title: String, val groups: List<String>)

private val SECTIONS = listOf(
    Section("terrain", "Terrain", listOf("hillshade", "contours", "slope")),
    Section("trails", "Trails and water", listOf("trails", "trailheads", "water")),
    Section("places", "Places", listOf("campsites", "peaks", "waterfalls", "shelters", "lookouts", "history", "restrooms", "other")),
    Section("land", "Land and roads", listOf("landmanager", "forests", "wilderness", "roadaccess")),
    Section("hazards", "Hazards", listOf("burns", "fires")),
)

/** One-tap sets of overlays. The base map stays as it is. */
val LAYER_PRESETS: List<Pair<String, Set<String>>> = listOf(
    "Hiking" to setOf("hillshade", "contours", "trails", "water", "campsites", "trailheads", "peaks", "waterfalls", "shelters",
        "lookouts", "history", "restrooms", "other", "forests", "wilderness", "burns", "fires"),
    "Driving to the trailhead" to setOf("hillshade", "trails", "campsites", "trailheads", "peaks", "restrooms", "landmanager",
        "forests", "wilderness", "roadaccess", "fires"),
    "Snow and scrambling" to setOf("hillshade", "contours", "slope", "trails", "peaks", "shelters", "forests", "wilderness", "fires"),
    "Minimal" to setOf("hillshade", "trails", "water", "fires"),
)

/** The switchable overlays (everything but the base map). */
val OVERLAY_GROUPS: List<String> get() = layerNames.keys.toList()

/**
 * The top of the layers screen: the base map as picture buttons, quick sets, then the
 * overlays in sections that open and close. [footer] goes under the fires section.
 */
@Composable
fun LayersContent(
    base: String,
    onBase: (String) -> Unit,
    online: Boolean,
    satelliteAreaCount: Int,
    layerOn: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onPreset: (Set<String>) -> Unit,
    customPresets: List<Pair<String, Set<String>>>,
    onCustomPresets: (List<Pair<String, Set<String>>>) -> Unit,
    sectionOpen: (String) -> Boolean,
    onSection: (String, Boolean) -> Unit,
    firesFooter: @Composable () -> Unit,
) {
    Text("Base map", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val satNote = when {
            online -> "Online imagery"
            satelliteAreaCount > 0 -> "Saved areas only"
            else -> "Needs signal"
        }
        BaseCard("Topo", "Works offline", com.trailguidegps.app.R.drawable.base_topo,
            base == MapStyle.BASE_TOPO, { onBase(MapStyle.BASE_TOPO) }, Modifier.weight(1f))
        BaseCard("Satellite", satNote, com.trailguidegps.app.R.drawable.base_satellite,
            base == MapStyle.BASE_SATELLITE, { onBase(MapStyle.BASE_SATELLITE) }, Modifier.weight(1f))
        BaseCard("Hybrid", "Imagery + roads, names", com.trailguidegps.app.R.drawable.base_hybrid,
            base == MapStyle.BASE_HYBRID, { onBase(MapStyle.BASE_HYBRID) }, Modifier.weight(1f))
    }

    Text("Quick sets", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
    // Built-in sets, then your own (the pencil edits one), then "save what's on now".
    var editing by remember { mutableStateOf<Int?>(null) }
    var naming by remember { mutableStateOf(false) }
    val current = OVERLAY_GROUPS.filter { layerOn[it] == true }.toSet()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White,
            selectedTrailingIconColor = Color.White)
        LAYER_PRESETS.forEach { (label, set) ->
            FilterChip(selected = current == set, onClick = { onPreset(set) }, label = { Text(label) }, colors = chipColors)
        }
        customPresets.forEachIndexed { i, (label, set) ->
            FilterChip(
                selected = current == set, onClick = { onPreset(set) }, label = { Text(label) }, colors = chipColors,
                trailingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit $label", modifier = Modifier.size(18.dp).clickable { editing = i })
                },
            )
        }
        AssistChip(onClick = { naming = true }, label = { Text("Save current") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = TG.Accent, modifier = Modifier.size(18.dp)) })
    }
    if (naming) {
        PresetDialog("Save these layers as a quick set", "", canDelete = false,
            onSave = { name, _ -> onCustomPresets(customPresets + (name to current)); naming = false },
            onDelete = {}, onDismiss = { naming = false })
    }
    editing?.let { i ->
        customPresets.getOrNull(i)?.let { (name, set) ->
            PresetDialog("Edit quick set", name, canDelete = true,
                onSave = { newName, useCurrent ->
                    onCustomPresets(customPresets.toMutableList().also { it[i] = newName to (if (useCurrent) current else set) }); editing = null
                },
                onDelete = { onCustomPresets(customPresets.filterIndexed { j, _ -> j != i }); editing = null },
                onDismiss = { editing = null })
        }
    }

    Text("Overlays", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, modifier = Modifier.padding(top = 12.dp))
    SECTIONS.forEach { sec ->
        val open = sectionOpen(sec.id)
        val onCount = sec.groups.count { layerOn[it] == true }
        HorizontalDivider(color = TG.Line)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            // All on, all off, or some (a dash). Tapping turns them all on, or all off if they all were.
            TriStateCheckbox(
                state = when (onCount) { 0 -> ToggleableState.Off; sec.groups.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                onClick = { val on = onCount < sec.groups.size; sec.groups.forEach { g -> if ((layerOn[g] == true) != on) onToggle(g, on) } },
                colors = CheckboxDefaults.colors(checkedColor = TG.Accent),
                modifier = Modifier.semantics { contentDescription = "All ${sec.title} layers" },
            )
            Column(
                Modifier.weight(1f).clickable(role = Role.Button) { onSection(sec.id, !open) }.padding(vertical = 6.dp),
            ) {
                Text(sec.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                Text(if (onCount == 0) "All off" else if (onCount == sec.groups.size) "All on" else "$onCount of ${sec.groups.size} on",
                    fontSize = 12.sp, color = TG.Muted)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Close ${sec.title}" else "Open ${sec.title}",
                tint = TG.Muted, modifier = Modifier.size(48.dp).clickable { onSection(sec.id, !open) }.padding(12.dp))
        }
        if (open) {
            sec.groups.forEach { group ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(layerNames[group] ?: group, fontSize = 15.sp, color = TG.Ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(
                        checked = layerOn[group] == true,
                        onCheckedChange = { onToggle(group, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = TG.Accent),
                    )
                }
                if (layerOn[group] == true) Box(Modifier.padding(start = 8.dp)) { LayerLegend(group) }
            }
            if (sec.id == "hazards") firesFooter()
            if (base != MapStyle.BASE_TOPO && sec.id in setOf("terrain", "land")) {
                Text("Hillshade, slope and land manager shading show on the Topo base map.", fontSize = 12.sp, color = TG.Muted,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            }
        }
    }
    HorizontalDivider(color = TG.Line)
}

@Composable
private fun BaseCard(
    label: String, sub: String, picture: Int,
    selected: Boolean, onClick: () -> Unit, modifier: Modifier,
) {
    Column(
        modifier.clickable(role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) {
            this.selected = selected
            contentDescription = "$label base map"
        },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(12.dp)).background(TG.Card)
                .border(if (selected) 3.dp else 1.dp, if (selected) TG.Accent else TG.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // A sample spot (Green Lakes and Broken Top) drawn each way; built into the app so it shows offline.
            Image(androidx.compose.ui.res.painterResource(picture), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(TG.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) TG.Accent else TG.Ink, modifier = Modifier.padding(top = 4.dp))
        Text(sub, fontSize = 11.sp, color = TG.Muted, maxLines = 2, lineHeight = 14.sp)
    }
}

@Composable
private fun PresetDialog(
    title: String, initial: String, canDelete: Boolean,
    onSave: (String, Boolean) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var useCurrent by remember { mutableStateOf(!canDelete) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text("e.g. Elk season") })
                if (canDelete) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { useCurrent = !useCurrent }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useCurrent, onCheckedChange = { useCurrent = it }, colors = CheckboxDefaults.colors(checkedColor = TG.Accent))
                        Text("Replace its layers with the ones on now", fontSize = 14.sp, color = TG.Ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), useCurrent) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete", color = TG.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
