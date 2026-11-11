package com.trailguidegps.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.trailguidegps.app.geo.Geo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** What you fill in for a trip plan. Times are epoch milliseconds. */
data class TripForm(
    val party: String,
    val phones: String,
    val startAt: Long,
    val backBy: Long,
    val alarmAt: Long,
    val vehicle: String,
    val parkedAt: String,
    val notes: String,
) {
    companion object {
        /** The parts that stay the same from trip to trip are remembered. */
        fun remembered(context: Context): TripForm {
            val p = context.getSharedPreferences("tripplan", Context.MODE_PRIVATE)
            return TripForm(p.getString("party", "") ?: "", p.getString("phones", "") ?: "", 0, 0, 0,
                p.getString("vehicle", "") ?: "", "", p.getString("notes", "") ?: "")
        }

        fun remember(context: Context, f: TripForm) {
            context.getSharedPreferences("tripplan", Context.MODE_PRIVATE).edit()
                .putString("party", f.party).putString("phones", f.phones).putString("vehicle", f.vehicle)
                .putString("notes", f.notes).apply()
        }
    }
}

/**
 * A one-page trip plan PDF to leave with someone at home: who, where, when you'll
 * be back, when to call for help, the route on a map, and its numbers.
 */
object TripPlan {
    // With the time zone ("PST"), so the plan reads the same to someone in another one.
    private val dateTime = SimpleDateFormat("EEE, MMM d 'at' h:mm a z", Locale.US)

    fun subject(route: SavedRoute, form: TripForm) =
        "Trip plan: ${route.name} (${SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(form.startAt))})"

    /** A short plain-text version for the email body, so the essentials show without opening the PDF. */
    fun body(route: SavedRoute, form: TripForm, pace: Pace, area: String? = null,
             water: Pair<Map<Int, String>, String?> = emptyMap<Int, String>() to null, gear: String? = null): String {
        val start = route.coords.firstOrNull()
        return buildString {
            appendLine("Here's my trip plan. The PDF attached has the map.")
            appendLine()
            appendLine("Route: ${route.name}" + if (route.outAndBack) " (out and back)" else "")
            turnLine(route)?.let { appendLine(it) }
            area?.takeIf { it.isNotBlank() }?.let { appendLine("Where: $it") }
            appendLine("Who: ${form.party.ifBlank { "(not given)" }}")
            if (form.phones.isNotBlank()) appendLine("Phone: ${form.phones}")
            appendLine("Start: ${dateTime.format(Date(form.startAt))}")
            appendLine("Expected back: ${dateTime.format(Date(form.backBy))}")
            appendLine()
            appendLine("IF YOU HAVEN'T HEARD FROM ME BY ${dateTime.format(Date(form.alarmAt)).uppercase()}, call 911 and ask for the county sheriff's search and rescue. Give them this plan.")
            appendLine()
            if (form.vehicle.isNotBlank()) appendLine("Vehicle: ${form.vehicle}")
            start?.let { appendLine("Parked at / trailhead: ${form.parkedAt.ifBlank { "route start" }} — %.5f, %.5f".format(Locale.US, it[0], it[1])) }
            start?.let { appendLine("Map link: https://www.google.com/maps/search/?api=1&query=%.5f,%.5f".format(Locale.US, it[0], it[1])) }
            appendLine("Distance: %.1f mi, +%,d ft climb, about %s".format(route.stats.distanceM / Geo.M_PER_MI,
                (route.stats.gainM * Geo.FT_PER_M).roundToInt(), Pace.format(pace.minutes(route.stats.distanceM, route.stats.gainM))))
            water.second?.let { appendLine(it) }
            if (route.camps.isNotEmpty()) {
                appendLine()
                TripDays.days(route, route.camps, pace).forEach { d -> appendLine(dayLine(route, d, water.first[d.day])) }
            }
            if (water.first.isNotEmpty() || water.second != null) appendLine(WATER_NOTE)
            if (route.notes.isNotBlank()) { appendLine(); appendLine("Route notes: ${route.notes}") }
            if (route.permits.isNotBlank()) { appendLine(); appendLine("Permits and rules: ${route.permits}") }
            gear?.let { appendLine(); appendLine("Gear packed: $it") }
            if (form.notes.isNotBlank()) { appendLine(); appendLine("Notes: ${form.notes}") }
        }
    }

