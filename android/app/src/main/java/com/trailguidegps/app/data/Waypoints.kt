package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lat: Double,
    val lon: Double,
    val eleM: Double? = null,
    val color: String = WAYPOINT_COLORS.first(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Pin colors offered in the editor: orange, blue, green, purple, red. */
val WAYPOINT_COLORS = listOf("#B4491A", "#1A73E8", "#2E8B57", "#7B3FA0", "#C62828")

/** Waypoints saved on the phone as one small JSON file. */
class WaypointStore(context: Context) {
    private val file = File(context.filesDir, "waypoints.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Waypoint>> = _items.asStateFlow()

    fun nextName(): String = "Waypoint ${_items.value.size + 1}"

    fun upsert(w: Waypoint) {
        val list = _items.value.filter { it.id != w.id } + w
        _items.value = list.sortedBy { it.createdAt }
        save()
    }

    fun addAll(ws: List<Waypoint>) {
        _items.value = (_items.value + ws).sortedBy { it.createdAt }
        save()
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    fun get(id: String): Waypoint? = _items.value.firstOrNull { it.id == id }

    /** Re-reads the file (after restoring a backup). */
    fun reload() { _items.value = load() }

    private fun load(): List<Waypoint> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Waypoint(
                id = o.getString("id"), name = o.getString("name"),
                lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                eleM = if (o.has("ele") && !o.isNull("ele")) o.getDouble("ele") else null,
                color = o.optString("color", WAYPOINT_COLORS.first()),
                note = o.optString("note", ""),
                createdAt = o.optLong("created", System.currentTimeMillis()),
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { w ->
            arr.put(JSONObject().put("id", w.id).put("name", w.name).put("lat", w.lat).put("lon", w.lon)
                .put("ele", w.eleM ?: JSONObject.NULL).put("color", w.color).put("note", w.note).put("created", w.createdAt))
        }
        // Write to a temp file first so a crash mid-write can't wipe the list.
        val tmp = File(file.parentFile, "waypoints.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** GeoJSON for the map layer. */
    fun geoJson(): String {
        val features = JSONArray()
        _items.value.forEach { w ->
            features.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(w.lon).put(w.lat)))
                .put("properties", JSONObject().put("id", w.id).put("name", w.name).put("color", w.color).put("wp", true)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }
}
