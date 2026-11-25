package com.trailguidegps.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.hardware.GeomagneticField
import android.os.Handler
import android.os.Looper
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.data.PrintPlan
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.geo.Coords
import com.trailguidegps.app.geo.Geo
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A topo map of a route at a true scale, as a letter-size PDF: an overview page, then one page
 * per stretch of the route with the route, mile marks, camps and a 1 km UTM grid. Drawn from the
 * downloaded map files, page by page.
 */
object PrintMap {
    /** Map points per paper point: a little more than 1 so the map draws at a more detailed zoom with print-size labels. */
    private const val DETAIL = 1.35
    private const val RATIO = 1.7f
    /**
     * One map renderer for all printing, reused page after page. A new renderer per page piles up
     * native memory until the garbage collector frees the old ones, which can crash the app.
     */
    private var shooter: MapSnapshotter? = null
    private var shooterStyle: String? = null

    fun make(context: Context, stores: List<MapStore>, route: SavedRoute, scale: PrintPlan.Scale, out: File,
             progress: (Int, Int) -> Unit, done: (String?) -> Unit) {
        val pages = PrintPlan.pages(route.coords, scale.ratio)
        if (pages.isEmpty() || stores.isEmpty()) { done("Nothing to print: the route or the maps are missing."); return }
        if (pages.size > PrintPlan.MAX_PAGES) { done("That would be ${pages.size} pages. Try 1:62,500, or split the route."); return }
        val style = styleJson(stores)
        val doc = PdfDocument()
        val main = Handler(Looper.getMainLooper())
        var zoomFix = 0.0   // learned from the first page if the map's zoom scale isn't what's expected

        fun finish(error: String?) {
            if (error != null) { doc.close(); done(error); return }
            Thread {
                val e = runCatching { out.outputStream().use { doc.writeTo(it) } }.exceptionOrNull()
                doc.close()
                main.post { done(e?.let { "Couldn't write the PDF: ${it.message}" }) }
            }.start()
        }

        fun page(i: Int) {
            if (i > pages.size) { finish(null); return }
            progress(i, pages.size)
            if (i == 0) {
                overview(context, style, route, pages, scale) { bmp ->
                    drawOverviewPage(doc, route, scale, pages, bmp)
                    bmp?.recycle()
                    main.post { page(1) }
                }
                return
            }
            val p = pages[i - 1]
            val wPx = PrintPlan.MAP_W * DETAIL
            val hPx = PrintPlan.MAP_H * DETAIL
            fun render(zoom: Double, retried: Boolean) {
                val center = LatLng(p.lat, p.lon)
                snapshot(context, style, wPx.roundToInt(), hPx.roundToInt(), CameraPosition.Builder().target(center).zoom(zoom).build(), center) { snap ->
                    if (snap == null) { finish("Couldn't draw page $i of the map."); return@snapshot }
                    // Check the scale: ground across the middle of the picture against what the page should cover.
                    val bw = snap.bitmap.width.toFloat(); val bh = snap.bitmap.height.toFloat()
                    val left = snap.toLL(0f, bh / 2)
                    val right = snap.toLL(bw, bh / 2)
                    val across = Geo.distanceM(left.latitude, left.longitude, right.latitude, right.longitude)
                    val off = across / p.widthM
                    if (!retried && abs(off - 1) > 0.03 && off in 0.4..2.5 && zoomFix == 0.0) {
                        zoomFix += ln(off) / ln(2.0)
                        // After this callback returns, so the renderer is free to start again.
                        main.post { render(zoom + ln(off) / ln(2.0), true) }
                        return@snapshot
                    }
                    val bmp = snap.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    drawOnMap(Canvas(bmp), bmp.width, bmp.height, route, snap)
                    drawMapPage(doc, route, scale, p, pages.size, bmp)
                    bmp.recycle()
                    main.post { page(i + 1) }
                }
            }
            render(PrintPlan.zoomFor(p.lat, p.widthM, wPx) + zoomFix, false)
        }
        page(0)
    }

