package com.trailguidegps.app.data

import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Elevation anywhere in the map area, read from the terrain-RGB tiles in
 * terrain.pmtiles (USGS 3DEP, about 90 ft per pixel at the zoom used).
 * Elevation in metres = -10000 + (R * 65536 + G * 256 + B) * 0.1.
 */
class Terrain(file: File) {
    private val pm = PmTiles(file)
    private val zoom = pm.maxZoom
    private val missing = IntArray(0)
    private val tiles = object : LruCache<Long, IntArray>(48) {}

    private fun pixels(x: Int, y: Int): IntArray? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        tiles.get(key)?.let { return if (it === missing) null else it }
        val bytes = pm.tile(zoom, x, y)
        val px = bytes?.let { b ->
            BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bmp ->
                IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height); bmp.recycle() }
            }
        }
        tiles.put(key, px ?: missing)
        return px
    }

    /** Elevation in metres, or null outside the map area. */
    @Synchronized
    fun elevationM(lat: Double, lon: Double): Double? {
        val n = 1 shl zoom
        val xf = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val yf = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        val tx = floor(xf).toInt()
        val ty = floor(yf).toInt()
        if (tx < 0 || ty < 0 || tx >= n || ty >= n) return null
        val px = pixels(tx, ty) ?: return null
        val size = kotlin.math.sqrt(px.size.toDouble()).toInt()
        val ix = ((xf - tx) * size).toInt().coerceIn(0, size - 1)
        val iy = ((yf - ty) * size).toInt().coerceIn(0, size - 1)
        val c = px[iy * size + ix]
        val v = ((c shr 16) and 0xff) * 65536 + ((c shr 8) and 0xff) * 256 + (c and 0xff)
        val e = -10000.0 + v * 0.1
        return if (e < -500) null else e
    }

    fun close() = pm.close()
}
