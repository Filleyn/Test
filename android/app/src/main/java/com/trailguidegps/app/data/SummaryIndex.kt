package com.trailguidegps.app.data

import org.json.JSONObject
import java.io.File

/**
 * Remembers the list-row summary of each saved file (a track, a route) along with the file's
 * size and time, so opening the app or saving one item doesn't mean re-reading every file in
 * full. A file that changed, or wasn't seen before, is read again. The index is only a cache
 * (kept in the cache folder): losing it just means one slow read.
 */
class SummaryIndex<T>(
    private val file: File,
    /** Bump when the summary's contents change, so old entries are rebuilt. */
    private val version: Int,
    private val write: (T) -> JSONObject,
    private val read: (JSONObject) -> T,
) {
    private class Entry<T>(val modified: Long, val length: Long, val value: T)
    private val entries = HashMap<String, Entry<T>>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            if (o.optInt("v") == version) {
                val items = o.getJSONObject("items")
                items.keys().forEach { k ->
                    runCatching {
                        val e = items.getJSONObject(k)
                        entries[k] = Entry(e.getLong("m"), e.getLong("n"), read(e.getJSONObject("s")))
                    }
                }
            }
        }
    }

    /** Forget a file's entry (it's about to change or go). */
    @Synchronized fun drop(name: String) { entries.remove(name) }

    /** The summary of every file in [files], reading with [parse] only those that are new or changed. */
    @Synchronized
    fun scan(files: Array<File>, parse: (File) -> T?): List<T> {
        var dirty = false
        val out = ArrayList<T>(files.size)
        val seen = HashSet<String>()
        for (f in files) {
            seen += f.name
            val e = entries[f.name]
            if (e != null && e.modified == f.lastModified() && e.length == f.length()) { out += e.value; continue }
            val v = parse(f)
            if (v != null) { entries[f.name] = Entry(f.lastModified(), f.length(), v); out += v } else entries.remove(f.name)
            dirty = true
        }
        if (entries.keys.retainAll(seen)) dirty = true
        if (dirty) runCatching {
            val items = JSONObject()
            entries.forEach { (k, e) -> items.put(k, JSONObject().put("m", e.modified).put("n", e.length).put("s", write(e.value))) }
            SafeFile.write(file, JSONObject().put("v", version).put("items", items).toString())
        }
        return out
    }
}
