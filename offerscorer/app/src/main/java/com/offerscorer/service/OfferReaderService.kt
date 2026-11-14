package com.offerscorer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.offerscorer.core.OfferParser
import com.offerscorer.core.ParseResult
import com.offerscorer.core.Score
import com.offerscorer.core.Scorer
import com.offerscorer.data.Captures
import com.offerscorer.data.Db
import com.offerscorer.data.Decision
import com.offerscorer.data.Prefs
import com.offerscorer.data.RulesRepo

/**
 * Reads the text of offer cards in the Uber Driver app and shows the score bubble.
 * Read-only: it never taps, swipes or types anything. Screen text is processed on
 * the phone and only the parsed offer is stored.
 */
class OfferReaderService : AccessibilityService() {

    companion object {
        @Volatile var instance: OfferReaderService? = null
            private set
        @Volatile var lastStatus: String = "Waiting for an offer"
            private set
        private const val MIN_INTERVAL_MS = 350L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var captures: Captures
    private lateinit var bubble: Bubble
    private var parser = OfferParser(com.offerscorer.core.ParserRules())
    private var packages: Set<String> = emptySet()

    private var currentSig: String? = null
    private var currentId: Long = -1
    private var lastOfferSeenAt = 0L
    private var lastUnreadableAt = 0L
    private var watchId: Long = -1
    private var watchUntil = 0L
    private var lastLoggedSig: String? = null
    private var lastLoggedAt = 0L
    private var lastLoggedId = -1L

    private var processScheduled = false
    private var lastProcessAt = 0L
    private var unreadableShowing = false
    private var lastUnreadableSeenAt = 0L
    private var lastScreenCaptureAt = 0L

    private val processRunnable = Runnable { processScheduled = false; process() }
    private val tick = object : Runnable {
        override fun run() {
            if (currentSig != null || watchId >= 0 || unreadableShowing) process()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        db = Db.get(this)
        captures = Captures(this)
        bubble = Bubble(this)
        reloadRules()
        instance = this
        handler.postDelayed(tick, 1000)
    }

    fun reloadRules() {
        val loaded = RulesRepo(this).load()
        parser = OfferParser(loaded.rules)
        packages = loaded.rules.packages.toSet()
        serviceInfo?.let { info ->
            info.packageNames = loaded.rules.packages.toTypedArray()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg != null && pkg !in packages && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Throttle, don't debounce: Uber's map sends a steady stream of events, and restarting
        // the timer on each one would postpone reading the screen for as long as they keep coming.
        if (processScheduled) return
        processScheduled = true
        val sinceLast = SystemClock.uptimeMillis() - lastProcessAt
        handler.postDelayed(processRunnable, (MIN_INTERVAL_MS - sinceLast).coerceIn(100L, MIN_INTERVAL_MS))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::bubble.isInitialized) bubble.hide()
        instance = null
        super.onDestroy()
    }

    /** Shows a sample bubble so the position and look can be checked. */
    fun showTestBubble(score: Score) = bubble.show(score)

    private fun collectLines(): List<String> {
        val out = ArrayList<String>(120)
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() in packages) roots.add(r)
            }
        }
        if (roots.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() in packages) roots.add(r)
        }
        for (r in roots) walk(r, out)
        return out
    }

    private fun walk(root: AccessibilityNodeInfo, out: MutableList<String>) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1200) {
            val (n, depth) = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()
            val cd = n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t)
            if (!cd.isNullOrBlank() && cd != t) out.add(cd)
            if (depth < 40) {
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
    }

    private fun process() {
        val now = System.currentTimeMillis()
        lastProcessAt = SystemClock.uptimeMillis()
        val lines = collectLines()
        val r = parser.parse(lines)

        // Capture mode: always keep screens that look like offers; sample the rest so the
        // constantly changing map screen doesn't fill the folder.
        if (now < prefs.captureUntil && lines.isNotEmpty() &&
            (r !is ParseResult.NotOffer || now - lastScreenCaptureAt > 2000)) {
            lastScreenCaptureAt = now
            captures.save("screen", "capture mode", lines)
        }

        when (r) {
            is ParseResult.Found -> {
                lastOfferSeenAt = now
                unreadableShowing = false
                val sig = r.offer.signature
                if (sig == currentSig) return
                val score = Scorer.score(r.offer, prefs.score) ?: return
                // A new offer replaced the previous one without the card closing in between.
                if (currentSig != null && currentId >= 0) db.setDecisionIfShown(currentId, Decision.NOT_ACCEPTED)
                currentSig = sig
                watchId = -1
                bubble.show(score)
                lastStatus = "Last offer: ${Scorer.money(r.offer.fare)}, ${score.verdict.label}"
                currentId = if (sig == lastLoggedSig && now - lastLoggedAt < 60_000) lastLoggedId
                else db.insertOffer(r.offer, score, "screen").also {
                    lastLoggedSig = sig; lastLoggedAt = now; lastLoggedId = it
                }
            }
            is ParseResult.Unreadable -> {
                lastUnreadableSeenAt = now
                if (currentSig == null && now - lastUnreadableAt > 5000) {
                    lastUnreadableAt = now
                    unreadableShowing = true
                    bubble.showUnreadable(r.reason)
                    captures.save("unreadable", r.reason, lines)
                    lastStatus = "Couldn't read the last offer: ${r.reason}"
                }
            }
            ParseResult.NotOffer -> {
                if (unreadableShowing && now - lastUnreadableSeenAt > 1500) {
                    unreadableShowing = false
                    if (currentSig == null) bubble.hide()
                }
                if (currentSig != null && now - lastOfferSeenAt > 1500) {
                    // The offer card closed. Watch the next screens to see if it was accepted.
                    currentSig = null
                    bubble.hide()
                    watchId = currentId
                    watchUntil = now + 20_000
                }
                if (watchId >= 0) {
                    if (parser.looksAccepted(lines)) {
                        db.setDecision(watchId, Decision.ACCEPTED); watchId = -1
                    } else if (now > watchUntil) {
                        db.setDecisionIfShown(watchId, Decision.NOT_ACCEPTED); watchId = -1
                    }
                }
            }
        }
    }
}
