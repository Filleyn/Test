package com.trailguidegps.app.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * What public land a spot is on, read straight from a state's publiclands.pmtiles,
 * with no map on screen: the wilderness or special area, the national forest or park
 * boundary, and the land parcel (PAD-US). Works offline.
 */
class LandLookup(private val file: File) {
    /** Props of the polygon the spot is inside, per layer ("special", "boundary", "land"), or null. */
    data class Hit(val special: Map<String, String?>?, val boundary: Map<String, String?>?, val land: Map<String, String?>?)

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    private var header: Header? = null
    private val dirCache = HashMap<Long, List<Entry>>()

    private class Header(
        val rootOffset: Long, val rootLength: Int, val leafOffset: Long, val tileDataOffset: Long,
        val internalGzip: Boolean, val tileGzip: Boolean, val minZoom: Int, val maxZoom: Int,
    )

    @Synchronized
    fun at(lat: Double, lon: Double): Hit? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            val h = header ?: readHeader(raf).also { header = it } ?: return null
            // The most detailed zoom has every polygon; fall back if that tile is missing.
            for (z in h.maxZoom downTo h.minZoom) {
                val n = 1L shl z
                val fx = (lon + 180.0) / 360.0 * n
                val latR = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                val fy = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n
                val x = floor(fx).toLong()
                val y = floor(fy).toLong()
                if (x !in 0 until n || y !in 0 until n) return null
                val bytes = tile(raf, h, z, x, y) ?: continue
                val data = if (h.tileGzip || (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())) gunzip(bytes) else bytes
                return Mvt.hit(data, fx - x, fy - y)
            }
        }
        return null
    }

    private fun readHeader(raf: RandomAccessFile): Header? {
        if (raf.length() < 127) return null
        val b = ByteArray(127)
        raf.seek(0); raf.readFully(b)
        if (String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) return null
        fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }
        return Header(
            rootOffset = u64(8), rootLength = u64(16).toInt(), leafOffset = u64(40), tileDataOffset = u64(56),
            internalGzip = b[97].toInt() == 2, tileGzip = b[98].toInt() == 2,
            minZoom = b[100].toInt() and 0xff, maxZoom = b[101].toInt() and 0xff,
        )
    }

    private fun directory(raf: RandomAccessFile, h: Header, offset: Long, length: Int): List<Entry> =
        dirCache.getOrPut(offset) {
            val raw = ByteArray(length)
            raf.seek(offset); raf.readFully(raw)
            parseDirectory(if (h.internalGzip) gunzip(raw) else raw)
        }

    private fun tile(raf: RandomAccessFile, h: Header, z: Int, x: Long, y: Long): ByteArray? {
        val id = tileId(z, x, y)
        var dirOffset = h.rootOffset
        var dirLength = h.rootLength
        repeat(4) { depth ->
            val entries = directory(raf, h, dirOffset, dirLength)
            val e = find(entries, id) ?: return null
            if (e.runLength > 0) {
                val out = ByteArray(e.length)
                raf.seek(h.tileDataOffset + e.offset); raf.readFully(out)
                return out
            }
            // A leaf directory.
            dirOffset = h.leafOffset + e.offset
            dirLength = e.length
        }
        return null
    }

    companion object {
        /** Hilbert-curve tile number, as in the PMTiles v3 spec. */
        fun tileId(z: Int, x: Long, y: Long): Long {
            var acc = 0L
            for (t in 0 until z) acc += (1L shl t) * (1L shl t)
            var xx = x; var yy = y
            var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if ((xx and s) > 0) 1L else 0L
                val ry = if ((yy and s) > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                    val t = xx; xx = yy; yy = t
                }
                s /= 2
            }
            return acc + d
        }

        private fun parseDirectory(b: ByteArray): List<Entry> {
            var pos = 0
            fun varint(): Long {
                var shift = 0; var result = 0L
                while (true) {
                    val c = b[pos++].toLong() and 0xff
                    result = result or ((c and 0x7f) shl shift)
                    if (c < 0x80) return result
                    shift += 7
                }
            }
            val n = varint().toInt()
            val ids = LongArray(n); val runs = LongArray(n); val lens = IntArray(n); val offs = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += varint(); ids[i] = last }
            for (i in 0 until n) runs[i] = varint()
            for (i in 0 until n) lens[i] = varint().toInt()
            for (i in 0 until n) {
                val v = varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
        }

        private fun find(entries: List<Entry>, id: Long): Entry? {
            var lo = 0; var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId
                when {
                    c < id -> lo = mid + 1
                    c > id -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            // The entry just before: a run of identical tiles, or a leaf directory.
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0L) return e
                if (id - e.tileId < e.runLength) return e
            }
            return null
        }

        private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
    }

    /** Just enough Mapbox Vector Tile decoding to ask "which polygons contain this point". */
    private object Mvt {
        private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
            fun more() = pos < end
            fun varint(): Long {
                var shift = 0; var result = 0L
                while (true) {
                    val c = b[pos++].toLong() and 0xff
                    result = result or ((c and 0x7f) shl shift)
                    if (c < 0x80) return result
                    shift += 7
                }
            }
            fun sub(): Reader { val len = varint().toInt(); val r = Reader(b, pos, pos + len); pos += len; return r }
            fun string(): String { val len = varint().toInt(); val s = String(b, pos, len, Charsets.UTF_8); pos += len; return s }
            fun skip(wire: Int) {
                when (wire) {
                    0 -> varint()
                    1 -> pos += 8
                    2 -> pos += varint().toInt()
                    5 -> pos += 4
                    else -> throw IllegalStateException("wire type $wire")
                }
            }
            fun packed(): LongArray {
                val r = sub(); val out = ArrayList<Long>()
                while (r.more()) out += r.varint()
                return out.toLongArray()
            }
        }

        fun hit(data: ByteArray, fx: Double, fy: Double): Hit {
            val found = HashMap<String, Map<String, String?>>()
            val r = Reader(data, 0, data.size)
            while (r.more()) {
                val key = r.varint().toInt()
                if ((key ushr 3) == 3 && (key and 7) == 2) layer(r.sub(), fx, fy, found) else r.skip(key and 7)
            }
            return Hit(found["special"], found["boundary"], found["land"])
        }

        private fun layer(r: Reader, fx: Double, fy: Double, found: MutableMap<String, Map<String, String?>>) {
            var name = ""
            val keys = ArrayList<String>(); val values = ArrayList<String?>()
            val features = ArrayList<Pair<LongArray, LongArray>>() // tags, geometry of polygons
            var extent = 4096
            while (r.more()) {
                val key = r.varint().toInt()
                when (key ushr 3) {
                    1 -> name = r.string()
                    2 -> {
                        val f = r.sub()
                        var type = 0; var tags = LongArray(0); var geom = LongArray(0)
                        while (f.more()) {
                            val k = f.varint().toInt()
                            when (k ushr 3) {
                                2 -> tags = f.packed()
                                3 -> type = f.varint().toInt()
                                4 -> geom = f.packed()
                                else -> f.skip(k and 7)
                            }
                        }
                        if (type == 3) features += tags to geom
                    }
                    3 -> keys += r.string()
                    4 -> {
                        val v = r.sub(); var s: String? = null
                        while (v.more()) {
                            val k = v.varint().toInt()
                            when {
                                (k ushr 3) == 1 -> s = v.string()
                                (k and 7) == 0 -> s = v.varint().toString()
                                else -> v.skip(k and 7)
                            }
                        }
                        values += s
                    }
                    5 -> extent = r.varint().toInt()
                    else -> r.skip(key and 7)
                }
            }
            if (name !in setOf("special", "boundary", "land") || name in found) return
            val px = fx * extent; val py = fy * extent
            for ((tags, geom) in features) {
                if (!inside(geom, px, py)) continue
                val props = HashMap<String, String?>()
                var i = 0
                while (i + 1 < tags.size) { props[keys[tags[i].toInt()]] = values.getOrNull(tags[i + 1].toInt()); i += 2 }
                found[name] = props
                return
            }
        }

        /** Even-odd test over every ring, which handles holes and multi-part polygons. */
        private fun inside(geom: LongArray, px: Double, py: Double): Boolean {
            var x = 0L; var y = 0L
            var inside = false
            var startX = 0L; var startY = 0L; var prevX = 0L; var prevY = 0L
            var i = 0
            fun edge(ax: Long, ay: Long, bx: Long, by: Long) {
                if ((ay > py) != (by > py)) {
                    val xi = ax + (py - ay) * (bx - ax).toDouble() / (by - ay)
                    if (px < xi) inside = !inside
                }
            }
            while (i < geom.size) {
                val cmd = (geom[i] and 7).toInt(); val count = (geom[i] ushr 3).toInt(); i++
                when (cmd) {
                    1 -> repeat(count) {
                        x += zig(geom[i]); y += zig(geom[i + 1]); i += 2
                        startX = x; startY = y; prevX = x; prevY = y
                    }
                    2 -> repeat(count) {
                        x += zig(geom[i]); y += zig(geom[i + 1]); i += 2
                        edge(prevX, prevY, x, y); prevX = x; prevY = y
                    }
                    7 -> { edge(prevX, prevY, startX, startY); prevX = startX; prevY = startY }
                    else -> return inside
                }
            }
            return inside
        }

        private fun zig(v: Long): Long = (v ushr 1) xor -(v and 1)
    }
}
