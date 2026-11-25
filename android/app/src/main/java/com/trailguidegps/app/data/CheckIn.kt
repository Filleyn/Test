package com.trailguidegps.app.data

import android.content.Context
import com.trailguidegps.app.geo.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A check-in text to people at home: that you're OK, running late, or out safely, with where
 * you are, how far along the route, and when you planned to be out. Sent through your own
 * messaging app, so nothing goes through TrailGuide.
 */
object CheckIn {
    enum class Kind(val label: String) { OK("I'm OK"), LATE("Running late"), OUT("Out safely") }

    data class Info(
        val lat: Double?, val lon: Double?, val accuracyM: Float? = null,
        /** When that position was taken. */
        val fixAtMs: Long? = null,
        val routeName: String? = null, val alongM: Double? = null, val totalM: Double? = null,
        /** When you planned to be out, and when they should call for help; 0 when not planned. */
        val backBy: Long = 0, val callBy: Long = 0,
        val now: Long = System.currentTimeMillis(),
        val zone: TimeZone = TimeZone.getDefault(),
    )

    fun message(kind: Kind, i: Info): String {
        val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = i.zone }
        val day = SimpleDateFormat("EEE h:mm a", Locale.US).apply { timeZone = i.zone }
        return buildString {
            append(when (kind) {
                Kind.OK -> "TrailGuide check-in: I'm OK."
                Kind.LATE -> "TrailGuide check-in: running late, but I'm OK."
                Kind.OUT -> "TrailGuide check-in: I'm out safely. Trip done."
            })
            if (i.lat != null && i.lon != null) {
                val ll = "%.5f, %.5f".format(Locale.US, i.lat, i.lon)
                append(" At $ll")
                i.accuracyM?.let { append(" (±${Geo.formatFeet(it.toDouble())})") }
                i.fixAtMs?.let { append(" at ${time.format(Date(it))}") }
                append(": https://maps.google.com/?q=${"%.5f,%.5f".format(Locale.US, i.lat, i.lon)}.")
            } else append(" No GPS position right now.")
            if (kind != Kind.OUT && i.routeName != null && i.alongM != null && i.totalM != null && i.totalM > 0) {
                append(" %.1f of %.1f mi on %s.".format(Locale.US, i.alongM / Geo.M_PER_MI, i.totalM / Geo.M_PER_MI, i.routeName))
            }
            if (kind != Kind.OUT && i.backBy > i.now - 12 * 3_600_000L) {
                append(if (kind == Kind.LATE) " I'd planned to be out by ${day.format(Date(i.backBy))}; I'll text again when I'm out."
                    else " Planned out by ${day.format(Date(i.backBy))}.")
                if (i.callBy > i.backBy) append(" Call for help if no word by ${day.format(Date(i.callBy))}.")
            }
            append(" Sent ${time.format(Date(i.now))}.")
        }
    }

    /** Numbers joined the way the phone's messaging app expects for a group text. */
    fun recipients(numbers: List<String>, samsung: Boolean): String =
        numbers.map { it.filter { c -> c.isDigit() || c == '+' } }.filter { it.isNotEmpty() }.joinToString(if (samsung) "," else ";")
}

/** The people check-ins go to, and the trip's planned out and call-for-help times. */
class CheckInStore(context: Context) {
    data class Contact(val name: String, val number: String)

    private val prefs = context.getSharedPreferences("checkin", Context.MODE_PRIVATE)

    fun contacts(): List<Contact> = runCatching {
        val a = JSONArray(prefs.getString("contacts", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Contact(o.optString("name"), o.optString("number")) } }
    }.getOrDefault(emptyList())

    fun setContacts(list: List<Contact>) {
        prefs.edit().putString("contacts", JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("number", it.number)) } }.toString()).apply()
    }

    val backBy get() = prefs.getLong("back_by", 0)
    val callBy get() = prefs.getLong("call_by", 0)

    fun setPlan(backBy: Long, callBy: Long) { prefs.edit().putLong("back_by", backBy).putLong("call_by", callBy).apply() }
    fun clearPlan() { prefs.edit().remove("back_by").remove("call_by").apply() }
}
