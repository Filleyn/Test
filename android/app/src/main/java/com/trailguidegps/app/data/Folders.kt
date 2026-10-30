package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A trip folder: any mix of routes, tracks and waypoints. */
data class Folder(val id: String, val name: String, val createdAt: Long)

/**
 * Trip folders, kept in one small file: the folders, and which folder each item is in
 * (an item is in at most one). Items themselves stay where they are; deleting a
 * folder never deletes what's in it.
 */
class FolderStore(context: Context) {
    private val file = File(context.filesDir, FILE)
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()
    /** Item key ("route:<id>", "track:<id>", "wp:<id>") → folder id. */
    private val _members = MutableStateFlow<Map<String, String>>(emptyMap())
    val members: StateFlow<Map<String, String>> = _members.asStateFlow()

    init { reload() }

    fun reload() {
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("folders") ?: JSONArray()
            _folders.value = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { Folder(it.getString("id"), it.getString("name"), it.optLong("created")) }
            }.sortedBy { it.name.lowercase() }
            val items = o.optJSONObject("items") ?: JSONObject()
            _members.value = items.keys().asSequence().associateWith { items.getString(it) }
        }.onFailure { _folders.value = emptyList(); _members.value = emptyMap() }
    }

    private fun save() {
        val o = JSONObject()
            .put("folders", JSONArray().apply {
                _folders.value.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.createdAt)) }
            })
            .put("items", JSONObject().apply { _members.value.forEach { (k, v) -> put(k, v) } })
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun create(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name.trim().ifBlank { "New folder" }, System.currentTimeMillis())
        _folders.value = (_folders.value + f).sortedBy { it.name.lowercase() }
        save()
        return f
    }

    fun rename(id: String, name: String) {
        _folders.value = _folders.value.map { if (it.id == id) it.copy(name = name.trim()) else it }.sortedBy { it.name.lowercase() }
        save()
    }

    /** Removes the folder; its items stay, just not in a folder. */
    fun delete(id: String) {
        _folders.value = _folders.value.filter { it.id != id }
        _members.value = _members.value.filterValues { it != id }
        save()
    }

    fun folderOf(key: String): Folder? = _members.value[key]?.let { fid -> _folders.value.firstOrNull { it.id == fid } }

    /** Puts an item in a folder, or takes it out with null. */
    fun put(key: String, folderId: String?) {
        _members.value = if (folderId == null) _members.value - key else _members.value + (key to folderId)
        save()
    }

    fun keysIn(folderId: String): List<String> = _members.value.filterValues { it == folderId }.keys.toList()

    companion object {
        const val FILE = "folders.json"
        fun routeKey(id: String) = "route:$id"
        fun trackKey(id: String) = "track:$id"
        fun waypointKey(id: String) = "wp:$id"
    }
}
