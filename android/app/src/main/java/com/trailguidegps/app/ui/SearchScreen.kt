package com.trailguidegps.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.Search
import com.trailguidegps.app.data.SearchResult
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Kind → dot color in result lists, matching the map icons. */
private val kindColors = mapOf(
    "spring" to 0xFF2E7DBF, "drinking_water" to 0xFF0D47A1, "water_point" to 0xFF00838F, "well" to 0xFF455A64,
    "campsite" to 0xFFB4491A, "trailhead" to 0xFF2E7D4F, "trail" to 0xFFB5462A, "peak" to 0xFF5A4636,
    "lake" to 0xFF4A8FC7, "town" to 0xFF23271F, "waterfall" to 0xFF1565C0, "hot_spring" to 0xFFC2185B,
    "shelter" to 0xFF6D4C41, "viewpoint" to 0xFF5E35B1, "toilets" to 0xFF00695C,
    "stream" to 0xFF3F88C5, "lookout" to 0xFFBF360C, "historic" to 0xFF6D4C41,
)

/** Offline search: trails, water sources and places from the downloaded maps. */
@Composable
fun SearchScreen(vm: AppViewModel, onClose: () -> Unit) {
    val features by vm.features.collectAsStateWithLifecycle()
    val notes by vm.waterNotes.notes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val kinds = Search.FILTERS.firstOrNull { it.first == filter }?.second
    var filtered by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var nearby by remember { mutableStateOf<List<Pair<String, List<SearchResult>>>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ref = remember { vm.searchReference() }
    BackHandler(onBack = onClose)

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Nearest water, campsites and trailheads when the box is empty.
    LaunchedEffect(features) {
        val f = features ?: return@LaunchedEffect
        val (lat, lon) = ref ?: return@LaunchedEffect
        nearby = withContext(Dispatchers.Default) {
            listOf(
                "Nearest water" to Search.nearest(f, Search.WATER_KINDS, lat, lon),
                "Nearest campsites" to Search.nearest(f, setOf("campsite"), lat, lon, n = 5),
                "Nearest trailheads" to Search.nearest(f, setOf("trailhead"), lat, lon, n = 5),
            ).filter { it.second.isNotEmpty() }
        }
    }

    // Search as you type, after a short pause.
    LaunchedEffect(query, features, filter) {
        val f = features ?: return@LaunchedEffect
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(150)
        val (lat, lon) = ref ?: (44.0 to -121.0)
        results = withContext(Dispatchers.Default) { Search.search(f, query, lat, lon, kinds) }
    }
    // With a filter and no words typed: the nearest of that type.
    LaunchedEffect(features, filter) {
        val f = features ?: return@LaunchedEffect
        val k = kinds ?: run { filtered = emptyList(); return@LaunchedEffect }
        val (lat, lon) = ref ?: return@LaunchedEffect
        filtered = withContext(Dispatchers.Default) {
            if (k == setOf("trail")) emptyList() else Search.nearest(f, k, lat, lon, n = 25, maxM = 80_000.0)
        }
    }

    fun open(r: SearchResult) {
        keyboard?.hide()
        vm.openResult(r)
        onClose()
    }

    Column(Modifier.fillMaxSize().background(TG.Surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to map", tint = TG.Ink) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Trail, spring, lake, peak, town") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        // Filter by type.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Search.FILTERS.forEach { (label, _) ->
                FilterChip(
                    selected = filter == label, onClick = { filter = label }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TG.Accent, selectedLabelColor = Color.White),
                )
            }
        }

        when {
            features == null -> Message("Loading the offline index…")
            query.isBlank() && kinds != null -> {
                if (filtered.isEmpty()) {
                    Message(if (kinds == setOf("trail")) "Type part of a trail's name." else if (ref == null) "Waiting for GPS to list the nearest ones." else "None within 50 miles.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "h-filter") { Header("Nearest ${filter.lowercase()}") }
                        items(filtered, key = { "f-${it.kind}-${it.lat}-${it.lon}-${it.name}" }) { r ->
                            ResultRow(r, notes[r.point?.osmId]?.let { "Your note: ${it.text}" }) { open(r) }
                            HorizontalDivider(color = TG.Line)
                        }
                    }
                }
            }
            query.isBlank() -> {
                if (nearby.isEmpty()) {
                    Message(if (ref == null) "Search by name. Once GPS has a fix, the nearest water and campsites show here." else "Search by name: trails, springs, lakes, peaks, campsites, trailheads and towns.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        nearby.forEach { (title, list) ->
                            item(key = "h-$title") { Header(title) }
                            items(list, key = { "$title-${it.lat}-${it.lon}-${it.name}" }) { r ->
                                ResultRow(r, notes[r.point?.osmId]?.let { "Your note: ${it.text}" }) { open(r) }
                                HorizontalDivider(color = TG.Line)
                            }
                        }
                    }
                }
            }
            results.isEmpty() -> Message(
                if (kinds != null) "No ${filter.lowercase()} match \"$query\". Try the All filter."
                else "No matches for \"$query\" in your maps. Try part of the name, like \"pacific\" or \"spring\"."
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { "${it.kind}-${it.name}-${it.lat}-${it.lon}" }) { r ->
                    ResultRow(r, notes[r.point?.osmId]?.let { "Your note: ${it.text}" }) { open(r) }
                    HorizontalDivider(color = TG.Line)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TG.Muted, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Message(text: String) {
    Text(text, fontSize = 15.sp, color = TG.Muted, lineHeight = 21.sp, modifier = Modifier.padding(24.dp))
}

@Composable
private fun ResultRow(r: SearchResult, note: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Color(kindColors[r.kind] ?: 0xFF6B716A), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink, maxLines = 1)
            val parts = mutableListOf(Features.KIND_LABELS[r.kind] ?: r.kind)
            if (r.point?.seasonal == true) parts += "seasonal"
            r.near?.let { parts += it }
            Text(parts.joinToString(" · "), fontSize = 13.sp, color = TG.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.detail?.let { Text(it, fontSize = 13.sp, color = TG.WaterText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            note?.let { Text(it, fontSize = 13.sp, color = TG.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val ref = r.distanceM
        Column(horizontalAlignment = Alignment.End) {
            Text(Geo.formatDistance(ref), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
        }
    }
}
