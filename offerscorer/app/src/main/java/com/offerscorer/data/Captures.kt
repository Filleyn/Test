package com.offerscorer.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved screen text from the Uber app, kept so the reader can be fixed when Uber changes
 * its wording. Only text is saved, never images. Stored in app-private storage.
 */
class Captures(context: Context) {
    private val dir = File(context.filesDir, "captures").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var lastHash = 0

    fun save(kind: String, reason: String, lines: List<String>) {
        val body = lines.joinToString("\n")
        val h = body.hashCode()
        if (h == lastHash) return
        lastHash = h
        val now = System.currentTimeMillis()
        val f = File(dir, "${now}_$kind.txt")
        f.writeText("# ${stamp.format(Date(now))}  $kind: $reason\n$body\n")
        // Prune each kind separately so capture-mode screens never push out unreadable offers.
        val mine = list().filter { it.name.endsWith("_$kind.txt") }
        val keep = if (kind == "unreadable") 100 else 300
        if (mine.size > keep) mine.drop(keep).forEach { it.delete() }
    }

    /** Newest first. */
    fun list(): List<File> = (dir.listFiles()?.toList() ?: emptyList()).sortedByDescending { it.name }

    fun count(kind: String? = null): Int = list().count { kind == null || it.name.endsWith("_$kind.txt") }

    fun exportText(): String = list().reversed().joinToString("\n\n") { it.readText() }

    fun clear() { list().forEach { it.delete() } }
}
