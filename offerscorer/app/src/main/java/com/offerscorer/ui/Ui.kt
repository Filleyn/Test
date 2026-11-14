package com.offerscorer.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Dark palette tuned for night driving. */
object C {
    const val BG = 0xFF0F1418.toInt()
    const val SURFACE = 0xFF182028.toInt()
    const val FIELD = 0xFF10161C.toInt()
    const val LINE = 0xFF2A3540.toInt()
    const val INK = 0xFFE7ECF0.toInt()
    const val MUTED = 0xFF93A0AD.toInt()
    const val ACCENT = 0xFF7EA4FF.toInt()
    const val ACCENT_INK = 0xFF0D1420.toInt()
    const val GOOD = 0xFF45C77D.toInt()
    const val WARN = 0xFFF0B43A.toInt()
    const val BAD = 0xFFF2735F.toInt()
}

enum class Kind { PRIMARY, SECONDARY, GO, DANGER }

/** Small helpers for building screens in code (the app uses no extra libraries). */
class Ui(val ctx: Context) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun col(gap: Int = 12, vararg children: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { i, c -> addView(c, lp(top = if (i == 0) 0 else gap)) }
    }

    fun row(gap: Int = 10, vararg children: View, weights: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEachIndexed { i, c ->
            val p = if (weights) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) p.marginStart = dp(gap)
            addView(c, p)
        }
    }

    fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun LinearLayout.add(v: View, top: Int = 12): LinearLayout { addView(v, lp(if (childCount == 0) 0 else top)); return this }

    fun text(s: CharSequence, size: Float = 15f, color: Int = C.INK, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    fun big(s: CharSequence, size: Float = 40f, color: Int = C.INK): TextView = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); typeface = condensed; includeFontPadding = false
    }

    fun label(s: String): TextView = text(s.uppercase(), 11.5f, C.MUTED, bold = true).apply { letterSpacing = 0.09f }

    fun hint(s: CharSequence): TextView = text(s, 13f, C.MUTED)

    fun rounded(color: Int, radius: Int = 12, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun card(vararg children: View): LinearLayout = col(12, *children).apply {
        background = rounded(C.SURFACE, 12, C.LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun button(label: String, kind: Kind = Kind.SECONDARY, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(48)
        val (bg, fg) = when (kind) {
            Kind.PRIMARY -> C.ACCENT to C.ACCENT_INK
            Kind.GO -> C.GOOD to C.ACCENT_INK
            Kind.DANGER -> C.SURFACE to C.BAD
            Kind.SECONDARY -> C.SURFACE to C.INK
        }
        background = rounded(bg, 10, if (kind == Kind.SECONDARY || kind == Kind.DANGER) C.LINE else null)
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    class Field(val view: LinearLayout, val input: EditText) {
        val value: String get() = input.text.toString().trim()
        val number: Double? get() = value.replace("$", "").replace(",", "").toDoubleOrNull()
    }

    fun field(label: String, value: String, suffix: String? = null, numeric: Boolean = true, multiline: Boolean = false): Field {
        val input = EditText(ctx).apply {
            setText(value); textSize = 17f; setTextColor(C.INK); setHintTextColor(C.MUTED)
            background = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
            inputType = when {
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (numeric) typeface = Typeface.MONOSPACE
            if (multiline) { minLines = 5; gravity = Gravity.TOP or Gravity.START; textSize = 13f; typeface = Typeface.MONOSPACE }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.FIELD, 8, C.LINE)
            setPadding(dp(10), 0, dp(10), 0)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (suffix != null) addView(text(suffix, 13f, C.MUTED))
        }
        val wrap = col(4, text(label, 12.5f, C.MUTED), box)
        return Field(wrap, input)
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(C.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun stat(labelText: String, value: String, color: Int = C.INK): LinearLayout =
        col(2, label(labelText), big(value, 26f, color))

    fun pill(s: String, color: Int): TextView = text(s.uppercase(), 11.5f, color, bold = true).apply {
        letterSpacing = 0.06f
        background = rounded((color and 0x00FFFFFF) or 0x2E000000, 99)
        setPadding(dp(9), dp(4), dp(9), dp(4))
    }
}
