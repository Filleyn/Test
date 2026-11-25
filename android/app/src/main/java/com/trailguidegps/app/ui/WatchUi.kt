package com.trailguidegps.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel

/** Watch apps that open a GPX file as a course. Their package names are listed in the manifest's <queries>. */
object WatchApps {
    data class App(val name: String, val pkg: String, val after: String)

    val KNOWN = listOf(
        App("Garmin Connect", "com.garmin.android.apps.connectmobile",
            "It opens as a course in Garmin Connect. Save it there, then send it to your watch from the course's menu."),
        App("Garmin Explore", "com.garmin.android.apps.explore",
            "It opens in Garmin Explore. Sync to send it to your inReach or GPS."),
    )

    fun installed(context: Context): List<App> = KNOWN.filter { a ->
        runCatching { context.packageManager.getPackageInfo(a.pkg, 0); true }.getOrDefault(false)
    }

    /** Hands the file to that app: "Open with" first (what Garmin Connect expects), then share. False if neither took it. */
    fun open(context: Context, app: App, uri: Uri): Boolean {
        fun grant(i: Intent) = i.setPackage(app.pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { it.clipData = ClipData.newUri(context.contentResolver, "course", uri) }
        val tries = listOf(
            grant(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml")),
            grant(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")),
            grant(Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)),
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return true
        }
        return false
    }
}

/** Send a route to a watch: straight to Garmin Connect or Explore when installed, otherwise through the share sheet. */
@Composable
fun SendToWatchDialog(vm: AppViewModel, routeId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { WatchApps.installed(context) }
    fun course() = vm.watchCourse(routeId).also { if (it == null) vm.showMessage("Couldn't write the route file.") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to watch") },
        text = {
            Column {
                Text("Sends this route as a course (a GPX file).", fontSize = 14.sp, color = TG.Ink)
                apps.forEach { a ->
                    Column(Modifier.fillMaxWidth().clickable {
                        val c = course() ?: return@clickable
                        if (WatchApps.open(context, a, c.second)) vm.showMessage(a.after)
                        else ShareFile.send(context, c.second, "application/gpx+xml", "Send to watch")
                        onDismiss()
                    }.padding(vertical = 10.dp)) {
                        Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Accent)
                        Text(a.after, fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
                    }
                }
                Column(Modifier.fillMaxWidth().clickable {
                    val c = course() ?: return@clickable
                    ShareFile.send(context, c.second, "application/gpx+xml", "Send to watch", c.first)
                    onDismiss()
                }.padding(vertical = 10.dp)) {
                    Text(if (apps.isEmpty()) "Choose an app" else "Another app", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TG.Accent)
                    Text("COROS, Suunto, Polar and most watch apps take a GPX through the share sheet." +
                        (if (apps.isEmpty()) " For a Garmin watch, install Garmin Connect first." else ""),
                        fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TG.Accent) } },
    )
}
