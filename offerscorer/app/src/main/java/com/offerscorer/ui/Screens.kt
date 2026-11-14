package com.offerscorer.ui

import android.content.ComponentName
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.offerscorer.MainActivity
import com.offerscorer.core.JobType
import com.offerscorer.core.Offer
import com.offerscorer.core.OfferParser
import com.offerscorer.core.ParseResult
import com.offerscorer.core.Scorer
import com.offerscorer.core.Verdict
import com.offerscorer.data.Captures
import com.offerscorer.data.Db
import com.offerscorer.data.Decision
import com.offerscorer.data.OfferRecord
import com.offerscorer.data.Prefs
import com.offerscorer.data.RulesRepo
import com.offerscorer.service.MileageService
import com.offerscorer.service.OfferReaderService
import com.offerscorer.visible
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

abstract class Screen(val a: MainActivity) {
    val ui: Ui get() = a.ui
    val prefs = Prefs(a)
    val db: Db = Db.get(a)
    abstract val view: View
    open fun refresh() {}
    open fun tick() {}
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
private val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.US)
private val shortFmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
fun money(v: Double) = Scorer.money(v)
fun f1(v: Double) = Scorer.fmt1(v)
fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun verdictColor(name: String) = when (name) {
    Verdict.GOOD.name -> C.GOOD; Verdict.BORDERLINE.name -> C.WARN; else -> C.BAD
}
fun verdictLabel(name: String) = runCatching { Verdict.valueOf(name).label }.getOrDefault(name)

data class OfferStats(val count: Int, val accepted: Int, val avgHourly: Double?, val greenShare: Int?)

fun stats(list: List<OfferRecord>): OfferStats {
    val acc = list.filter { it.decision == Decision.ACCEPTED }
    val mins = acc.sumOf { it.minutes }
    val avg = if (mins > 0) acc.sumOf { it.net } / mins * 60 else null
    val green = if (list.isNotEmpty()) list.count { it.verdict == Verdict.GOOD.name } * 100 / list.size else null
    return OfferStats(list.size, acc.size, avg, green)
}

// =====================================================================
// STATUS
// =====================================================================

class StatusScreen(a: MainActivity) : Screen(a) {
    private val readerState = ui.big("", 30f)
    private val readerDetail = ui.hint("")
    private val readerButton = ui.button("Turn on the reader", Kind.PRIMARY) { a.openAccessibilitySettings() }
    private val restrictedHelp = ui.hint(
        "If Android says the setting is restricted: open App info, tap the ⋮ menu, choose " +
            "\"Allow restricted settings\", then come back and turn the reader on."
    )
    private val appInfoButton = ui.button("Open App info") { a.openAppInfo() }
    private val testBubble = ui.button("Show a test bubble") { showTest() }

    private val shiftMiles = ui.big("0.0 mi", 40f)
    private val shiftDetail = ui.hint("")
    private val shiftButton = ui.button("Start shift", Kind.GO) { toggleShift() }

    private val todayRow = LinearLayout(a)

    private val sample = ui.field(
        "Paste or type text from an offer card",
        "UberX\n$14.00\n4.92 ★\n7 mins (3.1 mi) away\n123 Main St\n20 mins (9.0 mi) trip\nPacific Blvd\nAccept",
        numeric = false, multiline = true,
    )
    private val sampleResult = ui.text("", 14f)

    override val view: View = ui.col(
        14,
        ui.card(ui.label("Offer reader"), readerState, readerDetail, readerButton, restrictedHelp, appInfoButton, testBubble),
        ui.card(ui.label("Shift mileage"), shiftMiles, shiftDetail, shiftButton,
            ui.hint("Start the shift when you go online and end it when you go offline. Driving from home to your first trip and back home is usually commuting, not business miles.")),
        ui.card(ui.label("Today"), todayRow),
        ui.card(ui.label("Test the reader"), sample.view, ui.button("Read this text") { testParse() }, sampleResult),
    )

