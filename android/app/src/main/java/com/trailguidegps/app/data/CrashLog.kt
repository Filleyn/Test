package com.trailguidegps.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.trailguidegps.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notes why the app last closed when it wasn't you closing it: a crash (with its stack),
 * a native crash, running out of memory, or not responding. Shown in Settings so it can
 * be copied and sent.
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last-crash.txt")
    private fun seenFile(c: Context) = File(c.filesDir, "last-exit-seen.txt")
    private fun stamp(t: Long) = SimpleDateFormat("MMM d, h:mm:ss a", Locale.US).format(Date(t))

    /** Catches Kotlin/Java crashes and writes the stack before the app closes. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(app).writeText(
                    "TrailGuide ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                        "Crashed ${stamp(System.currentTimeMillis())} on thread ${thread.name}\n\n" +
                        e.stackTraceToString().take(12_000),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Asks Android why the app last stopped. Native crashes, low memory and freezes
     * don't reach the handler above, so they're picked up here on the next launch.
     */
    fun checkLastExit(context: Context) {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val seen = seenFile(context).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            if (last.timestamp <= seen) return
            seenFile(context).writeText(last.timestamp.toString())
            val why = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (map engine or system library)"
                ApplicationExitInfo.REASON_ANR -> "App not responding"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: phone low on memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: too much CPU or memory"
                ApplicationExitInfo.REASON_SIGNALED -> "Stopped by a signal (often memory)"
                else -> return // Closed normally, by you, or by an update.
            }
            val existing = file(context).takeIf { it.exists() }?.readText().orEmpty()
            // A Kotlin crash was already written with its full stack by the handler.
            if (last.reason == ApplicationExitInfo.REASON_CRASH && existing.isNotBlank() &&
                file(context).lastModified() >= last.timestamp - 10_000
            ) return
            val trace = if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || last.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { last.traceInputStream?.use { it.readBytes().decodeToString().take(12_000) } }.getOrNull()
            } else null
            file(context).writeText(
                "TrailGuide ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                    "$why at ${stamp(last.timestamp)}\n" +
                    "Details: ${last.description ?: "none"} · status ${last.status} · memory ${last.pss / 1024} MB (pss), ${last.rss / 1024} MB (rss)\n" +
                    (trace?.let { "\n$it" } ?: ""),
            )
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    fun clear(context: Context) { file(context).delete() }
}
