package com.trailguidegps.app.ui

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val release by vm.release.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.checkRelease() }

    // Keep the screen on while a large download runs.
    val downloading = state is DownloadState.Downloading || state is DownloadState.Checking
    DisposableEffect(downloading) {
        val window = (context as? Activity)?.window
        if (downloading) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val size = release?.totalBytes?.let { formatSize(it) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFEFEEE2)).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TrailGuide GPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TG.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Download the offline Oregon maps once. After that the app works with no signal: " +
                "topo map, trails, water sources and your GPS position, anywhere in the state.",
            fontSize = 16.sp, color = TG.Muted, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            (size?.let { "Download size: $it. " } ?: "") + "Use Wi-Fi and keep the app open; an interrupted download resumes.",
            fontSize = 14.sp, color = TG.Muted, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            is DownloadState.Downloading -> {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                Text("Downloading ${s.file}…", fontSize = 15.sp, color = TG.Ink)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = TG.Accent)
                Spacer(Modifier.height(6.dp))
                Text("${formatSize(s.done)} of ${formatSize(s.total)}", fontSize = 13.sp, color = TG.Muted)
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
                ) {
                    Text(
                        when {
                            s is DownloadState.Error -> "Resume download"
                            size != null -> "Download Oregon maps ($size)"
                            else -> "Download Oregon maps"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
