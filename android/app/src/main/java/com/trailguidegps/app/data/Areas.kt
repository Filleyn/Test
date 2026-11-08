package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where each saved route, track, waypoint and trail note is, as one line like
 * "Three Sisters Wilderness · Willamette National Forest · 9 mi W of Sisters".
 * Worked out offline from the downloaded maps and kept in areas.json, so it can be
 * rebuilt any time (it's not in backups).
 */
class AreaStore(context: Context) {
    /**
     * [line] is what lists show; [names] are the land names in it (for the area filter);
     * [lat]/[lon] is the start, for sorting by distance.
     */
    data class Info(val line: String, val names: List<String>, val lat: Double, val lon: Double, val sig: String)

    private val file = File(context.filesDir, "areas.json")
    private val _areas = MutableStateFlow(load())
    val areas: StateFlow<Map<String, Info>> = _areas.asStateFlow()

    @Synchronized
    fun update(changes: Map<String, Info>, keep: Set<String>) {
        val next = _areas.value.filterKeys { it in keep } + changes
        if (next == _areas.value) return
        _areas.value = next
        runCatching {
            val o = JSONObject()
            next.forEach { (k, i) ->
                o.put(k, JSONObject().put("line", i.line).put("names", JSONArray(i.names)).put("lat", i.lat).put("lon", i.lon).put("sig", i.sig))
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private fun load(): Map<String, Info> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val i = o.getJSONObject(k)
            val n = i.optJSONArray("names") ?: JSONArray()
            Info(i.optString("line"), List(n.length()) { n.getString(it) }, i.optDouble("lat"), i.optDouble("lon"), i.optString("sig"))
        }
    }.getOrDefault(emptyMap())

    companion object {
        /** Special areas worth naming: wilderness, monuments, recreation and conservation areas. */
        private val SPECIAL_SHOWN = setOf("WA", "NM", "NRA", "NCA")
        private val BIG_AGENCIES = setOf("USFS", "NPS", "FWS")

        /** The forest, park or land a spot is on, or null for private land and plain ground. */
        private fun mainLand(h: LandLookup.Hit): String? {
            val boundary = h.boundary?.get("name")?.takeIf { it.isNotBlank() }
            val land = h.land
            val landName = land?.get("name")?.takeIf { it.isNotBlank() && land["mgr"] != "PVT" }
            return when {
                landName != null && boundary != null && land?.get("mgr") in BIG_AGENCIES -> boundary
                landName != null -> landName
                boundary != null -> boundary
                land?.get("mgr") == "BLM" -> "BLM land"
                else -> null
            }
        }

        private fun special(h: LandLookup.Hit): String? {
            val s = h.special ?: return null
            val n = s["name"]?.takeIf { it.isNotBlank() } ?: return null
            if (s["des"] !in SPECIAL_SHOWN || n.contains("Proposed", true) || n.contains("Recommended", true)) return null
            return n
        }

        /**
         * One line from the land at a few spots along an item (start first) and the town
         * near its start: the wilderness it goes into, the forest or park, then the town.
         */
        fun describe(hits: List<LandLookup.Hit?>, near: String?, lat: Double, lon: Double, sig: String): Info {
            val specials = LinkedHashMap<String, Int>()
            val mains = LinkedHashMap<String, Int>()
            for (h in hits.filterNotNull()) {
                special(h)?.let { specials[it] = (specials[it] ?: 0) + 1 }
                mainLand(h)?.let { mains[it] = (mains[it] ?: 0) + 1 }
            }
            val names = specials.entries.sortedByDescending { it.value }.take(2).map { it.key } +
                mains.entries.sortedByDescending { it.value }.take(2).map { it.key }
            val distinct = names.distinct()
            val line = (distinct + listOfNotNull(near)).joinToString(" · ")
            return Info(line, distinct, lat, lon, sig)
        }

        /** Up to [n] spots spread along a line, start and end included. */
        fun samples(coords: List<DoubleArray>, n: Int = 5): List<DoubleArray> {
            if (coords.size <= n) return coords
            return List(n) { i -> coords[(i * (coords.size - 1)) / (n - 1)] }
        }
    }
}
