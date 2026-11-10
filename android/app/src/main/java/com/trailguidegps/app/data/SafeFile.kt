package com.trailguidegps.app.data

import java.io.File

/** Small helpers so a bad file or a failed write can't quietly cost you what you've saved. */
object SafeFile {
    /**
     * Writes [text] to [file] by way of a temp file, so a crash mid-write can't leave it half
     * written. Throws if the write or the swap fails (a full phone), leaving the old file as it was.
     */
    fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (tmp.length() == 0L && text.isNotEmpty()) throw java.io.IOException("Couldn't write ${file.name}")
        if (!tmp.renameTo(file)) throw java.io.IOException("Couldn't save ${file.name}")
    }

    /**
     * Called when [file] exists but can't be read as a whole: sets a copy aside
     * ("waypoints.json.unreadable-<time>") before anything overwrites it, so it can be recovered.
     */
    fun setAside(file: File) {
        runCatching {
            if (file.exists() && file.length() > 2) file.copyTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"), overwrite = true)
        }
    }
}
