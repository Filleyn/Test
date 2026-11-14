package com.offerscorer.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.offerscorer.MainActivity
import com.offerscorer.core.Csv
import com.offerscorer.core.IrsRates
import com.offerscorer.core.ScoreSettings
import com.offerscorer.core.Scorer
import com.offerscorer.data.RulesRepo
import com.offerscorer.data.ShiftRecord
import com.offerscorer.service.OfferReaderService
import com.offerscorer.visible
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
private val dayFmt2 = SimpleDateFormat("EEE, MMM d", Locale.US)
private val timeFmt2 = SimpleDateFormat("h:mm a", Locale.US)
private val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// =====================================================================
// MILEAGE
// =====================================================================

class MileageScreen(a: MainActivity) : Screen(a) {
    private var year = Calendar.getInstance().get(Calendar.YEAR)
    private val yearText = ui.big("", 30f).apply { gravity = Gravity.CENTER }
    private val totals = LinearLayout(a)
    private val monthsBox = ui.col(6)
    private val entries = ui.col(0)
    private val emptyEntries = ui.hint("No mileage for this year yet. Start a shift on the Status tab or add an entry below.")
    private var expandedId = -1L
    private val exportButton = ui.button("", Kind.PRIMARY) { exportYear() }

    private val fDate = ui.field("Date (YYYY-MM-DD)", dateFmt.format(Date()), numeric = false)
    private val fMiles = ui.field("Business miles", "", "mi")
    private val fStartOdo = ui.field("Start odometer", "", "mi")
    private val fEndOdo = ui.field("End odometer", "", "mi")
    private val fPurpose = ui.field("Purpose", prefs.defaultPurpose, numeric = false)
    private val formMsg = ui.text("", 13f, C.MUTED)

    override val view: View = ui.col(
        14,
        ui.row(10,
            ui.button("◀") { year--; refresh() },
            yearText,
            ui.button("▶") { year++; refresh() },
        ),
        ui.card(ui.label("Year to date"), totals),
        ui.card(ui.label("By month"), monthsBox),
        exportButton,
        ui.card(ui.label("Add miles by hand"),
            ui.hint("Enter miles, or the start and end odometer readings."),
            fDate.view, fMiles.view, ui.row(10, fStartOdo.view, fEndOdo.view), fPurpose.view,
            ui.button("Add entry", Kind.PRIMARY) { addManual() }, formMsg),
        ui.label("Entries"),
        emptyEntries,
        entries,
    )

