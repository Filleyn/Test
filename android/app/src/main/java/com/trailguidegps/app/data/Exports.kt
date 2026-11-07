package com.trailguidegps.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Copies a file the app made into the phone's Downloads/TrailGuide folder and returns
 * its link. Email apps upload attachments in the background and often can't read a
 * file from inside the app ("failed to download"); a file in Downloads is the same
 * kind they get from the Files or Photos app, so it attaches reliably. It also leaves
 * a copy you can find in Files.
 */
object Exports {
    private const val FOLDER = "Download/TrailGuide/"

    fun publish(context: Context, file: File, mime: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Replace the earlier copy of the same name (one this app saved) instead of adding "(1)".
        runCatching {
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(file.name, FOLDER), null)?.use { c ->
                while (c.moveToNext()) resolver.delete(android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Downloads didn't accept the file")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }.onFailure { android.util.Log.w("TrailGuide", "Couldn't save ${file.name} to Downloads", it) }.getOrNull()
}
