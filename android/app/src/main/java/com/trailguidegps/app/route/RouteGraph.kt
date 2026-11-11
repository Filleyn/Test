package com.trailguidegps.app.route

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** A tap snapped onto the nearest trail: which segment, how far along it (0–1), and where. */
data class Snap(val edge: Int, val frac: Double, val lat: Double, val lon: Double, val distM: Double)

/**
 * A routed stretch between two snaps: its line (lat, lon pairs), length and the trails it uses.
 * [kindM] is metres on trail, track and road; [sacMax] the hardest SAC scale rating met (0 = none rated),
 * [sacRatedM] how many metres carried a rating at all.
 */
data class RoutedLeg(
    val coords: List<DoubleArray>, val lengthM: Double, val names: List<String>,
    val kindM: DoubleArray = DoubleArray(3), val sacMax: Int = 0, val sacRatedM: Double = 0.0,
)

/**
 * The offline trail network (routing.bin from the map build), memory-mapped so
 * a whole state's worth of trails costs almost no app memory. Layout is
 * documented in pipeline/routing_bin.py.
 */
class RouteGraph private constructor(private val buf: ByteBuffer) {
    val vertexCount: Int
    val edgeCount: Int
    private val rows: Int
    private val cols: Int
    private val cellDeg: Double
    private val minLat: Double
    private val minLon: Double
    private val vlat: IntBuffer
    private val vlon: IntBuffer
    private val adjStart: IntBuffer
    private val adjEdges: IntBuffer
    private val eFrom: IntBuffer
    private val eTo: IntBuffer
    private val eLen: FloatBuffer
    private val eName: IntBuffer
    private val eKind: ByteBuffer
    private val eSac: ByteBuffer?
    private val geomStart: IntBuffer
    private val cLat: IntBuffer
    private val cLon: IntBuffer
    private val cellStart: IntBuffer
    private val cellEdges: IntBuffer
    private val names: Array<String>

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.position(0); buf.get(it) }
        require(String(magic) == "TGRG") { "Not a TrailGuide routing file" }
        vertexCount = buf.getInt(8)
        edgeCount = buf.getInt(12)
        val coordCount = buf.getInt(16)
        rows = buf.getInt(20)
        cols = buf.getInt(24)
        val nameCount = buf.getInt(28)
        cellDeg = buf.getDouble(32)
        minLat = buf.getDouble(40)
        minLon = buf.getDouble(48)
        val version = buf.getInt(4)
        val off = LongArray(if (version >= 2) 18 else 17) { buf.getLong(56 + it * 8) }
        fun ints(i: Int, n: Int): IntBuffer = view(off[i], n * 4).asIntBuffer()
        fun floats(i: Int, n: Int): FloatBuffer = view(off[i], n * 4).asFloatBuffer()
        vlat = ints(0, vertexCount)
        vlon = ints(1, vertexCount)
        adjStart = ints(2, vertexCount + 1)
        adjEdges = ints(3, edgeCount * 2)
        eFrom = ints(4, edgeCount)
        eTo = ints(5, edgeCount)
        eLen = floats(6, edgeCount)
        eName = ints(9, edgeCount)
        eKind = view(off[10], edgeCount)
        eSac = if (version >= 2) view(off[17], edgeCount) else null
        geomStart = ints(11, edgeCount + 1)
        cLat = ints(12, coordCount)
        cLon = ints(13, coordCount)
        cellStart = ints(14, rows * cols + 1)
        cellEdges = ints(15, cellStart.get(rows * cols))
        var p = off[16].toInt() + 4
        names = Array(nameCount) {
            val len = buf.getShort(p).toInt() and 0xffff
            val b = ByteArray(len)
            val d = buf.duplicate(); d.position(p + 2); d.get(b)
            p += 2 + len
            String(b, Charsets.UTF_8)
        }
    }

    private fun view(offset: Long, bytes: Int): ByteBuffer {
        val d = buf.duplicate()
        d.position(offset.toInt())
        val s = d.slice()
        s.limit(bytes)
        return s.order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun other(e: Int, v: Int) = if (eFrom.get(e) == v) eTo.get(e) else eFrom.get(e)

    fun nameOf(edge: Int): String? = eName.get(edge).takeIf { it >= 0 }?.let { names[it] }

    /** 0 trail, 1 track, 2 road. */
    fun kindOf(edge: Int): Int = (eKind.get(edge).toInt() and 0xff).coerceIn(0, 2)

    /** SAC hiking scale 1–6, or 0 if the trail isn't rated (or the map is too old to say). */
    fun sacOf(edge: Int): Int = eSac?.get(edge)?.toInt()?.and(0xff) ?: 0

    fun vertexLat(v: Int): Double = vlat.get(v) / 1e6
    fun vertexLon(v: Int): Double = vlon.get(v) / 1e6
    /** How many trail or road stretches meet at a vertex (1 = a dead end). */
    fun degree(v: Int): Int = adjStart.get(v + 1) - adjStart.get(v)

    /** [south, west, north, east] of the area this network was built for. */
    val bounds: DoubleArray get() = doubleArrayOf(minLat, minLon, minLat + rows * cellDeg, minLon + cols * cellDeg)

    /** A snap sitting exactly on a vertex. */
    fun snapAtVertex(v: Int): Snap? {
        if (degree(v) == 0) return null
        val e = adjEdges.get(adjStart.get(v))
        return Snap(e, if (eFrom.get(e) == v) 0.0 else 1.0, vertexLat(v), vertexLon(v), 0.0)
    }

    /**
     * Walking distance in metres from [a] to each of [targets] (infinity where unreachable
     * within the search limit). One search for all of them: A* aimed at the nearest target,
     * which still gives exact distances because the estimate never overshoots.
     */
    fun distances(a: Snap, targets: List<Snap>, maxSettled: Int = 2_500_000): DoubleArray {
        val best = DoubleArray(targets.size) { Double.POSITIVE_INFINITY }
        if (targets.isEmpty()) return best
        val n = vertexCount
        val g = FloatArray(n) { Float.POSITIVE_INFINITY }
        val closed = BooleanArray(n)
        val heap = MinHeap()
        val tLat = DoubleArray(targets.size) { targets[it].lat }
        val tLon = DoubleArray(targets.size) { targets[it].lon }
        val kx = cos(Math.toRadians(a.lat)) * 111_320.0 * 0.99
        val ky = 110_540.0 * 0.99
        fun h(v: Int): Double {
            val la = vlat.get(v) / 1e6
            val lo = vlon.get(v) / 1e6
            var m = Double.POSITIVE_INFINITY
            for (i in tLat.indices) { val d = hypot((lo - tLon[i]) * kx, (la - tLat[i]) * ky); if (d < m) m = d }
            return m
        }
        // Where each target can be reached from: the two ends of its stretch.
        val atVertex = HashMap<Int, MutableList<Pair<Int, Double>>>()
        targets.forEachIndexed { i, t ->
            val len = eLen.get(t.edge).toDouble()
            atVertex.getOrPut(eFrom.get(t.edge)) { ArrayList() } += i to t.frac * len
            atVertex.getOrPut(eTo.get(t.edge)) { ArrayList() } += i to (1 - t.frac) * len
            if (t.edge == a.edge) best[i] = abs(a.frac - t.frac) * len
        }
        val la = eLen.get(a.edge).toDouble()
        for ((v, cost) in listOf(eFrom.get(a.edge) to a.frac * la, eTo.get(a.edge) to (1 - a.frac) * la)) {
            if (cost < g[v]) { g[v] = cost.toFloat(); heap.push(cost + h(v), v) }
        }
        var settled = 0
        while (heap.size > 0) {
            val f = heap.peekKey()
            val v = heap.pop()
            if (f >= best.max()) break
            if (closed[v]) continue
            closed[v] = true
            if (++settled > maxSettled) break
            val cost = g[v].toDouble()
            atVertex[v]?.forEach { (i, off) -> if (cost + off < best[i]) best[i] = cost + off }
            for (i in adjStart.get(v) until adjStart.get(v + 1)) {
                val e = adjEdges.get(i)
                val u = other(e, v)
                if (closed[u]) continue
                val nc = cost + eLen.get(e)
                if (nc < g[u]) { g[u] = nc.toFloat(); heap.push(nc + h(u), u) }
            }
        }
        return best
    }

    /**
     * The nearest place by walking where a stretch of [kind] (0 trail, 1 track, 2 road) begins:
     * a snap at that junction and the stretch's name. Null when [a] is already on one, or none
     * is within [maxM] metres of walking.
     */
    fun nearestOfKind(a: Snap, kind: Int, maxM: Double = 40_000.0, maxSettled: Int = 600_000): Pair<Snap, String?>? {
        if (kindOf(a.edge) == kind) return null
        val g = HashMap<Int, Double>()
        val closed = HashSet<Int>()
        val heap = MinHeap()
        val la = eLen.get(a.edge).toDouble()
        for ((v, cost) in listOf(eFrom.get(a.edge) to a.frac * la, eTo.get(a.edge) to (1 - a.frac) * la)) {
            if (cost < (g[v] ?: Double.POSITIVE_INFINITY)) { g[v] = cost; heap.push(cost, v) }
        }
        var settled = 0
        while (heap.size > 0) {
            val v = heap.pop()
            if (!closed.add(v)) continue
            val cost = g[v] ?: continue
            if (cost > maxM || ++settled > maxSettled) return null
            for (i in adjStart.get(v) until adjStart.get(v + 1)) {
                val e = adjEdges.get(i)
                if (kindOf(e) == kind) {
                    return Snap(e, if (eFrom.get(e) == v) 0.0 else 1.0, vertexLat(v), vertexLon(v), 0.0) to nameOf(e)
                }
                val u = other(e, v)
                if (u in closed) continue
                val nc = cost + eLen.get(e)
                if (nc < (g[u] ?: Double.POSITIVE_INFINITY)) { g[u] = nc; heap.push(nc, u) }
            }
        }
        return null
    }

    /** Adds up terrain for a leg made of these (edge, metres used) pieces. */
    private fun terrain(pieces: List<Pair<Int, Double>>): Triple<DoubleArray, Int, Double> {
        val kindM = DoubleArray(3)
        var sacMax = 0
        var rated = 0.0
        for ((e, m) in pieces) {
            kindM[kindOf(e)] += m
            val s = sacOf(e)
            if (s > 0) { rated += m; if (s > sacMax) sacMax = s }
        }
        return Triple(kindM, sacMax, rated)
    }

    /** The points of an edge, from its start vertex to its end vertex, as (lat, lon). */
    private fun edgePoints(e: Int): List<DoubleArray> {
        val s = geomStart.get(e)
        val t = geomStart.get(e + 1)
        return (s until t).map { doubleArrayOf(cLat.get(it) / 1e6, cLon.get(it) / 1e6) }
    }

    /** Nearest point on any trail or road within [maxM] metres, or null. */
    fun snap(lat: Double, lon: Double, maxM: Double = 400.0): Snap? {
        val r0 = ((lat - minLat) / cellDeg).toInt()
        val c0 = ((lon - minLon) / cellDeg).toInt()
        val kx = cos(Math.toRadians(lat)) * 111_320.0
        val ky = 110_540.0
        val seen = HashSet<Int>()
        var best: Snap? = null
        for (r in r0 - 1..r0 + 1) for (c in c0 - 1..c0 + 1) {
            if (r < 0 || c < 0 || r >= rows || c >= cols) continue
            val k = r * cols + c
            for (i in cellStart.get(k) until cellStart.get(k + 1)) {
                val e = cellEdges.get(i)
                if (!seen.add(e)) continue
                val pts = edgePoints(e)
                if (pts.size < 2) continue
                val segLens = DoubleArray(pts.size - 1) { j ->
                    hypot((pts[j + 1][1] - pts[j][1]) * kx, (pts[j + 1][0] - pts[j][0]) * ky)
                }
                val total = segLens.sum().coerceAtLeast(1e-9)
                var along = 0.0
                for (j in 0 until pts.size - 1) {
                    val ax = (pts[j][1] - lon) * kx
                    val ay = (pts[j][0] - lat) * ky
                    val dx = (pts[j + 1][1] - pts[j][1]) * kx
                    val dy = (pts[j + 1][0] - pts[j][0]) * ky
                    val l2 = dx * dx + dy * dy
                    val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                    val d = hypot(ax + t * dx, ay + t * dy)
                    if (best == null || d < best.distM) {
                        best = Snap(
                            e, (along + t * segLens[j]) / total,
                            pts[j][0] + t * (pts[j + 1][0] - pts[j][0]),
                            pts[j][1] + t * (pts[j + 1][1] - pts[j][1]), d,
                        )
                    }
                    along += segLens[j]
                }
            }
        }
        return best?.takeIf { it.distM <= maxM }
    }

    /** Part of an edge's line between two fractions along it, in travel order. */
    private fun subLine(e: Int, f0: Double, f1: Double): List<DoubleArray> {
        val pts = edgePoints(e)
        if (pts.size < 2) return pts
        val kx = cos(Math.toRadians(pts[0][0])) * 111_320.0
        val ky = 110_540.0
        val cum = DoubleArray(pts.size)
        for (j in 1 until pts.size) {
            cum[j] = cum[j - 1] + hypot((pts[j][1] - pts[j - 1][1]) * kx, (pts[j][0] - pts[j - 1][0]) * ky)
        }
        val total = cum.last().coerceAtLeast(1e-9)
        fun at(f: Double): DoubleArray {
            val d = f.coerceIn(0.0, 1.0) * total
            var j = 1
            while (j < pts.size - 1 && cum[j] < d) j++
            val seg = (cum[j] - cum[j - 1]).coerceAtLeast(1e-9)
            val t = ((d - cum[j - 1]) / seg).coerceIn(0.0, 1.0)
            return doubleArrayOf(pts[j - 1][0] + t * (pts[j][0] - pts[j - 1][0]), pts[j - 1][1] + t * (pts[j][1] - pts[j - 1][1]))
        }
        val lo = minOf(f0, f1)
        val hi = maxOf(f0, f1)
        val out = ArrayList<DoubleArray>()
        out += at(lo)
        for (j in 1 until pts.size - 1) {
            val f = cum[j] / total
            if (f > lo && f < hi) out += pts[j]
        }
        out += at(hi)
        if (f0 > f1) out.reverse()
        return out
    }

    /**
     * Shortest walking route between two snaps along trails and roads (A*).
     * Returns null if they aren't connected within the search limit.
     */
    fun route(a: Snap, b: Snap, maxSettled: Int = 2_500_000): RoutedLeg? {
        if (a.edge == b.edge) {
            // Both on the same stretch: often the direct way along it is best.
            val direct = abs(a.frac - b.frac) * eLen.get(a.edge)
            val viaNetwork = search(a, b, maxSettled, direct)
            if (viaNetwork == null) {
                val (kindM, sac, rated) = terrain(listOf(a.edge to direct))
                return RoutedLeg(subLine(a.edge, a.frac, b.frac), direct, listOfNotNull(nameOf(a.edge)), kindM, sac, rated)
            }
            return viaNetwork
        }
        return search(a, b, maxSettled, Double.POSITIVE_INFINITY)
    }

    private fun search(a: Snap, b: Snap, maxSettled: Int, bound: Double): RoutedLeg? {
        val n = vertexCount
        val g = FloatArray(n) { Float.POSITIVE_INFINITY }
        val prev = IntArray(n) { -2 }
        val closed = BooleanArray(n)
        val heap = MinHeap()
        val tLat = b.lat
        val tLon = b.lon
        val kx = cos(Math.toRadians(tLat)) * 111_320.0 * 0.995 // slightly under so the estimate never overshoots
        val ky = 110_540.0 * 0.995
        fun h(v: Int): Double = hypot((vlon.get(v) / 1e6 - tLon) * kx, (vlat.get(v) / 1e6 - tLat) * ky)

        val la = eLen.get(a.edge).toDouble()
        for ((v, cost) in listOf(eFrom.get(a.edge) to a.frac * la, eTo.get(a.edge) to (1 - a.frac) * la)) {
            if (cost < g[v]) {
                g[v] = cost.toFloat(); prev[v] = -1
                heap.push(cost + h(v), v)
            }
        }
        val lb = eLen.get(b.edge).toDouble()
        val bFrom = eFrom.get(b.edge)
        val bTo = eTo.get(b.edge)
        var best = bound
        var bestV = -1
        var settled = 0
        while (heap.size > 0) {
            val f = heap.peekKey()
            val v = heap.pop()
            if (f >= best) break
            if (closed[v]) continue
            closed[v] = true
            if (++settled > maxSettled) return null
            val cost = g[v].toDouble()
            if (v == bFrom && cost + b.frac * lb < best) { best = cost + b.frac * lb; bestV = v }
            if (v == bTo && cost + (1 - b.frac) * lb < best) { best = cost + (1 - b.frac) * lb; bestV = v }
            for (i in adjStart.get(v) until adjStart.get(v + 1)) {
                val e = adjEdges.get(i)
                val u = other(e, v)
                if (closed[u]) continue
                val nc = cost + eLen.get(e)
                if (nc < g[u]) {
                    g[u] = nc.toFloat(); prev[u] = e
                    heap.push(nc + h(u), u)
                }
            }
        }
        if (bestV < 0) return null

        // Walk back from the end to the start to list the whole edges used.
        val edges = ArrayList<Pair<Int, Int>>() // (edge, vertex it was entered from)
        var v = bestV
        while (prev[v] >= 0) {
            val e = prev[v]
            val from = other(e, v)
            edges += e to from
            v = from
        }
        edges.reverse()
        val startV = v

        val coords = ArrayList<DoubleArray>()
        fun add(pts: List<DoubleArray>) {
            for (p in pts) {
                val last = coords.lastOrNull()
                if (last == null || last[0] != p[0] || last[1] != p[1]) coords += p
            }
        }
        add(subLine(a.edge, a.frac, if (startV == eFrom.get(a.edge)) 0.0 else 1.0))
        for ((e, from) in edges) add(if (from == eFrom.get(e)) edgePoints(e) else edgePoints(e).reversed())
        add(subLine(b.edge, if (bestV == bFrom) 0.0 else 1.0, b.frac))

        val names = ArrayList<String>()
        (listOf(a.edge) + edges.map { it.first } + listOf(b.edge)).forEach { e ->
            nameOf(e)?.let { if (names.lastOrNull() != it) names += it }
        }
        val la2 = eLen.get(a.edge).toDouble()
        val lb2 = eLen.get(b.edge).toDouble()
        val (kindM, sac, rated) = terrain(
            listOf(a.edge to (if (startV == eFrom.get(a.edge)) a.frac else 1 - a.frac) * la2) +
                edges.map { (e, _) -> e to eLen.get(e).toDouble() } +
                listOf(b.edge to (if (bestV == bFrom) b.frac else 1 - b.frac) * lb2),
        )
        return RoutedLeg(coords, best, names, kindM, sac, rated)
    }

    /** A plain binary min-heap of (key, vertex) without boxing. */
    private class MinHeap {
        private var keys = DoubleArray(1024)
        private var vals = IntArray(1024)
        var size = 0
            private set

        fun push(k: Double, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]; vals[i] = vals[p]; i = p
            }
            keys[i] = k; vals[i] = v
        }

        fun peekKey() = keys[0]

        fun pop(): Int {
            val top = vals[0]
            val k = keys[--size]
            val v = vals[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && keys[c + 1] < keys[c]) c++
                if (keys[c] >= k) break
                keys[i] = keys[c]; vals[i] = vals[c]; i = c
            }
            keys[i] = k; vals[i] = v
            return top
        }
    }

    companion object {
        /** Opens and memory-maps routing.bin, or returns null if it's missing or unreadable. */
        fun open(file: File): RouteGraph? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                RouteGraph(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }
        }.getOrNull()

        /** Straight-line distance in metres. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val kx = cos(Math.toRadians((lat1 + lat2) / 2)) * 111_320.0
            return sqrt(((lon2 - lon1) * kx).let { it * it } + ((lat2 - lat1) * 110_540.0).let { it * it })
        }
    }
}