    private fun readerEnabled(): Boolean {
        val list = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(a, OfferReaderService::class.java)
        return list.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    override fun refresh() {
        val on = readerEnabled()
        readerState.text = if (on) "On" else "Off"
        readerState.setTextColor(if (on) C.GOOD else C.BAD)
        readerDetail.text = if (on) OfferReaderService.lastStatus
            else "The reader shows a score bubble over Uber offers. It only reads the screen; it never taps anything."
        readerButton.visible(!on)
        restrictedHelp.visible(!on)
        appInfoButton.visible(!on)
        testBubble.visible(on)
        renderToday()
        tick()
    }

    override fun tick() {
        val running = MileageService.running
        val paused = !running && prefs.activeShiftId >= 0
        if (running) {
            shiftMiles.text = "${f1(MileageService.liveMiles)} mi"
            val mins = (System.currentTimeMillis() - MileageService.startedAt) / 60000
            shiftDetail.text = if (MileageService.locationOff) "Location is off, so miles aren't recording. Turn on Location in the phone's quick settings."
                else "Recording since ${timeFmt.format(Date(MileageService.startedAt))} · ${mins / 60}h ${mins % 60}m"
            shiftDetail.setTextColor(if (MileageService.locationOff) C.BAD else C.MUTED)
            shiftButton.text = "End shift"
        } else if (paused) {
            val s = db.shift(prefs.activeShiftId)
            shiftMiles.text = "${f1(s?.miles ?: 0.0)} mi"
            shiftDetail.setTextColor(C.WARN)
            shiftDetail.text = "Shift paused. Android stopped GPS tracking. Tap Resume to keep recording."
            shiftButton.text = "Resume shift"
        } else {
            shiftMiles.text = "Not recording"
            shiftDetail.text = ""
            shiftButton.text = "Start shift"
        }
    }

    private fun toggleShift() {
        if (MileageService.running) {
            MileageService.stop(a)
            a.toast("Shift ended. See the Mileage tab.")
            shiftButton.postDelayed({ tick() }, 600)
        } else {
            a.withLocationPermission {
                MileageService.start(a)
                shiftButton.postDelayed({ tick() }, 800)
            }
        }
    }

    private fun renderToday() {
        val st = stats(db.offers(startOfToday()))
        todayRow.removeAllViews()
        todayRow.addView(ui.row(10,
            ui.stat("Offers", st.count.toString()),
            ui.stat("Accepted", st.accepted.toString()),
            ui.stat("Net/hr", st.avgHourly?.let { "$" + Scorer.fmt0(it) } ?: "—"),
        ), ui.lp())
    }

    private fun showTest() {
        val svc = OfferReaderService.instance
        if (svc == null) { a.toast("The reader isn't running yet. Turn it off and on again in Accessibility settings."); return }
        val score = Scorer.score(Offer(JobType.RIDE, 14.0, 7.0, 3.1, 20.0, 9.0), prefs.score) ?: return
        svc.showTestBubble(score)
        a.toast("Drag the bubble to move it. Tap it to hide it.")
    }

    private fun testParse() {
        val parser = OfferParser(RulesRepo(a).load().rules)
        sampleResult.text = when (val r = parser.parse(sample.value.lines())) {
            is ParseResult.Found -> {
                val o = r.offer
                val s = Scorer.score(o, prefs.score)
                val read = if (o.type == JobType.RIDE)
                    "Ride: ${money(o.fare)}, pickup ${Scorer.fmt0(o.pickupMin)} min / ${f1(o.pickupMi)} mi, trip ${Scorer.fmt0(o.tripMin)} min / ${f1(o.tripMi)} mi"
                else "Delivery: ${money(o.fare)}, ${Scorer.fmt0(o.totalMin + o.pickupMin + o.tripMin)} min / ${f1(o.totalMi + o.pickupMi + o.tripMi)} mi, ${o.pickups} pickup(s), ${o.dropoffs} drop-off(s)"
                if (s == null) read else "$read\n${money(s.hourly)}/hr net · ${s.verdict.label}\n${s.reason}"
            }
            is ParseResult.Unreadable -> "Couldn't read it: ${r.reason}"
            ParseResult.NotOffer -> "No offer found in this text."
        }
    }
}

// =====================================================================
// OFFERS
// =====================================================================

class OffersScreen(a: MainActivity) : Screen(a) {
    private val captures = Captures(a)
    private val summary = LinearLayout(a)
    private val list = ui.col(0)
    private val empty = ui.hint("No offers yet. They appear here as the reader scores them.")
    private val confirmRow: LinearLayout = LinearLayout(a)

