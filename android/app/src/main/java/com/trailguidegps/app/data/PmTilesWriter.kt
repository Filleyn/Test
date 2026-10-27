package com.trailguidegps.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/** One map tile in the usual web-map (XYZ) numbering. */
data class TileXyz(val z: Int, val x: Int, val y: Int)

/**
 * Writes a PMTiles v3 archive from tile files on disk, so a downloaded
 * satellite area becomes one file MapLibre can show offline. No Android
 * dependencies, so it can be tested on a computer.
 */
object PmTilesWriter {
    const val TYPE_PNG = 2
    const val TYPE_JPEG = 3
    private const val HEADER = 127
    private const val ROOT_MAX = 16_384 - HEADER

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    /** Writes [tiles] (each with the file holding its image) to [out]. Bounds are degrees. */
    fun write(
        out: File, tiles: List<Pair<TileXyz, File>>, tileType: Int,
        west: Double, south: Double, east: Double, north: Double, name: String, attribution: String,
    ) {
        require(tiles.isNotEmpty()) { "No tiles to write" }
        val sorted = tiles.map { (t, f) -> Triple(zxyToTileId(t.z, t.x, t.y), t, f) }.sortedBy { it.first }
        val entries = ArrayList<Entry>(sorted.size)
        var offset = 0L
        for ((id, _, f) in sorted) {
            val len = f.length().toInt()
            entries += Entry(id, offset, len, 1)
            offset += len
        }
        val dataLength = offset

        // Root directory, plus leaf directories if everything doesn't fit in the first 16 KB.
        var root = gzip(encode(entries))
        var leaves = ByteArray(0)
        if (root.size > ROOT_MAX) {
            var leafSize = 4096
            while (true) {
                val leafBytes = ByteArrayOutputStream()
                val rootEntries = ArrayList<Entry>()
                for (chunk in entries.chunked(leafSize)) {
                    val enc = gzip(encode(chunk))
                    rootEntries += Entry(chunk.first().tileId, leafBytes.size().toLong(), enc.size, 0)
                    leafBytes.write(enc)
                }
                root = gzip(encode(rootEntries))
                if (root.size <= ROOT_MAX) { leaves = leafBytes.toByteArray(); break }
                leafSize *= 2
            }
        }
        val meta = gzip("""{"name":${jsonString(name)},"attribution":${jsonString(attribution)},"type":"baselayer","format":"${if (tileType == TYPE_PNG) "png" else "jpg"}"}""".toByteArray())

        val rootOffset = HEADER.toLong()
        val metaOffset = rootOffset + root.size
        val leafOffset = metaOffset + meta.size
        val dataOffset = leafOffset + leaves.size
        val zooms = sorted.map { it.second.z }
        val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()).put(3.toByte())
        h.putLong(rootOffset).putLong(root.size.toLong())
        h.putLong(metaOffset).putLong(meta.size.toLong())
        h.putLong(leafOffset).putLong(leaves.size.toLong())
        h.putLong(dataOffset).putLong(dataLength)
        h.putLong(entries.size.toLong()).putLong(entries.size.toLong()).putLong(entries.size.toLong())
        h.put(1.toByte())          // clustered: tiles are in tile-id order
        h.put(2.toByte())          // directories are gzipped
        h.put(1.toByte())          // tiles themselves aren't compressed (JPEG already is)
        h.put(tileType.toByte())
        h.put(zooms.min().toByte()).put(zooms.max().toByte())
        h.putInt(e7(west)).putInt(e7(south)).putInt(e7(east)).putInt(e7(north))
        h.put(zooms.max().toByte()).putInt(e7((west + east) / 2)).putInt(e7((south + north) / 2))

        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).buffered(1 shl 16).use { os ->
            os.write(h.array())
            os.write(root)
            os.write(meta)
            os.write(leaves)
            for ((_, _, f) in sorted) f.inputStream().use { it.copyTo(os) }
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "Couldn't save ${out.name}" }
    }

    private fun e7(v: Double) = Math.round(v * 1e7).toInt()

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    private fun encode(entries: List<Entry>): ByteArray {
        val o = ByteArrayOutputStream()
        fun varint(v0: Long) {
            var v = v0
            while (v >= 0x80) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            o.write(v.toInt())
        }
        varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(e.tileId - last); last = e.tileId }
        for (e in entries) varint(e.runLength.toLong())
        for (e in entries) varint(e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            val prev = if (i > 0) entries[i - 1] else null
            if (prev != null && e.offset == prev.offset + prev.length) varint(0) else varint(e.offset + 1)
        }
        return o.toByteArray()
    }

    /** PMTiles tile id (Hilbert curve order), same as the reader. */
    fun zxyToTileId(z: Int, x0: Int, y0: Int): Long {
        var acc = ((1L shl (z * 2)) - 1) / 3
        var x = x0.toLong()
        var y = y0.toLong()
        var a = z - 1
        while (a >= 0) {
            val s = 1L shl a
            val rx = s and x
            val ry = s and y
            acc += ((3 * rx) xor ry) shl a
            if (ry == 0L) {
                if (rx != 0L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val t = x; x = y; y = t
            }
            a--
        }
        return acc
    }

    // ---- Tile maths for choosing what to download ----

    fun lonToX(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1 - asinh(tan(r)) / Math.PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun xToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360 - 180

    fun yToLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(Math.PI * (1 - 2.0 * y / (1 shl z)))))

    /** Every tile covering the box, zoom [minZ] to [maxZ]. */
    fun tilesFor(west: Double, south: Double, east: Double, north: Double, minZ: Int, maxZ: Int): List<TileXyz> {
        val out = ArrayList<TileXyz>()
        for (z in minZ..maxZ) {
            for (x in lonToX(west, z)..lonToX(east, z)) for (y in latToY(north, z)..latToY(south, z)) out += TileXyz(z, x, y)
        }
        return out
    }

    fun countFor(west: Double, south: Double, east: Double, north: Double, minZ: Int, maxZ: Int): Long =
        (minZ..maxZ).sumOf { z ->
            (lonToX(east, z) - lonToX(west, z) + 1).toLong() * (latToY(south, z) - latToY(north, z) + 1)
        }
}