    /** "Day 1: 8.2 mi, +2,100 ft, about 5h 10m. Camp: Lost Lake (44.12345, -121.12345)" */
    /** Under any water estimate, on the plan and in the email. */
    const val WATER_NOTE = "Water amounts are planning estimates from map data; mapped water can be missing or dry, so carry extra."

    /** "Out and back: in 6.1 mi to Minnie Scott Spring, then back the same way." */
    private fun turnLine(route: SavedRoute): String? {
        val t = route.turnM ?: return null
        val where = route.turnName ?: RouteStore.pointAt(route.coords, t)?.let { "%.5f, %.5f".format(Locale.US, it[0], it[1]) }
        return "Out and back: in %.1f mi".format(t / Geo.M_PER_MI) + (where?.let { " to $it" } ?: "") + ", then back the same way."
    }

    private fun dayLine(route: SavedRoute, d: TripDay, water: String? = null): String {
        if (d.rest) return "Day ${d.day}: at camp, night ${d.night} at ${d.endName ?: "mile %.1f".format(d.toM / Geo.M_PER_MI)} (no miles on the route)."
        val camp = if (d.endsAtCamp) RouteStore.pointAt(route.coords, d.toM)?.let { p ->
            " Camp: ${d.endName ?: "mile %.1f".format(d.toM / Geo.M_PER_MI)} (%.5f, %.5f)".format(Locale.US, p[0], p[1])
        } ?: "" else " Out at the end of the route."
        val rating = Difficulty.LABELS[Difficulty.rating(d.distanceM, d.gainM).second].lowercase()
        return "Day ${d.day}: %.1f mi, +%,d ft, about %s, %s.".format(d.distanceM / Geo.M_PER_MI, (d.gainM * Geo.FT_PER_M).roundToInt(),
            Pace.format(d.minutes), rating) + camp + (water?.let { " $it" } ?: "")
    }

