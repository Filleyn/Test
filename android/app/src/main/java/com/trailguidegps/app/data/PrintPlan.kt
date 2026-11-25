package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * How a route is cut into letter-size pages at a true map scale: each page's center and the
 * ground it covers, in the order the route reaches them. Pages overlap a little so nothing
 * falls in a gap.
 */
object PrintPlan {
    enum class Scale(val ratio: Int, val label: String, val note: String) {
        S24(24_000, "1:24,000", "Same as USGS 7.5-minute maps: most detail, more pages"),
        S62(62_500, "1:62,500", "About an inch to the mile: more ground on each page"),
    }

    // US Letter, in points (72 to the inch).
    const val PAGE_W = 612
    const val PAGE_H = 792
    const val MARGIN = 36
    const val HEADER = 30
    const val FOOTER = 58
    const val MAP_W = PAGE_W - 2 * MARGIN
    const val MAP_H = PAGE_H - 2 * MARGIN - HEADER - FOOTER
    const val OVERLAP = 0.08
    const val MAX_PAGES = 24

    /** Ground metres covered by [points] of paper at [scale]. */
    fun groundM(points: Double, scale: Int) = points / 72.0 * 0.0254 * scale

    /** Paper points for [meters] of ground at [scale]. */
    fun paperPt(meters: Double, scale: Int) = meters / scale / 0.0254 * 72.0

    data class Page(val n: Int, val lat: Double, val lon: Double, val widthM: Double, val heightM: Double, val fromM: Double, val toM: Double)

    fun pages(coords: List<DoubleArray>, scale: Int): List<Page> {
        if (coords.size < 2) return emptyList()
        val lat0 = (coords.minOf { it[0] } + coords.maxOf { it[0] }) / 2
        val lon0 = (coords.minOf { it[1] } + coords.maxOf { it[1] }) / 2
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_574.0
        // The route every 50 m, in metres east and north of its middle, with distance along.
        val pts = ArrayList<Triple<Double, Double, Double>>()
        var along = 0.0
        for (i in coords.indices) {
            val c = coords[i]
            if (i > 0) {
                val p = coords[i - 1]
                val d = Geo.distanceM(p[0], p[1], c[0], c[1])
                val steps = (d / 50).toInt()
                for (s in 1..steps) {
                    val t = s * 50 / d
                    pts += Triple((p[1] + t * (c[1] - p[1]) - lon0) * kx, (p[0] + t * (c[0] - p[0]) - lat0) * ky, along + s * 50)
                }
                along += d
            }
            pts += Triple((c[1] - lon0) * kx, (c[0] - lat0) * ky, along)
        }
        val w = groundM(MAP_W.toDouble(), scale)
        val h = groundM(MAP_H.toDouble(), scale)
        val sx = w * (1 - OVERLAP)
        val sy = h * (1 - OVERLAP)
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val cols = max(1, ceil((maxX - minX - w) / sx).toInt() + 1)
        val rows = max(1, ceil((maxY - minY - h) / sy).toInt() + 1)
        // The grid centred on the route.
        val x0 = (minX + maxX) / 2 - (w + (cols - 1) * sx) / 2 + w / 2
        val y0 = (minY + maxY) / 2 - (h + (rows - 1) * sy) / 2 + h / 2
        val first = HashMap<Pair<Int, Int>, Double>()
        val last = HashMap<Pair<Int, Int>, Double>()
        for ((x, y, a) in pts) {
            for (cx in 0 until cols) for (cy in 0 until rows) {
                val px = x0 + cx * sx; val py = y0 + cy * sy
                if (x >= px - w / 2 && x <= px + w / 2 && y >= py - h / 2 && y <= py + h / 2) {
                    val k = cx to cy
                    if (k !in first) first[k] = a
                    last[k] = a
                }
            }
        }
        return first.entries.sortedBy { it.value }.mapIndexed { i, (k, a) ->
            val px = x0 + k.first * sx; val py = y0 + k.second * sy
            Page(i + 1, lat0 + py / ky, lon0 + px / kx, w, h, a, last[k] ?: a)
        }
    }

    /** The map zoom that draws [groundWidthM] across [widthPx] map points at [lat] (MapLibre: 512 points span the world at zoom 0). */
    fun zoomFor(lat: Double, groundWidthM: Double, widthPx: Double): Double =
        ln(40_075_016.686 * cos(Math.toRadians(lat)) * widthPx / (512.0 * groundWidthM)) / ln(2.0)

    /** The corners of a page, as (lat, lon): north-west, north-east, south-east, south-west. */
    fun corners(p: Page): List<DoubleArray> {
        val dLat = p.heightM / 2 / 110_574.0
        val dLon = p.widthM / 2 / (111_320.0 * cos(Math.toRadians(p.lat)))
        return listOf(doubleArrayOf(p.lat + dLat, p.lon - dLon), doubleArrayOf(p.lat + dLat, p.lon + dLon),
            doubleArrayOf(p.lat - dLat, p.lon + dLon), doubleArrayOf(p.lat - dLat, p.lon - dLon))
    }
}
