package com.trailguidegps.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reads and writes GPX 1.1, the format other hiking apps and Garmin devices use. */
object Gpx {
    data class Imported(val waypoints: List<Waypoint>, val tracks: List<Pair<String, List<TrackPoint>>>)

    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun write(waypoints: List<Waypoint>, tracks: List<Pair<String, List<TrackPoint>>>): String {
        val f = iso()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="TrailGuide GPS" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        waypoints.forEach { w ->
            sb.append("""  <wpt lat="${w.lat}" lon="${w.lon}">""")
            w.eleM?.let { sb.append("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
            sb.append("<time>${f.format(Date(w.createdAt))}</time><name>${esc(w.name)}</name>")
            if (w.note.isNotBlank()) sb.append("<desc>${esc(w.note)}</desc>")
            sb.append("</wpt>\n")
        }
        tracks.forEach { (name, pts) ->
            sb.append("  <trk><name>${esc(name)}</name><trkseg>\n")
            pts.forEach { p ->
                sb.append("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.eleM?.let { sb.append("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
                sb.append("<time>${f.format(Date(p.time))}</time></trkpt>\n")
            }
            sb.append("  </trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Reads waypoints, tracks and routes (routes come in as tracks for now). */
    fun read(input: InputStream): Imported {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val f = iso()
        val waypoints = mutableListOf<Waypoint>()
        val tracks = mutableListOf<Pair<String, List<TrackPoint>>>()

        var inWpt = false
        var inTrk = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var time: Long? = null
        var name: String? = null
        var desc: String? = null
        var trkName: String? = null
        var trkPts = mutableListOf<TrackPoint>()
        var text = ""
        var fallbackTime = System.currentTimeMillis()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    when (parser.name) {
                        "wpt", "trkpt", "rtept" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ele = null; time = null
                            if (parser.name == "wpt") { inWpt = true; name = null; desc = null }
                        }
                        "trk", "rte" -> { inTrk = true; trkName = null; trkPts = mutableListOf() }
                    }
                }
                XmlPullParser.TEXT -> text += parser.text
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "ele" -> ele = text.trim().toDoubleOrNull()
                        "time" -> time = runCatching { f.parse(text.trim().replace(Regex("\\.\\d+Z$"), "Z"))?.time }.getOrNull()
                        "name" -> if (inWpt) name = text.trim() else if (inTrk && trkName == null) trkName = text.trim()
                        "desc", "cmt" -> if (inWpt && desc == null) desc = text.trim()
                        "wpt" -> {
                            waypoints += Waypoint(name = name ?: "Imported point", lat = lat, lon = lon, eleM = ele,
                                note = desc ?: "", createdAt = time ?: System.currentTimeMillis())
                            inWpt = false
                        }
                        "trkpt", "rtept" -> {
                            // Routes have no times; space them a second apart so order is kept.
                            fallbackTime += 1000
                            trkPts += TrackPoint(lat, lon, ele, time ?: fallbackTime)
                        }
                        "trk", "rte" -> {
                            if (trkPts.size >= 2) tracks += (trkName ?: "Imported track") to trkPts.toList()
                            inTrk = false
                        }
                    }
                    text = ""
                }
            }
        }
        return Imported(waypoints, tracks)
    }
}
