package com.trailguidegps.app.ui

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.PrintPlan
import com.trailguidegps.app.data.SavedRoute
import java.io.File

/** Print a topo map of a route at a true scale: pick the scale, make the PDF, then print it or share it. */
@Composable
fun PrintMapDialog(vm: AppViewModel, route: SavedRoute, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(PrintPlan.Scale.S24) }
    val counts = remember(route.id) { PrintPlan.Scale.entries.associateWith { PrintPlan.pages(route.coords, it.ratio).size } }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var made by remember { mutableStateOf<Pair<File, android.net.Uri>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Print a map") },
        text = {
            Column {
                Text(route.name, fontSize = 13.sp, color = TG.Muted, maxLines = 1)
                if (made == null) {
                    PrintPlan.Scale.entries.forEach { s ->
                        val n = counts[s] ?: 0
                        Row(Modifier.fillMaxWidth().clickable(enabled = progress == null) { scale = s }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = scale == s, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = TG.Accent),
                                modifier = Modifier.padding(end = 8.dp))
                            Column {
                                Text("${s.label} · $n page${if (n == 1) "" else "s"} + overview", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TG.Ink)
                                Text(s.note, fontSize = 12.sp, color = TG.Muted)
                            }
                        }
                    }
                    Text("Letter paper, from your downloaded maps: the route with a mark every mile, camps, a 1 km UTM grid, scale bars and declination. " +
                        "Print at actual size (100%) so the scale is true.", fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                progress?.let { (i, n) ->
                    Text(if (i == 0) "Drawing the overview…" else "Drawing page $i of $n…", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 10.dp))
                }
                made?.let { Text("Ready: ${it.first.name}", fontSize = 14.sp, color = TG.Ink, modifier = Modifier.padding(top = 6.dp)) }
                error?.let { Text(it, fontSize = 13.sp, color = TG.Danger, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            val m = made
            if (m == null) TextButton(enabled = progress == null && (counts[scale] ?: 0) in 1..PrintPlan.MAX_PAGES, onClick = {
                error = null
                progress = 0 to (counts[scale] ?: 0)
                vm.printMap(route, scale, onProgress = { i, n -> progress = i to n }) { result, message ->
                    progress = null
                    if (result != null) made = result else error = message
                }
            }) { Text("Make PDF", color = TG.Accent) }
            else TextButton(onClick = { printPdf(context, m.first, route.name); onDismiss() }) { Text("Print", color = TG.Accent) }
        },
        dismissButton = {
            val m = made
            if (m != null) TextButton(onClick = { ShareFile.send(context, m.second, "application/pdf", "Share map", "Map: ${route.name}"); onDismiss() }) {
                Text("Share PDF", color = TG.Accent)
            } else TextButton(enabled = progress == null, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Sends a finished PDF to Android's printing, on letter paper. */
fun printPdf(context: Context, file: File, name: String) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
        }
        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            runCatching {
                file.inputStream().use { i -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { o -> i.copyTo(o) } }
            }.onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }.onFailure { cb.onWriteFailed(it.message) }
        }
    }
    pm.print("TrailGuide map - $name", adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build())
}