    init {
        confirmRow.addView(ui.row(10,
            ui.button("Yes, delete all", Kind.DANGER) { db.deleteAllOffers(); confirmRow.visible(false); refresh() },
            ui.button("Keep them") { confirmRow.visible(false) },
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        confirmRow.visible(false)
    }
    private val captureText = ui.hint("")

    override val view: View = ui.col(
        14,
        ui.card(ui.label("Last 7 days"), summary),
        ui.row(10,
            ui.button("Export CSV", Kind.PRIMARY) { exportCsv() },
            ui.button("Clear log", Kind.DANGER) { confirmRow.visible(true) },
        ),
        confirmRow,
        empty,
        list,
        ui.card(ui.label("Screens the reader couldn't read"), captureText,
            ui.row(10,
                ui.button("Export") { exportCaptures() },
                ui.button("Delete", Kind.DANGER) { captures.clear(); refresh() },
            )),
    )

    override fun refresh() {
        val week = db.offers(System.currentTimeMillis() - 7L * 86_400_000)
        val st = stats(week)
        summary.removeAllViews()
        summary.addView(ui.col(12,
            ui.row(10, ui.stat("Offers", st.count.toString()), ui.stat("Accepted", st.accepted.toString())),
            ui.row(10, ui.stat("Net/hr accepted", st.avgHourly?.let { money(it) } ?: "—"),
                ui.stat("Scored green", st.greenShare?.let { "$it%" } ?: "—")),
        ), ui.lp())

        val recent = db.offers(limit = 150)
        empty.visible(recent.isEmpty())
        list.removeAllViews()
        recent.forEachIndexed { i, r ->
            if (i > 0) list.addView(ui.divider(), ui.lp())
            list.addView(rowFor(r), ui.lp())
        }
        val n = captures.count("unreadable"); val all = captures.count()
        captureText.text = if (all == 0) "None saved. When an offer can't be read, its screen text is saved here so the reading rules can be fixed."
            else "$n unreadable offer screen(s), ${all - n} capture-mode screen(s). Export the file and send it to Claude to update the rules."
    }

    private fun rowFor(r: OfferRecord): View {
        val c = verdictColor(r.verdict)
        val top = ui.text("${shortFmt.format(Date(r.ts))} · ${if (r.type == JobType.RIDE) "Ride" else "Delivery"} · ${money(r.fare)}", 15f, bold = true)
        val bottom = ui.hint("${f1(r.miles)} mi · ${Scorer.fmt0(r.minutes)} min · ${money(r.hourly)}/hr net · ${r.decision}${if (r.source == "screen") "" else " · ${r.source}"}")
        val left = ui.col(2, top, bottom)
        val chip = ui.pill(verdictLabel(r.verdict), c)
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip)
        }
    }

    private fun exportCsv() {
        val rows = db.offers().reversed()
        if (rows.isEmpty()) { a.toast("Nothing to export yet."); return }
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("timestamp,type,pay,pickup_min,pickup_mi,trip_min,trip_mi,pickups,dropoffs,total_miles,total_minutes,net,net_per_hour,score,decision,source\n")
        for (r in rows) sb.append(com.offerscorer.core.Csv.row(
            iso.format(Date(r.ts)), r.type.name.lowercase(), r.fare, r.pickupMin, r.pickupMi, r.tripMin, r.tripMi,
            r.pickups, r.dropoffs, f1(r.miles), Scorer.fmt0(r.minutes), "%.2f".format(Locale.US, r.net),
            "%.2f".format(Locale.US, r.hourly), verdictLabel(r.verdict), r.decision, r.source)).append('\n')
        a.saveFile("offers-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.csv", "text/csv", sb.toString())
    }

    private fun exportCaptures() {
        if (captures.count() == 0) { a.toast("No saved screens."); return }
        a.saveFile("offer-screens-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.txt", "text/plain", captures.exportText())
    }
}
