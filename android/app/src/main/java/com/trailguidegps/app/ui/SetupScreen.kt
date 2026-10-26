package com.trailguidegps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.DownloadState

/** First run: fetch the offline map files once, over Wi-Fi or data. */
@Composable
fun SetupScreen(vm: AppViewModel) {
    val state by vm.download.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Color(0xFFEFEEE2)).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TrailGuide GPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Download the offline maps for Cottonwood Canyon once. After that the app works with no signal: " +
                "topo map, trails, water sources and your GPS position.",
            fontSize = 16.sp, color = TG.Muted, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            is DownloadState.Downloading -> {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                Text("Downloading ${s.file}…", fontSize = 15.sp, color = TG.Ink)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = TG.Accent)
                Spacer(Modifier.height(6.dp))
                Text("%.1f of %.1f MB".format(s.done / 1048576.0, s.total / 1048576.0), fontSize = 13.sp, color = TG.Muted)
            }
            DownloadState.Checking -> Text("Finding the latest map files…", fontSize = 15.sp, color = TG.Ink)
            else -> {
                if (s is DownloadState.Error) {
                    Text(s.message, fontSize = 15.sp, color = TG.Danger)
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = { vm.downloadMaps(force = true) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (s is DownloadState.Error) "Try again" else "Download maps (about 12 MB)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
