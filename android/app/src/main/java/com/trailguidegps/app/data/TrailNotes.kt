package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A note pinned to a spot: trail conditions, a good campsite, a washout. Can have photos. */
data class TrailNote(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val lat: Double,
    val lon: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val photos: List<String> = emptyList(),
) {
    /** The first line, for titles and lists. */
    val title: String get() = text.lineSequence().firstOrNull()?.trim()?.take(60)?.ifBlank { null } ?: "Trail note"
}

/** Trail notes saved on the phone as one small JSON file. */
class TrailNoteStore(context: Context) {
    private val file = File(context.filesDir, FILE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TrailNote>> = _items.asStateFlow()

    fun upsert(n: TrailNote) {
        _items.value = (_items.value.filter { it.id != n.id } + n).sortedBy { it.createdAt }
        save()
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    fun reload() { _items.value = load() }

    private fun load(): List<TrailNote> {
        if (!file.exists()) return emptyList()
        // Bad entries are skipped; an unreadable file is set aside before a save could replace it.
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { SafeFile.setAside(file); return emptyList() }
        return (0 until arr.length()).mapNotNull { i -> runCatching {
            val o = arr.getJSONObject(i)
            val ph = o.optJSONArray("photos") ?: JSONArray()
            TrailNote(o.getString("id"), o.getString("text"), o.getDouble("lat"), o.getDouble("lon"),
                o.optLong("created", System.currentTimeMillis()), (0 until ph.length()).map { ph.getString(it) })
        }.getOrNull() }
    }

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("text", n.text).put("lat", n.lat).put("lon", n.lon)
                .put("created", n.createdAt).put("photos", JSONArray(n.photos)))
        }
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val FILE = "trail-notes.json"

        fun geoJson(notes: List<TrailNote>): String {
            val f = JSONArray()
            notes.forEach { n ->
                f.put(JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("id", n.id).put("title", n.title).put("photo", n.photos.isNotEmpty()))
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(n.lon).put(n.lat))))
            }
            return JSONObject().put("type", "FeatureCollection").put("features", f).toString()
        }
    }
}
