package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Your own notes on water sources ("flowing, cold"), keyed by the map's source id, with the date written. */
class WaterNotes(context: Context) {
    data class Note(val text: String, val date: String)

    private val file = File(context.filesDir, "water-notes.json")
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<Map<String, Note>> = _notes.asStateFlow()

    fun get(id: String?): Note? = id?.let { _notes.value[it] }

    /** Re-reads the file (after restoring a backup). */
    fun reload() { _notes.value = load() }

    fun set(id: String, text: String) {
        val m = _notes.value.toMutableMap()
        if (text.isBlank()) m.remove(id)
        else m[id] = Note(text.trim(), SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date()))
        _notes.value = m
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONObject().put("text", v.text).put("date", v.date)) }
        val tmp = File(file.parentFile, "water-notes.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun load(): Map<String, Note> {
        if (!file.exists()) return emptyMap()
        val o = runCatching { JSONObject(file.readText()) }.getOrElse { SafeFile.setAside(file); return emptyMap() }
        val out = LinkedHashMap<String, Note>()
        o.keys().forEach { k -> runCatching { o.getJSONObject(k).let { n -> out[k] = Note(n.getString("text"), n.optString("date")) } } }
        return out
    }
}
