package com.trailguidegps.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backs up everything you've made (routes, tracks, waypoints, water notes, pace
 * and settings) to one .zip file, saved wherever you choose through Android's
 * file picker: this phone, Google Drive, OneDrive, an SD card. Map files and
 * satellite areas aren't included; they download again. A GPX copy of
 * everything is inside too, for other hiking apps.
 */
object Backup {
    data class Counts(val routes: Int, val tracks: Int, val waypoints: Int, val notes: Int)

    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val PREFS = listOf("pace", "settings", "layers")

    fun write(context: Context, out: OutputStream, gpx: String): Counts {
        val dir = context.filesDir
        var routes = 0
        var tracks = 0
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            File(dir, "routes").listFiles { f -> f.name.endsWith(".json") }?.forEach { put("routes/${it.name}", it.readBytes()); routes++ }
            File(dir, "tracks").listFiles { f -> f.name.endsWith(".json") }?.forEach { put("tracks/${it.name}", it.readBytes()); tracks++ }
            File(dir, "waypoints.json").takeIf { it.exists() }?.let { put("waypoints.json", it.readBytes()) }
            File(dir, "water-notes.json").takeIf { it.exists() }?.let { put("water-notes.json", it.readBytes()) }
            PREFS.forEach { name ->
                val o = JSONObject()
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    o.put(k, JSONObject().put("type", v?.javaClass?.simpleName ?: "null").put("value", v ?: JSONObject.NULL))
                }
                put("prefs/$name.json", o.toString().toByteArray())
            }
            put("TrailGuide everything.gpx", gpx.toByteArray())
            val waypoints = runCatching { JSONArray(File(dir, "waypoints.json").readText()).length() }.getOrDefault(0)
            val notes = runCatching { JSONObject(File(dir, "water-notes.json").readText()).length() }.getOrDefault(0)
            put("backup.json", JSONObject().put("app", "TrailGuide GPS").put("format", 1)
                .put("created", System.currentTimeMillis()).put("routes", routes).put("tracks", tracks)
                .put("waypoints", waypoints).put("notes", notes).toString().toByteArray())
            return Counts(routes, tracks, waypoints, notes)
        }
    }

    /**
     * Restores a backup, merging with what's on the phone: items in the backup are
     * added, and an item with the same id is replaced by the backup's copy. Nothing
     * already on the phone is deleted. Returns what was restored.
     */
    fun restore(context: Context, input: InputStream): Counts {
        val dir = context.filesDir
        var routes = 0
        var tracks = 0
        var waypoints = 0
        var notes = 0
        var sawManifest = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || name.contains("..") || name.startsWith("/")) continue
                val bytes = zip.readBytes()
                when {
                    name == "backup.json" -> sawManifest = JSONObject(String(bytes)).optString("app") == "TrailGuide GPS"
                    name.startsWith("routes/") || name.startsWith("tracks/") -> {
                        val folder = name.substringBefore('/')
                        val id = name.substringAfter('/').removeSuffix(".json")
                        if (!SAFE_ID.matches(id) || runCatching { JSONObject(String(bytes)) }.isFailure) continue
                        File(dir, folder).mkdirs()
                        File(dir, "$folder/$id.json").writeBytes(bytes)
                        if (folder == "routes") routes++ else tracks++
                    }
                    name == "waypoints.json" -> {
                        val incoming = JSONArray(String(bytes))
                        val f = File(dir, "waypoints.json")
                        val current = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
                        val byId = LinkedHashMap<String, JSONObject>()
                        for (i in 0 until current.length()) current.getJSONObject(i).let { byId[it.getString("id")] = it }
                        for (i in 0 until incoming.length()) incoming.getJSONObject(i).let { byId[it.getString("id")] = it; waypoints++ }
                        writeAtomically(f, JSONArray(byId.values).toString())
                    }
                    name == "water-notes.json" -> {
                        val incoming = JSONObject(String(bytes))
                        val f = File(dir, "water-notes.json")
                        val current = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
                        incoming.keys().forEach { k -> if (!current.has(k)) { current.put(k, incoming.get(k)); notes++ } }
                        writeAtomically(f, current.toString())
                    }
                    name.startsWith("prefs/") -> {
                        val pref = name.removePrefix("prefs/").removeSuffix(".json")
                        if (pref !in PREFS) continue
                        val o = JSONObject(String(bytes))
                        val ed = context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit()
                        o.keys().forEach { k ->
                            val v = o.getJSONObject(k)
                            when (v.optString("type")) {
                                "Boolean" -> ed.putBoolean(k, v.getBoolean("value"))
                                "Integer" -> ed.putInt(k, v.getInt("value"))
                                "Long" -> ed.putLong(k, v.getLong("value"))
                                "Float" -> ed.putFloat(k, v.getDouble("value").toFloat())
                                "String" -> ed.putString(k, v.getString("value"))
                            }
                        }
                        ed.apply()
                    }
                }
            }
        }
        require(sawManifest) { "That file isn't a TrailGuide backup." }
        return Counts(routes, tracks, waypoints, notes)
    }

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
