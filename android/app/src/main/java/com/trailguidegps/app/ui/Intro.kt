package com.trailguidegps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class Tip(val icon: ImageVector, val title: String, val text: String)

private val TIPS = listOf(
    Tip(Icons.Filled.Terrain, "Your maps work offline",
        "Your maps are on your phone. The map, GPS, search, routes, water and peaks in view all work with no signal."),
    Tip(Icons.Filled.Route, "Plan a route",
        "Long-press anywhere to plan a route there along the trails. Or tap Route, then tap the map. To change a saved route later, tap Edit."),
    Tip(Icons.Filled.TouchApp, "Tap anything",
        "Tap a spring, campsite, trail or peak for details. Pull the card up for weather, peaks in view and links. Swipe the bottom panel down to see more map."),
    Tip(Icons.Filled.Layers, "Layers",
        "Pick Topo, Satellite or Hybrid, turn overlays on by section, or use a quick set like Hiking. You can save your own sets."),
    Tip(Icons.Filled.Sos, "In an emergency",
        "The red SOS button shows your coordinates in large type to read to 911. Many phones can't text 911 by satellite, so check yours, and take a satellite messenger on remote trips."),
    Tip(Icons.Filled.WbSunny, "Night and bright sun",
        "Settings has dark mode for night and the tent, and sunlight mode with bolder lines and bigger text for reading in bright sun."),
)

/** A few quick tips the first time the app opens (and from Settings). */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    FullScreen(onDone) {
        IntroContent(onDone)
    }
}

@Composable
fun IntroContent(onDone: () -> Unit, startPage: Int = 0) {
    val pager = rememberPagerState(initialPage = startPage) { TIPS.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = TG.Surface) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip", color = TG.Muted) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val t = TIPS[page]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(112.dp).background(TG.Accent.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(t.icon, contentDescription = null, tint = TG.Accent, modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(t.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TG.Ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(t.text, fontSize = 17.sp, color = TG.Muted, textAlign = TextAlign.Center, lineHeight = 24.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(TIPS.size) { i ->
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .background(if (i == pager.currentPage) TG.Accent else TG.Line, CircleShape))
                }
            }
            val last = pager.currentPage == TIPS.size - 1
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TG.Accent, contentColor = Color.White),
            ) { Text(if (last) "Start" else "Next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