    private fun yearRange(): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { clear(); set(year, 0, 1) }
        val from = c.timeInMillis
        c.add(Calendar.YEAR, 1)
        return from to c.timeInMillis
    }

    private fun rate(s: ShiftRecord) = IrsRates.rateFor(s.startTs, prefs.irsFallbackRate)

    override fun refresh() {
        yearText.text = year.toString()
        exportButton.text = "Export $year mileage log (CSV)"
        val (from, to) = yearRange()
        val list = db.shifts(from, to)
        val total = list.sumOf { it.miles }
        val gps = list.filter { it.source == "gps" }.sumOf { it.miles }
        val deduction = list.sumOf { it.miles * rate(it) }

        totals.removeAllViews()
        totals.addView(ui.col(12,
            ui.row(10, ui.stat("Business miles", f1(total)), ui.stat("Est. deduction", money(deduction), C.GOOD)),
            ui.row(10, ui.stat("GPS", f1(gps)), ui.stat("Added by hand", f1(total - gps))),
            ui.hint("Estimate uses the IRS standard mileage rate for each date. Check with your tax preparer."),
        ), ui.lp())

        val byMonth = DoubleArray(12)
        val cal = Calendar.getInstance()
        list.forEach { cal.timeInMillis = it.startTs; byMonth[cal.get(Calendar.MONTH)] += it.miles }
        monthsBox.removeAllViews()
        val max = byMonth.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        var anyMonth = false
        byMonth.forEachIndexed { m, mi ->
            if (mi <= 0) return@forEachIndexed
            anyMonth = true
            val bar = View(a).apply { background = ui.rounded(C.ACCENT, 3) }
            val track = LinearLayout(a).apply {
                addView(bar, LinearLayout.LayoutParams(0, ui.dp(10), (mi / max).toFloat()))
                addView(View(a), LinearLayout.LayoutParams(0, ui.dp(10), (1 - mi / max).toFloat().coerceAtLeast(0.0001f)))
            }
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(months[m], 13f, C.MUTED), LinearLayout.LayoutParams(ui.dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(track, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("${f1(mi)} mi", 13f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(ui.dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            monthsBox.addView(r, ui.lp(if (monthsBox.childCount == 0) 0 else 6))
        }
        if (!anyMonth) monthsBox.addView(ui.hint("No miles yet."), ui.lp())

        emptyEntries.visible(list.isEmpty())
        entries.removeAllViews()
        list.forEachIndexed { i, s ->
            if (i > 0) entries.addView(ui.divider(), ui.lp())
            entries.addView(entryRow(s), ui.lp())
        }
    }

    private fun entryRow(s: ShiftRecord): View {
        val when_ = if (s.source == "gps")
            "${dayFmt2.format(Date(s.startTs))} · ${timeFmt2.format(Date(s.startTs))}–${if (s.active) "now" else timeFmt2.format(Date(s.endTs))}"
            else dayFmt2.format(Date(s.startTs))
        val odo = if (s.startOdo != null && s.endOdo != null) " · odometer ${f1(s.startOdo)}–${f1(s.endOdo)}" else ""
        val src = if (s.source == "gps") (if (s.active) "GPS, recording" else "GPS") else "Added by hand"
        val col = ui.col(2,
            ui.row(10, ui.text(when_, 15f, bold = true), ui.text("${f1(s.miles)} mi", 15f, bold = true).apply { gravity = Gravity.END }),
            ui.hint("$src$odo · ${s.purpose}"),
        ).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) }
        if (expandedId == s.id && !s.active) {
            col.addView(ui.button("Delete this entry", Kind.DANGER) { db.deleteShift(s.id); expandedId = -1; refresh() }, ui.lp(8))
        }
        col.setOnClickListener { expandedId = if (expandedId == s.id) -1 else s.id; refresh() }
        return col
    }

    private fun addManual() {
        val date = runCatching { dateFmt.parse(fDate.value) }.getOrNull()
        if (date == null) { formMsg.setTextColor(C.BAD); formMsg.text = "Enter the date as YYYY-MM-DD, for example ${dateFmt.format(Date())}."; return }
        val so = fStartOdo.number; val eo = fEndOdo.number
        val miles = fMiles.number ?: if (so != null && eo != null) eo - so else null
        if (miles == null || miles <= 0 || miles > 2000) {
            formMsg.setTextColor(C.BAD); formMsg.text = "Enter the miles, or start and end odometer readings with the end higher than the start."; return
        }
        val purpose = fPurpose.value.ifBlank { prefs.defaultPurpose }
        val start = date.time + 12 * 3_600_000L // noon, so time zones never shift the date
        db.addManualShift(start, start, miles, purpose, so, eo)
        fMiles.input.setText(""); fStartOdo.input.setText(""); fEndOdo.input.setText("")
        formMsg.setTextColor(C.GOOD); formMsg.text = "Added ${f1(miles)} mi on ${fDate.value}."
        val c = Calendar.getInstance().apply { time = date }
        year = c.get(Calendar.YEAR)
        refresh()
    }

    private fun exportYear() {
        val (from, to) = yearRange()
        val list = db.shifts(from, to).reversed()
        if (list.isEmpty()) { a.toast("No mileage for $year."); return }
        val t = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder("date,start_time,end_time,business_miles,source,purpose,start_odometer,end_odometer,irs_rate,est_deduction\n")
        for (s in list) {
            val r = rate(s)
            sb.append(Csv.row(
                dateFmt.format(Date(s.startTs)),
                if (s.source == "gps") t.format(Date(s.startTs)) else "",
                if (s.source == "gps") t.format(Date(s.endTs)) else "",
                "%.1f".format(Locale.US, s.miles), if (s.source == "gps") "GPS" else "Manual", s.purpose,
                s.startOdo?.let { "%.1f".format(Locale.US, it) } ?: "", s.endOdo?.let { "%.1f".format(Locale.US, it) } ?: "",
                r, "%.2f".format(Locale.US, s.miles * r),
            )).append('\n')
        }
        val total = list.sumOf { it.miles }
        sb.append(Csv.row("TOTAL", "", "", "%.1f".format(Locale.US, total), "", "", "", "", "", "%.2f".format(Locale.US, list.sumOf { it.miles * rate(it) }))).append('\n')
        a.saveFile("mileage-$year.csv", "text/csv", sb.toString())
    }
}

// =====================================================================
// SETTINGS
// =====================================================================

class SettingsScreen(a: MainActivity) : Screen(a) {
    private val repo = RulesRepo(a)

    private val gas = ui.field("Gas price", "", "$/gal")
    private val mpg = ui.field("Real-world MPG", "", "mpg")
    private val maint = ui.field("Maintenance + tires", "", "$/mi")
    private val depr = ui.field("Depreciation", "", "$/mi")
    private val cpm = ui.hint("")
    private val tRide = ui.field("Ride target", "", "$/hr")
    private val tDel = ui.field("Delivery target", "", "$/hr")
    private val maxP = ui.field("Max pickup time", "", "min")
    private val band = ui.field("Yellow zone", "", "% below")
    private val wait = ui.field("Restaurant wait", "", "min/pickup")
    private val stop = ui.field("Extra stop", "", "min/stop")
    private val saveMsg = ui.text("", 13f, C.GOOD)

    private val irs = ui.field("Mileage rate for dates the app doesn't know", "", "$/mi")
    private val purpose = ui.field("Purpose written on GPS shifts", "", numeric = false)

    private val rulesInfo = ui.hint("")
    private val rulesUrl = ui.field("Rules file link (optional)", "", numeric = false)
    private val rulesMsg = ui.text("", 13f, C.MUTED)
    private val captureInfo = ui.hint("")

    override val view: View = ui.col(
        14,
        ui.card(ui.label("Vehicle cost"), ui.row(10, gas.view, mpg.view), ui.row(10, maint.view, depr.view), cpm),
        ui.card(ui.label("Targets"), ui.row(10, tRide.view, tDel.view), ui.row(10, maxP.view, band.view),
            ui.hint("Green meets the target. Yellow is within the yellow zone below it. Red is below that, or a pickup longer than the max.")),
        ui.card(ui.label("Delivery adjustments"), ui.row(10, wait.view, stop.view)),
        ui.card(ui.label("Mileage log"), irs.view, purpose.view,
            ui.hint("Built-in IRS rates: 2025 \$0.70, Jan–Jun 2026 \$0.725, Jul–Dec 2026 \$0.76.")),
        ui.button("Save settings", Kind.PRIMARY) { save() },
        saveMsg,
        ui.button("Reset to defaults") { prefs.resetScore(); load(); saveMsg.text = "Defaults restored." },
        ui.card(ui.label("Reading rules"), rulesInfo, rulesUrl.view,
            ui.row(10, ui.button("Update now") { updateRules() }, ui.button("Use built-in") { repo.resetToBuiltIn(); reloadReader(); refresh(); rulesMsg.text = "Using the built-in rules." }),
            rulesMsg,
            ui.hint("If Uber changes its wording, a new rules file fixes the reader without rebuilding the app. Host it anywhere with an https link, such as a GitHub file's Raw link.")),
        ui.card(ui.label("Capture mode"), captureInfo,
            ui.button("Record Uber screen text for 15 minutes") {
                prefs.captureUntil = System.currentTimeMillis() + 15 * 60_000; refresh()
            },
            ui.button("Stop recording") { prefs.captureUntil = 0; refresh() },
            ui.hint("Saves the text of each Uber screen (never images) so the rules can be tuned to real offers. Export it from the Offers tab.")),
        ui.card(ui.label("Privacy"), ui.hint("Everything stays on this phone. The app only goes online if you add a rules file link and tap Update now.")),
    )

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun load() {
        val s = prefs.score
        gas.input.setText(fmt(s.gasPrice)); mpg.input.setText(fmt(s.mpg))
        maint.input.setText(fmt(s.maintPerMile)); depr.input.setText(fmt(s.deprPerMile))
        tRide.input.setText(fmt(s.rideTargetHourly)); tDel.input.setText(fmt(s.deliveryTargetHourly))
        maxP.input.setText(fmt(s.maxPickupMin)); band.input.setText(fmt(s.yellowBandPct))
        wait.input.setText(fmt(s.restaurantWaitMin)); stop.input.setText(fmt(s.extraStopMin))
        irs.input.setText(fmt(prefs.irsFallbackRate)); purpose.input.setText(prefs.defaultPurpose)
        rulesUrl.input.setText(prefs.rulesUrl)
        showCpm(s)
    }

    private fun showCpm(s: ScoreSettings) {
        cpm.text = "Fuel ${String.format(Locale.US, "$%.3f", s.fuelPerMile)} + maintenance ${money(s.maintPerMile)} + depreciation ${money(s.deprPerMile)} = ${money(s.costPerMile)} per mile"
    }

    override fun refresh() {
        load()
        val loaded = repo.load()
        rulesInfo.text = "${loaded.source} rules, version ${loaded.rules.version}. Reading: ${loaded.rules.packages.joinToString()}"
        val until = prefs.captureUntil
        captureInfo.text = if (until > System.currentTimeMillis())
            "Recording until ${SimpleDateFormat("h:mm a", Locale.US).format(Date(until))}." else "Off."
    }

    private fun save() {
        val nums = listOf(gas, mpg, maint, depr, tRide, tDel, maxP, band, wait, stop).map { it.number }
        if (nums.any { it == null || it < 0 } || (nums[1] ?: 0.0) <= 0) {
            saveMsg.setTextColor(C.BAD); saveMsg.text = "Every field needs a number, and MPG must be above zero."; return
        }
        val n = nums.map { it!! }
        val s = ScoreSettings(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9])
        prefs.score = s
        irs.number?.takeIf { it in 0.0..5.0 }?.let { prefs.irsFallbackRate = it }
        if (purpose.value.isNotBlank()) prefs.defaultPurpose = purpose.value
        showCpm(s)
        saveMsg.setTextColor(C.GOOD); saveMsg.text = "Saved. New offers use these numbers."
    }

    private fun updateRules() {
        val url = rulesUrl.value
        prefs.rulesUrl = url
        rulesMsg.setTextColor(C.MUTED); rulesMsg.text = "Updating…"
        Thread {
            val msg = repo.update(url)
            a.runOnUiThread {
                rulesMsg.setTextColor(if (msg.startsWith("Rules updated")) C.GOOD else C.BAD)
                rulesMsg.text = msg
                reloadReader(); refresh()
            }
        }.start()
    }

    private fun reloadReader() { OfferReaderService.instance?.reloadRules() }
}