    /** The offline topo style, without imagery, land-manager colours or online-only layers. */
    private fun styleJson(stores: List<MapStore>): String {
        val o = org.json.JSONObject(MapStyle.build(stores.map { MapStyle.MapSource(it.region, it.dir) }))
        val layers = o.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.getString("id")
            if (listOf("satellite", "land-fill", "fire", "smoke", "aqi").any { id.startsWith(it) }) {
                l.put("layout", (l.optJSONObject("layout") ?: org.json.JSONObject()).put("visibility", "none"))
            }
        }
        return o.toString()
    }

    /** A finished map picture, with lat/lon → bitmap pixel and bitmap pixel → lat/lon. */
    private class Shot(val bitmap: Bitmap, val toPx: (Double, Double) -> PointF, val toLL: (Float, Float) -> LatLng)

    /** One map picture centred on [center], or null after a failure or 40 s. Must be called on the main thread. */
    private fun snapshot(context: Context, style: String, w: Int, h: Int, camera: CameraPosition,
                         center: LatLng, done: (Shot?) -> Unit) {
        val reuse = shooter
        val snap = if (reuse == null) {
            MapSnapshotter(context.applicationContext, MapSnapshotter.Options(w, h)
                .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromJson(style))
                .withPixelRatio(RATIO).withLogo(false).withCameraPosition(camera)).also { shooter = it; shooterStyle = style }
        } else reuse.apply {
            if (shooterStyle != style) { setStyleJson(style); shooterStyle = style }
            setSize(w, h)
            setCameraPosition(camera)
        }
        var finished = false
        Handler(Looper.getMainLooper()).postDelayed({ if (!finished) { finished = true; runCatching { snap.cancel() }; done(null) } }, 40_000)
        snap.start({ s ->
            if (finished) return@start
            finished = true
            // MapLibre: pixelForLatLng answers in bitmap pixels; latLngForPixel takes map points (pixels / pixel ratio).
            done(Shot(s.bitmap, { lat, lon -> s.pixelForLatLng(LatLng(lat, lon)) },
                { x, y -> s.latLngForPixel(PointF(x / RATIO, y / RATIO)) }))
        }, { _ -> if (!finished) { finished = true; done(null) } })
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width; this.color = color; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun text(size: Float, color: Int = Color.BLACK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** The UTM grid, the route, mile marks, camps and the start and end, drawn onto a map picture. */
    private fun drawOnMap(c: Canvas, w: Int, h: Int, route: SavedRoute, shot: Shot) {
        val px = shot.toPx
        val u = RATIO   // bitmap pixels per map point
        // 1 km UTM grid in the zone of the page's middle.
        val corners = listOf(PointF(0f, 0f), PointF(w.toFloat(), 0f), PointF(w.toFloat(), h.toFloat()), PointF(0f, h.toFloat()))
        val midLL = shot.toLL(w / 2f, h / 2f)
        run {
            val zone = Geo.utm(midLL.latitude, midLL.longitude).zone
            val utms = corners.map { shot.toLL(it.x, it.y) }.map { Geo.utm(it.latitude, it.longitude) }.filter { it.zone == zone }
            if (utms.size == 4) {
                val grid = stroke(Color.argb(150, 21, 101, 192), 1.1f * u)
                val label = text(10f * u, Color.argb(220, 21, 101, 192))
                val halo = text(10f * u, Color.WHITE).apply { style = Paint.Style.STROKE; strokeWidth = 3f * u }
                val e0 = floor(utms.minOf { it.easting } / 1000.0).toInt(); val e1 = ceil(utms.maxOf { it.easting } / 1000.0).toInt()
                val n0 = floor(utms.minOf { it.northing } / 1000.0).toInt(); val n1 = ceil(utms.maxOf { it.northing } / 1000.0).toInt()
                fun line(pts: List<DoubleArray>): Path? {
                    val path = Path(); var any = false
                    pts.forEachIndexed { i, ll -> px(ll[0], ll[1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }; any = true }
                    return if (any) path else null
                }
                for (e in e0..e1) {
                    val pts = (n0 * 4..n1 * 4).mapNotNull { q -> Coords.fromUtm(zone, false, e * 1000.0, q * 250.0)?.let { doubleArrayOf(it.first, it.second) } }
                    line(pts)?.let { c.drawPath(it, grid) }
                    pts.map { px(it[0], it[1]) }.filter { it.y in 0f..h.toFloat() }.minByOrNull { it.y }?.let { top ->
                        if (top.x in 20f * u..w - 20f * u) { c.drawText("$e", top.x + 2 * u, 11f * u, halo); c.drawText("$e", top.x + 2 * u, 11f * u, label) }
                    }
                }
                for (n in n0..n1) {
                    val pts = (e0 * 4..e1 * 4).mapNotNull { q -> Coords.fromUtm(zone, false, q * 250.0, n * 1000.0)?.let { doubleArrayOf(it.first, it.second) } }
                    line(pts)?.let { c.drawPath(it, grid) }
                    pts.map { px(it[0], it[1]) }.filter { it.x in 0f..w.toFloat() }.minByOrNull { it.x }?.let { l ->
                        if (l.y in 20f * u..h - 20f * u) { c.drawText("$n", 3 * u, l.y - 2 * u, halo); c.drawText("$n", 3 * u, l.y - 2 * u, label) }
                    }
                }
            }
        }
        // The route.
        val path = Path()
        route.coords.forEachIndexed { i, cc -> px(cc[0], cc[1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        val color = runCatching { Color.parseColor(route.color) }.getOrDefault(Color.rgb(106, 27, 154))
        c.drawPath(path, stroke(Color.WHITE, 6.5f * u))
        c.drawPath(path, stroke(color, 3.6f * u))
        // A mark and number every mile.
        val miPaint = text(12f * u, color, bold = true, align = Paint.Align.CENTER)
        val miHalo = text(12f * u, Color.WHITE, bold = true, align = Paint.Align.CENTER).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * u }
        val total = route.stats.distanceM
        var m = Geo.M_PER_MI
        while (m < total - 100) {
            RouteStore.pointAt(route.coords, m)?.let { pt ->
                val p = px(pt[0], pt[1])
                c.drawCircle(p.x, p.y, 3.2f * u, fill(Color.WHITE)); c.drawCircle(p.x, p.y, 2.2f * u, fill(color))
                val label = "${(m / Geo.M_PER_MI).roundToInt()}"
                c.drawText(label, p.x + 9 * u, p.y - 5 * u, miHalo); c.drawText(label, p.x + 9 * u, p.y - 5 * u, miPaint)
            }
            m += Geo.M_PER_MI
        }
        // Camps by night, then the start and end.
        val campText = text(10f * u, color, bold = true, align = Paint.Align.CENTER)
        route.camps.sortedBy { it.alongM }.forEachIndexed { i, cp ->
            RouteStore.pointAt(route.coords, cp.alongM)?.let { pt ->
                val p = px(pt[0], pt[1])
                c.drawCircle(p.x, p.y, 8f * u, fill(Color.WHITE)); c.drawCircle(p.x, p.y, 8f * u, stroke(color, 2f * u))
                c.drawText("${i + 1}", p.x, p.y + 3.6f * u, campText)
            }
        }
        route.coords.last().let { px(it[0], it[1]) }.let { c.drawCircle(it.x, it.y, 6f * u, fill(Color.WHITE)); c.drawCircle(it.x, it.y, 4.2f * u, fill(Color.rgb(183, 28, 28))) }
        route.coords.first().let { px(it[0], it[1]) }.let { c.drawCircle(it.x, it.y, 6f * u, fill(Color.WHITE)); c.drawCircle(it.x, it.y, 4.2f * u, fill(Color.rgb(46, 139, 87))) }
    }

    private fun header(c: Canvas, route: SavedRoute, right: String) {
        c.drawText(route.name, PrintPlan.MARGIN.toFloat(), PrintPlan.MARGIN + 16f, text(13f, bold = true))
        c.drawText(right, (PrintPlan.PAGE_W - PrintPlan.MARGIN).toFloat(), PrintPlan.MARGIN + 16f, text(10f, Color.DKGRAY, align = Paint.Align.RIGHT))
    }

    private fun drawMapPage(doc: PdfDocument, route: SavedRoute, scale: PrintPlan.Scale, p: PrintPlan.Page, count: Int, bmp: Bitmap) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PrintPlan.PAGE_W, PrintPlan.PAGE_H, p.n + 1).create())
        val c = page.canvas
        header(c, route, "Map page ${p.n} of $count · miles %.1f–%.1f".format(Locale.US, p.fromM / Geo.M_PER_MI, p.toM / Geo.M_PER_MI))
        val top = (PrintPlan.MARGIN + PrintPlan.HEADER).toFloat()
        val rect = RectF(PrintPlan.MARGIN.toFloat(), top, (PrintPlan.MARGIN + PrintPlan.MAP_W).toFloat(), top + PrintPlan.MAP_H)
        c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(rect, stroke(Color.BLACK, 0.8f))
        footer(c, scale, p.lat, p.lon, rect.bottom + 14f)
        doc.finishPage(page)
    }

    /** Scale bars, north and declination, the grid and the credit, under the map. */
    private fun footer(c: Canvas, scale: PrintPlan.Scale, lat: Double, lon: Double, y: Float) {
        val left = PrintPlan.MARGIN.toFloat()
        val small = text(8f, Color.DKGRAY)
        val bar = stroke(Color.BLACK, 2.2f)
        fun scaleBar(x: Float, yy: Float, meters: Double, label: String) {
            val len = PrintPlan.paperPt(meters, scale.ratio).toFloat()
            c.drawLine(x, yy, x + len, yy, bar)
            c.drawLine(x, yy - 4, x, yy + 4, stroke(Color.BLACK, 1f)); c.drawLine(x + len / 2, yy - 3, x + len / 2, yy + 3, stroke(Color.BLACK, 1f))
            c.drawLine(x + len, yy - 4, x + len, yy + 4, stroke(Color.BLACK, 1f))
            c.drawText(label, x + len + 6, yy + 3, small)
        }
        val mile = if (scale == PrintPlan.Scale.S24) Geo.M_PER_MI else 2 * Geo.M_PER_MI
        val km = if (scale == PrintPlan.Scale.S24) 1000.0 else 2000.0
        scaleBar(left, y, mile, if (scale == PrintPlan.Scale.S24) "1 mile" else "2 miles")
        scaleBar(left, y + 13, km, if (scale == PrintPlan.Scale.S24) "1 km" else "2 km")
        val decl = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
        val zone = Geo.utm(lat, lon).zone
        val x2 = left + 230
        c.drawText("Scale ${scale.label}. Print at actual size (100%), not \"fit to page\".", x2, y + 3, text(8.5f, bold = true))
        c.drawText("True north is up. Magnetic declination about %.0f° %s (%d).".format(Locale.US, abs(decl), if (decl >= 0) "E" else "W",
            java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)), x2, y + 15, small)
        c.drawText("Blue grid: UTM zone $zone, 1 km squares (WGS84); edge numbers in km.", x2, y + 26, small)
        c.drawText("TrailGuide GPS · Map data © OpenStreetMap contributors; elevation USGS. Check it against a current map.", left, y + 38, small)
    }

    private fun overview(context: Context, style: String, route: SavedRoute, pages: List<PrintPlan.Page>, scale: PrintPlan.Scale, done: (Bitmap?) -> Unit) {
        val all = pages.flatMap { PrintPlan.corners(it) } + route.coords
        val b = LatLngBounds.Builder(); all.forEach { b.include(LatLng(it[0], it[1])) }
        val raw = runCatching { b.build() }.getOrNull() ?: run { done(null); return }
        val padLat = (raw.latitudeNorth - raw.latitudeSouth) * 0.04; val padLon = (raw.longitudeEast - raw.longitudeWest) * 0.04
        val region = LatLngBounds.from(raw.latitudeNorth + padLat, raw.longitudeEast + padLon, raw.latitudeSouth - padLat, raw.longitudeWest - padLon)
        val c = region.center
        val wideM = (region.longitudeEast - region.longitudeWest) * 111_320.0 * kotlin.math.cos(Math.toRadians(c.latitude))
        val tallM = (region.latitudeNorth - region.latitudeSouth) * 110_574.0
        val zoom = minOf(PrintPlan.zoomFor(c.latitude, wideM, PrintPlan.MAP_W.toDouble()), PrintPlan.zoomFor(c.latitude, tallM, 520.0))
        snapshot(context, style, PrintPlan.MAP_W, 520, CameraPosition.Builder().target(c).zoom(zoom).build(), c) { snap ->
            if (snap == null) { done(null); return@snapshot }
            val px = snap.toPx
            val bmp = snap.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(bmp)
            val path = Path()
            route.coords.forEachIndexed { i, cc -> px(cc[0], cc[1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            val color = runCatching { Color.parseColor(route.color) }.getOrDefault(Color.rgb(106, 27, 154))
            c.drawPath(path, stroke(Color.WHITE, 7f)); c.drawPath(path, stroke(color, 4f))
            val box = stroke(Color.argb(220, 33, 33, 33), 2.4f)
            val num = text(26f, Color.argb(230, 33, 33, 33), bold = true)
            pages.forEach { p ->
                val q = PrintPlan.corners(p).map { px(it[0], it[1]) }
                val r = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                c.drawPath(r, box)
                c.drawText("${p.n}", q[0].x + 8, q[0].y + 28, num)
            }
            done(bmp)
        }
    }

    private fun drawOverviewPage(doc: PdfDocument, route: SavedRoute, scale: PrintPlan.Scale, pages: List<PrintPlan.Page>, bmp: Bitmap?) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PrintPlan.PAGE_W, PrintPlan.PAGE_H, 1).create())
        val c = page.canvas
        header(c, route, "Overview · ${pages.size} map page${if (pages.size == 1) "" else "s"} at ${scale.label}")
        var y = (PrintPlan.MARGIN + PrintPlan.HEADER).toFloat()
        val left = PrintPlan.MARGIN.toFloat()
        c.drawText("%.1f mi · +%,d ft climb · −%,d ft descent".format(Locale.US, route.stats.distanceM / Geo.M_PER_MI,
            (route.stats.gainM * Geo.FT_PER_M).roundToInt(), (route.stats.lossM * Geo.FT_PER_M).roundToInt()), left, y + 4, text(10f, Color.DKGRAY))
        y += 14
        if (bmp != null) {
            val h = PrintPlan.MAP_W * bmp.height.toFloat() / bmp.width
            val rect = RectF(left, y, left + PrintPlan.MAP_W, y + h)
            c.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(rect, stroke(Color.BLACK, 0.8f))
            y = rect.bottom + 18
        }
        c.drawText("Map pages", left, y, text(11f, bold = true)); y += 15
        val body = text(9.5f)
        pages.forEach { p ->
            if (y > PrintPlan.PAGE_H - PrintPlan.MARGIN - 40) return@forEach
            c.drawText("Page ${p.n}: miles %.1f to %.1f".format(Locale.US, p.fromM / Geo.M_PER_MI, p.toM / Geo.M_PER_MI), left, y, body); y += 13
        }
        y += 6
        val note = text(8.5f, Color.DKGRAY)
        listOf(
            "Each page is ${scale.label}: print at actual size (100%) on letter paper for the scale bars and grid to measure true.",
            "The route is drawn with a dot and number every mile; camps are numbered by night. Green is the start, red the end.",
        ).forEach { if (y < PrintPlan.PAGE_H - PrintPlan.MARGIN) { c.drawText(it, left, y, note); y += 12 } }
        doc.finishPage(page)
    }
}
