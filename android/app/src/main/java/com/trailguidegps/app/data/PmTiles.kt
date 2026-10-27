package com.trailguidegps.app.data

import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Minimal PMTiles v3 reader: finds and returns one tile's bytes from a local
 * archive. MapLibre reads the map tiles itself; the app uses this to read
 * elevation from terrain.pmtiles.
 */
class PmTiles(file: File) : Closeable {
    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private val raf = RandomAccessFile(file, "r")
    private val rootOffset: Long
    private val rootLength: Long
    private val leafOffset: Long
    private val tileDataOffset: Long
    private val internalCompression: Int
    val minZoom: Int
    val maxZoom: Int
    private val root: List<Entry>
    private val leaves = LruCache<Long, List<Entry>>(64)

    init {
        val h = read(0, 127).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(7).also { h.get(it) }
        require(String(magic) == "PMTiles") { "Not a PMTiles file" }
        rootOffset = h.getLong(8)
        rootLength = h.getLong(16)
        leafOffset = h.getLong(40)
        tileDataOffset = h.getLong(56)
        internalCompression = h.get(97).toInt() and 0xff
        minZoom = h.get(100).toInt() and 0xff
        maxZoom = h.get(101).toInt() and 0xff
        root = readDirectory(rootOffset, rootLength)
    }

    @Synchronized
    private fun read(offset: Long, length: Int): ByteBuffer {
        val b = ByteArray(length)
        raf.seek(offset)
        raf.readFully(b)
        return ByteBuffer.wrap(b)
    }

    private fun readDirectory(offset: Long, length: Long): List<Entry> {
        var bytes = read(offset, length.toInt()).array()
        if (internalCompression == 2) bytes = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        val runs = IntArray(n) { varint().toInt() }
        val lengths = IntArray(n) { varint().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = varint()
            offsets[i] = if (i > 0 && v == 0L) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    private fun find(entries: List<Entry>, id: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = id - entries[mid].tileId
            when {
                c > 0 -> lo = mid + 1
                c < 0 -> hi = mid - 1
                else -> return entries[mid]
            }
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e
            if (id - e.tileId < e.runLength) return e
        }
        return null
    }

    /** The tile's bytes, or null if the archive has no tile there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minZoom || z > maxZoom) return null
        val id = zxyToTileId(z, x, y)
        var entries = root
        repeat(4) {
            val e = find(entries, id) ?: return null
            if (e.runLength > 0) return read(tileDataOffset + e.offset, e.length).array()
            val key = leafOffset + e.offset
            entries = leaves.get(key) ?: readDirectory(key, e.length.toLong()).also { leaves.put(key, it) }
        }
        return null
    }

    override fun close() = raf.close()

    companion object {
        /** Hilbert-curve tile id, as in the PMTiles specification. */
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
    }
}
