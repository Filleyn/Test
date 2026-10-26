package com.trailguidegps.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.geo.Geo
import kotlin.math.roundToInt

/**
 * One-tap emergency location: large, high-contrast coordinates to read to 911
 * or search and rescue. Keeps the screen on at full brightness while open.
 */
@Composable
fun EmergencyScreen(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        // Light status bar icons on this dark screen.
        val insets = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        insets?.isAppearanceLightStatusBars = false
        insets?.isAppearanceLightNavigationBars = false
        onDispose {
            insets?.isAppearanceLightStatusBars = true
            insets?.isAppearanceLightNavigationBars = true
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val f = fix
    val near = f?.let { features?.describe(it.lat, it.lon) }
    val shareText = f?.let {
        val (la, lo) = Geo.decimal(it.lat, it.lon)
        val u = Geo.utm(it.lat, it.lon)
        buildString {
            append("My location: $la, $lo")
            append(" (${String.format("%.5f, %.5f", it.lat, it.lon)})")
            append(". UTM ${u.line1} ${u.line2}.")
            it.altitudeM?.let { a -> append(" Elevation ${Geo.formatFeet(a)}.") }
            near?.let { n -> append(" $n.") }
            append(" https://maps.google.com/?q=${String.format("%.5f,%.5f", it.lat, it.lon)}")
        }
    }

    Column(
        Modifier.fillMaxSize().background(TG.Night).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row {
            IconButton(onClick = onClose, modifier = Modifier.background(Color(0xFF262B29), RoundedCornerShape(22.dp))) {
                Icon(Icons.Filled.Close, contentDescription = "Close emergency screen", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text("Screen stays on · full brightness", fontSize = 13.sp, color = TG.NightMuted, modifier = Modifier.padding(top = 12.dp))
        }
        Column {
            Text("My location", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Read these to 911 or search and rescue", fontSize = 15.sp, color = TG.NightMuted)
        }

        if (f == null) {
            Card {
                Text("Waiting for GPS…", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Stay still with a clear view of the sky. GPS works with no cell signal.", fontSize = 15.sp, color = TG.NightMuted)
            }
        } else {
            val (la, lo) = Geo.decimal(f.lat, f.lon)
            val u = Geo.utm(f.lat, f.lon)
            Card {
                Label("Latitude, longitude")
                Text("$la\n$lo", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(Geo.dms(f.lat, f.lon), fontSize = 15.sp, color = Color(0xFFD3D8D5))
            }
            Card {
                Label("UTM")
                Text("${u.line1}\n${u.line2}", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1f)) {
                    Text(f.altitudeM?.let { Geo.formatFeet(it) } ?: "Unknown", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Elevation", fontSize = 13.sp, color = TG.NightMuted)
                }
                Card(Modifier.weight(1f)) {
                    Text(f.accuracyM?.let { "±${(it * Geo.FT_PER_M).roundToInt()} ft" } ?: "Unknown", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("GPS accuracy, ${f.ageSeconds}s ago", fontSize = 13.sp, color = TG.NightMuted)
                }
            }
            near?.let { Text("Near: $it", fontSize = 16.sp, color = Color(0xFFE7EBE9), lineHeight = 22.sp) }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
            colors = ButtonDefaults.buttonColors(containerColor = TG.Danger, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Call 911", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    shareText?.let {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", it)
                        runCatching { context.startActivity(i) }
                    }
                },
                enabled = shareText != null,
                border = BorderStroke(1.dp, Color(0xFF4A514D)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Text location", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = {
                    shareText?.let {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("My location", it))
                        Toast.makeText(context, "Location copied", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = shareText != null,
                border = BorderStroke(1.dp, Color(0xFF4A514D)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Copy", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            "No bars? 911 can connect through any carrier's tower. Try higher ground. This phone does not support satellite SOS.",
            fontSize = 13.sp, color = TG.NightMuted, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(TG.NightCard, RoundedCornerShape(16.dp))
            .border(1.dp, TG.NightLine, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = TG.NightMuted)
}
