package com.trailguidegps.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/**
 * Photos attached to waypoints and trail notes, kept in the app's own folder as JPEGs
 * shrunk to 1,600 px on the long side (a few hundred KB each), turned upright.
 */
class PhotoStore(context: Context) {
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun file(name: String) = File(dir, name)

    /** Copies a picked or just-taken photo in, shrunk. Returns its file name, or null if it couldn't be read. */
    fun add(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val scale = minOf(1f, MAX_PX.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        file(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (bmp !== raw) raw.recycle()
        bmp.recycle()
        name
    }.getOrNull()

    fun delete(names: List<String>) = names.forEach { if (SAFE.matches(it)) file(it).delete() }

    /** A small version for cards and lists. */
    fun thumbnail(name: String, px: Int = 320): Bitmap? = runCatching {
        val f = file(name)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    companion object {
        const val MAX_PX = 1600
        val SAFE = Regex("^[A-Za-z0-9-]{1,60}\\.jpg$")
    }
}
