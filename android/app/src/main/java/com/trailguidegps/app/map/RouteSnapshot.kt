package com.trailguidegps.app.map

import android.content.Context
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute

/** A map picture of a route for the trip plan, drawn from the offline map files. Split out of the view model. */
object RouteSnapshot {
    /** Kept so the snapshotter isn't collected before it answers. */
    private var active: org.maplibre.android.snapshotter.MapSnapshotter? = null

    /** A map picture of the route (topo, no satellite), with the route drawn on. Null if it can't be drawn. */
    fun make(context: Context, stores: List<MapStore>, route: SavedRoute, done: (android.graphics.Bitmap?) -> Unit) {
        if (route.coords.size < 2 || stores.isEmpty()) { done(null); return }
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        route.coords.forEach { b.include(org.maplibre.android.geometry.LatLng(it[0], it[1])) }
        val raw = runCatching { b.build() }.getOrNull() ?: run { done(null); return }
        // A margin around the route so it doesn't touch the edges.
        val padLat = (raw.latitudeNorth - raw.latitudeSouth).coerceAtLeast(0.004) * 0.12
        val padLon = (raw.longitudeEast - raw.longitudeWest).coerceAtLeast(0.004) * 0.12
        val bounds = org.maplibre.android.geometry.LatLngBounds.from(
            raw.latitudeNorth + padLat, raw.longitudeEast + padLon, raw.latitudeSouth - padLat, raw.longitudeWest - padLon,
        )
        // The offline style, without the layers that are off by default or need a connection.
        val styleJson = org.json.JSONObject(com.trailguidegps.app.map.MapStyle.build(stores.map { com.trailguidegps.app.map.MapStyle.MapSource(it.region, it.dir) }))
        val layers = styleJson.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.getString("id")
            if (id.startsWith("satellite") || id.startsWith("land-fill") || id.startsWith("fire")) {
                l.put("layout", (l.optJSONObject("layout") ?: org.json.JSONObject()).put("visibility", "none"))
            }
        }
        val w = 532
        val h = 300
        val ratio = 2f
        val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(w, h)
            .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromJson(styleJson.toString()))
            .withRegion(bounds).withPixelRatio(ratio).withLogo(false)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(context, opts)
        active = snap // keep it alive until it answers
        var finished = false
        val timeout = android.os.Handler(android.os.Looper.getMainLooper())
        timeout.postDelayed({ if (!finished) { finished = true; runCatching { snap.cancel() }; done(null) } }, 20_000)
        snap.start({ s ->
            if (finished) return@start
            finished = true
            val bmp = s.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(bmp)
            // pixelForLatLng may answer in map points rather than bitmap pixels; check against the middle.
            val mid = s.pixelForLatLng(bounds.center)
            val scale = if (kotlin.math.abs(mid.x * ratio - bmp.width / 2f) < kotlin.math.abs(mid.x - bmp.width / 2f)) ratio else 1f
            fun px(lat: Double, lon: Double) = s.pixelForLatLng(org.maplibre.android.geometry.LatLng(lat, lon)).let { android.graphics.PointF(it.x * scale, it.y * scale) }
            val path = android.graphics.Path()
            route.coords.forEachIndexed { i, c -> px(c[0], c[1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            val stroke = { color: Int, width: Float ->
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = width; this.color = color
                    strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
                }
            }
            canvas.drawPath(path, stroke(android.graphics.Color.WHITE, 12f))
            canvas.drawPath(path, stroke(android.graphics.Color.parseColor(route.color), 7f))
            val dot = { lat: Double, lon: Double, color: Int ->
                val p = px(lat, lon)
                canvas.drawCircle(p.x, p.y, 13f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
                canvas.drawCircle(p.x, p.y, 9f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            }
            route.coords.last().let { dot(it[0], it[1], android.graphics.Color.rgb(183, 28, 28)) }
            route.coords.first().let { dot(it[0], it[1], android.graphics.Color.rgb(46, 139, 87)) }
            // Camps, numbered by night.
            val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 26f; typeface = android.graphics.Typeface.DEFAULT_BOLD; color = android.graphics.Color.parseColor(route.color)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            route.camps.sortedBy { it.alongM }.forEachIndexed { i, c ->
                RouteStore.pointAt(route.coords, c.alongM)?.let { pt ->
                    val p = px(pt[0], pt[1])
                    canvas.drawCircle(p.x, p.y, 17f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
                    canvas.drawCircle(p.x, p.y, 17f, stroke(android.graphics.Color.parseColor(route.color), 5f))
                    canvas.drawText("${i + 1}", p.x, p.y + 9f, label)
                }
            }
            done(bmp)
        }, { _ -> if (!finished) { finished = true; done(null) } })
    }
}