    /** Writes the PDF. [map] is a snapshot of the route area with the route drawn on it, or null. */
    fun write(out: File, route: SavedRoute, form: TripForm, pace: Pace, map: Bitmap?, area: String? = null,
              water: Pair<Map<Int, String>, String?> = emptyMap<Int, String>() to null, gear: String? = null,
              turns: List<String> = emptyList()) {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
        var c = page.canvas
        val left = 40f
        val width = 532f
        var y = 40f
        val bottom = 752f

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(35, 39, 31) }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.rgb(107, 113, 106) }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(35, 39, 31) }
        val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        fun block(s: String, p: TextPaint, x: Float, top: Float, w: Float, maxLines: Int = 6): Float {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(x, top); l.draw(c); c.restore()
            return top + l.height
        }

        val made = "Made with TrailGuide GPS on ${SimpleDateFormat("MMM d, yyyy h:mm a z", Locale.US).format(Date())}. " +
            "Coordinates are latitude, longitude (WGS84). Map © OpenStreetMap contributors, USGS."
        val small = TextPaint(label).apply { textSize = 8f }
        fun footer() { c.drawText("$made  Page $pageNo", left, 776f, small) }
        /** Starts a new page when [h] more won't fit, so nothing is ever left off the plan. */
        fun need(h: Float) {
            if (y + h <= bottom) return
            footer()
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
            c = page.canvas
            c.drawText("TRIP PLAN, CONTINUED · ${route.name}".take(90), left, 40f, label)
            y = 56f
        }
        /** A paragraph in full, moved to the next page if it doesn't fit here. */
        fun para(s: String, p: TextPaint, gap: Float = 2f) {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            need(l.height.toFloat())
            c.save(); c.translate(left, y); l.draw(c); c.restore()
            y += l.height + gap
        }
        fun heading(s: String) { need(40f); c.drawText(s, left, y + 9, label); y += 12 }

        label.textSize = 10f
        c.drawText("TRIP PLAN", left, y, label)
        y = block(route.name, title, left, y + 4, width, 2) + 6
        area?.takeIf { it.isNotBlank() }?.let { y = block(it, text, left, y, width, 2) + 6 }
        turnLine(route)?.let { y = block(it, text, left, y, width, 2) + 6 }

        // The one thing someone at home needs to know.
        val alarm = "If you haven't heard from us by ${dateTime.format(Date(form.alarmAt))}, call 911 and ask for the county " +
            "sheriff's search and rescue. Give them this plan."
        val alarmPaint = TextPaint(bold).apply { color = Color.WHITE; textSize = 12f }
        val alarmLayout = StaticLayout.Builder.obtain(alarm, 0, alarm.length, alarmPaint, (width - 20).toInt()).build()
        val boxBottom = y + alarmLayout.height + 16
        c.drawRoundRect(RectF(left, y, left + width, boxBottom), 6f, 6f, Paint().apply { color = Color.rgb(183, 28, 28) })
        c.save(); c.translate(left + 10, y + 8); alarmLayout.draw(c); c.restore()
        y = boxBottom + 12

        // Details in two columns.
        val start = route.coords.firstOrNull()
        val rows = listOfNotNull(
            "Who" to form.party.ifBlank { "—" },
            form.phones.takeIf { it.isNotBlank() }?.let { "Phone" to it },
            "Start" to dateTime.format(Date(form.startAt)),
            "Expected back" to dateTime.format(Date(form.backBy)),
            start?.let { s ->
                val sun = com.trailguidegps.app.geo.Sun.day(s[0], s[1], form.startAt)
                val back = com.trailguidegps.app.geo.Sun.day(s[0], s[1], form.backBy)
                val t = SimpleDateFormat("h:mm a", Locale.US)
                "Daylight" to listOfNotNull(
                    sun.sunrise?.let { "Sunrise ${t.format(Date(it))}" }, sun.sunset?.let { "sunset ${t.format(Date(it))}" },
                    back.dusk?.let { d -> if (form.backBy > d) "expected back after dark" else null },
                ).joinToString(", ")
            },
            "Vehicle" to form.vehicle.ifBlank { "—" },
            start?.let {
                "Parked at" to (form.parkedAt.ifBlank { "Route start" } + "\n%.5f, %.5f".format(Locale.US, it[0], it[1]))
            },
        )
        val colW = (width - 16) / 2
        var colY = floatArrayOf(y, y)
        rows.forEachIndexed { i, (k, v) ->
            val col = i % 2
            val x = left + col * (colW + 16)
            c.drawText(k.uppercase(), x, colY[col] + 9, label)
            colY[col] = block(v, text, x, colY[col] + 12, colW, 3) + 8
        }
        y = maxOf(colY[0], colY[1]) + 2

        // The map.
        if (map != null) {
            val h = width * map.height / map.width
            c.drawBitmap(map, null, RectF(left, y, left + width, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(RectF(left, y, left + width, y + h), Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(200, 200, 195) })
            y += h + 10
        }

        // Numbers.
        val d = Difficulty.of(route.stats, route.terrain, if (route.camps.isNotEmpty()) TripDays.days(route, route.camps, pace) else emptyList())
        val summary = "%.1f mi · +%,d ft climb · −%,d ft descent · about %s at your pace · %s".format(
            route.stats.distanceM / Geo.M_PER_MI, (route.stats.gainM * Geo.FT_PER_M).roundToInt(),
            (route.stats.lossM * Geo.FT_PER_M).roundToInt(), Pace.format(pace.minutes(route.stats.distanceM, route.stats.gainM)),
            d.label + (d.hardestDay?.let { " (hardest day, day ${it.day})" } ?: ""),
        ) + (d.highPointFt?.let { " · high point %,d ft".format(it.roundToInt()) } ?: "")
        y = block(summary, bold, left, y, width, 2) + 4
        if (route.via.isNotEmpty()) y = block("Via " + route.via.joinToString(" → "), text, left, y, width, 2) + 6
        water.second?.let { y = block("$it $WATER_NOTE", text, left, y, width, 3) + 6 }

        // Elevation profile.
        val prof = route.stats.profile
        val tripDays = if (route.camps.isNotEmpty()) TripDays.days(route, route.camps, pace) else emptyList()
        if (prof.size >= 2) {
            val h = 60f
            need(h + 16)
            val minE = prof.minOf { it.second }
            val range = (prof.maxOf { it.second } - minE).coerceAtLeast(30.0)
            val maxD = prof.last().first.coerceAtLeast(1.0)
            val path = Path()
            prof.forEachIndexed { i, (dd, e) ->
                val px = left + (dd / maxD * width).toFloat()
                val py = y + h - ((e - minE) / range * (h - 4)).toFloat() - 2
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val fill = Path(path).apply { lineTo(left + width, y + h); lineTo(left, y + h); close() }
            c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 107, 63, 160) })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.rgb(107, 63, 160) })
            c.drawText("%,d ft".format((prof.maxOf { it.second } * Geo.FT_PER_M).roundToInt()), left, y + 8, label)
            c.drawText("%,d ft".format((minE * Geo.FT_PER_M).roundToInt()), left, y + h - 2, label)
            c.drawText("%.1f mi".format(maxD / Geo.M_PER_MI), left + width - 30, y + h + 10, label)
            // Camps on the profile.
            val campPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(107, 63, 160); strokeWidth = 1f }
            route.camps.sortedBy { it.alongM }.forEachIndexed { i, cp ->
                val px = left + (cp.alongM / maxD * width).toFloat()
                c.drawLine(px, y, px, y + h, campPaint)
                c.drawText(TripDays.nightsLabel(tripDays, i), px + 2, y + h + 10, label)
            }
            y += h + 16
        }

        // Day by day, for a multi-day trip. Everything from here runs onto more pages as needed.
        if (tripDays.isNotEmpty()) {
            heading("DAY BY DAY")
            tripDays.forEach { d -> para(dayLine(route, d, water.first[d.day]), text) }
            if (water.first.isNotEmpty()) para(WATER_NOTE, label)
            y += 6
        }

        // Each change of trail along the way, by mile.
        if (turns.isNotEmpty()) {
            heading("TRAIL BY TRAIL")
            turns.forEach { para(it, text) }
            y += 6
        }

        // Your notes on the route: road conditions, bugs, permits.
        if (route.notes.isNotBlank()) {
            heading("ROUTE NOTES")
            para(route.notes, text, 6f)
        }

        if (route.permits.isNotBlank()) {
            heading("PERMITS AND RULES")
            para(route.permits, text, 6f)
        }

        // What's ticked on the packing checklist: useful to whoever comes looking.
        if (gear != null) {
            heading("GEAR PACKED")
            para(gear, text, 6f)
        }

        // Named points along the way.
        val named = route.points.filter { it.waypointName != null }
        if (named.isNotEmpty()) {
            heading("POINTS ON THE ROUTE")
            named.forEach { p -> para("${p.waypointName}: %.5f, %.5f".format(Locale.US, p.lat, p.lon), text) }
            y += 6
        }
        if (form.notes.isNotBlank()) {
            heading("NOTES")
            para(form.notes, text, 4f)
        }

        footer()
        doc.finishPage(page)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
