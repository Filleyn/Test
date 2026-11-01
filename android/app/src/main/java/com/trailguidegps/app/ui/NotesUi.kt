package com.trailguidegps.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.PhotoStore
import com.trailguidegps.app.data.TrailNote
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A row of photo thumbnails. Tap one to see it full size; [onRemove] adds an x on each. */
@Composable
fun PhotoStrip(names: List<String>, store: PhotoStore, onOpen: (String) -> Unit, onRemove: ((String) -> Unit)? = null, size: Int = 72) {
    if (names.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n ->
            val bmp by produceState<Bitmap?>(null, n) { value = withContext(Dispatchers.IO) { store.thumbnail(n) } }
            Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(TG.Card).clickable { onOpen(n) }) {
                bmp?.let { Image(it.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                onRemove?.let { remove ->
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).background(Color(0x99000000), CircleShape)
                        .clickable { remove(n) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** Take a photo or pick one from the phone. [onAdded] gets the saved photo's name. */
@Composable
fun AddPhotoButtons(vm: AppViewModel, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCamera by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCamera
        if (ok && uri != null) scope.launch { vm.addPhoto(uri)?.let(onAdded) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.addPhoto(uri)?.let(onAdded) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(Icons.Filled.PhotoCamera, "Take photo") {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val f = java.io.File(dir, "shot-${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            pendingCamera = uri
            runCatching { camera.launch(uri) }
        }
        PillButton(Icons.Filled.PhotoLibrary, "From phone") {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/** One photo, full screen. */
@Composable
fun PhotoViewer(name: String, store: PhotoStore, onDismiss: () -> Unit) {
    FullScreen(onDismiss) {
        val bmp by produceState<Bitmap?>(null, name) {
            value = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(store.file(name).path) }.getOrNull() }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close photo", tint = Color.White)
            }
        }
    }
}

/** Add or change a trail note: what it says, photos, and its folder. */
@Composable
fun NoteEditor(
    vm: AppViewModel, note: TrailNote, isNew: Boolean,
    folders: List<com.trailguidegps.app.data.Folder>, folderId: String?,
    onSave: (TrailNote, String?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var text by remember(note.id) { mutableStateOf(note.text) }
    var photos by remember(note.id) { mutableStateOf(note.photos) }
    var added by remember(note.id) { mutableStateOf(emptyList<String>()) }
    var folder by remember(note.id) { mutableStateOf(folderId) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // Photos added and then cancelled aren't kept.
    val cancel = { vm.photos.delete(added); onDismiss() }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("Its photos are deleted too. This can't be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = TG.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(if (isNew) "New trail note" else "Edit trail note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note") }, placeholder = { Text("e.g. Blowdown across the trail, passable on foot") },
                )
                PhotoStrip(photos, vm.photos, onOpen = { viewing = it }, onRemove = { n -> photos = photos - n })
                AddPhotoButtons(vm) { n -> photos = photos + n; added = added + n }
                FolderPicker(folders, folder, { folder = it }, { vm.folders.create(it) })
                val (la, lo) = Geo.decimal(note.lat, note.lon)
                Text("$la, $lo · ${SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(note.createdAt))}", fontSize = 13.sp, color = TG.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Photos taken out are deleted from the phone when the note is saved.
                vm.photos.delete(note.photos.filter { it !in photos })
                onSave(note.copy(text = text.trim().ifBlank { "Trail note" }, photos = photos), folder)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = TG.Danger) }
                TextButton(onClick = cancel) { Text("Cancel") }
            }
        },
    )
    viewing?.let { PhotoViewer(it, vm.photos) { viewing = null } }
}
