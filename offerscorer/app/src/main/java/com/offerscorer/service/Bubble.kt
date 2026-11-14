package com.offerscorer.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.offerscorer.core.Score
import com.offerscorer.core.Scorer
import com.offerscorer.core.Verdict
import com.offerscorer.data.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating score badge. Drawn as an accessibility overlay, so it needs no
 * "draw over other apps" permission. Drag to move it; tap to hide it.
 * It never touches anything in the Uber app.
 */
class Bubble(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val big = TextView(ctx)
    private val label = TextView(ctx)
    private val detail = TextView(ctx)
    private val autoHide = Runnable { hide() }

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun show(score: Score) {
        val bg = when (score.verdict) {
            Verdict.GOOD -> GOOD; Verdict.BORDERLINE -> WARN; Verdict.SKIP -> BAD
        }
        val hr = score.hourly
        render(
            bg,
            (if (hr < 0) "-$" else "$") + abs(hr).roundToInt() + "/hr",
            score.verdict.label.uppercase(),
            "Net ${Scorer.money(score.net)} · ${Scorer.fmt1(score.miles)} mi · ${Scorer.fmt0(score.minutes)} min",
        )
    }

    fun showUnreadable(reason: String) {
        render(GRAY, "?", "COULDN'T READ", reason)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun render(bg: Int, bigText: String, labelText: String, detailText: String) {
        handler.removeCallbacks(autoHide)
        if (root == null) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                elevation = dp(6).toFloat()
            }
            big.apply { setTextColor(Color.WHITE); textSize = 30f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
            label.apply { setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }
            detail.apply { setTextColor(0xE6FFFFFF.toInt()); textSize = 12f; maxWidth = dp(220) }
            r.addView(big); r.addView(label); r.addView(detail)

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = if (prefs.bubbleX >= 0) prefs.bubbleX else dp(12)
                y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(96)
            }
            attachDrag(r, p)
            try { wm.addView(r, p) } catch (e: Exception) { return }
            root = r; params = p
        }
        root?.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
        big.text = bigText; label.text = labelText; detail.text = detailText
        handler.postDelayed(autoHide, 30_000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View, p: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) dragging = true
                    if (dragging) {
                        p.x = (startX - dx).roundToInt().coerceAtLeast(0) // gravity END: x grows leftward
                        p.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { prefs.bubbleX = p.x; prefs.bubbleY = p.y } else hide()
                    true
                }
                else -> false
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; params = null
    }

    companion object {
        const val GOOD = 0xFF17824A.toInt()
        const val WARN = 0xFFB07A06.toInt()
        const val BAD = 0xFFBF3626.toInt()
        const val GRAY = 0xFF55606C.toInt()
    }
}
