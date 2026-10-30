package com.trailguidegps.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * National Weather Service forecasts (api.weather.gov, free, no key), fetched online and kept
 * so the last one can still be read on the trail. The NWS forecast is for a 1.5 mile grid
 * square at that square's average elevation, so the app says how far your spot is above it.
 */
object Weather {
    data class Period(
        val name: String,
        val tempF: Int?,
        val isDay: Boolean,
        val wind: String,
        val short: String,
        val detail: String,
        val rainChance: Int?,
    )

    data class Forecast(
        val label: String,
        val lat: Double,
        val lon: Double,
        /** Your spot's elevation and the forecast grid's, in metres. */
        val spotEleM: Double?,
        val gridEleM: Double?,
        val updated: String?,
        val fetchedAt: Long,
        val periods: List<Period>,
        val area: String?,
    ) {
        /** Rough temperature change for the height difference: about 3.5 °F colder per 1,000 ft up. */
        val tempAdjustF: Int?
            get() {
                val s = spotEleM ?: return null
                val g = gridEleM ?: return null
                val ft = (s - g) * 3.28084
                return if (kotlin.math.abs(ft) < 700) null else (-ft / 1000 * 3.5).toInt()
            }
    }

    private const val UA = "TrailGuideGPS/1.2 (personal hiking app; github.com/Filleyn/Test)"

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/geo+json")
        }
        try {
            if (c.responseCode != 200) {
                val msg = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("detail") }.getOrNull()
                throw java.io.IOException(msg?.takeIf { it.isNotBlank() } ?: "The weather service answered ${c.responseCode}")
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    /** Fetches the 7-day forecast for a spot. Throws on network or service errors. */
    fun fetch(label: String, lat: Double, lon: Double, spotEleM: Double?): Forecast {
        val pt = get("https://api.weather.gov/points/%.4f,%.4f".format(Locale.US, lat, lon)).getJSONObject("properties")
        val area = pt.optJSONObject("relativeLocation")?.optJSONObject("properties")?.let {
            listOfNotNull(it.optString("city").takeIf { s -> s.isNotBlank() }, it.optString("state").takeIf { s -> s.isNotBlank() }).joinToString(", ")
        }
        val fc = get(pt.getString("forecast")).getJSONObject("properties")
        val periods = fc.getJSONArray("periods")
        return Forecast(
            label, lat, lon, spotEleM,
            fc.optJSONObject("elevation")?.optDouble("value")?.takeIf { !it.isNaN() },
            fc.optString("updateTime").takeIf { it.isNotBlank() } ?: fc.optString("generatedAt").takeIf { it.isNotBlank() },
            System.currentTimeMillis(),
            (0 until periods.length()).map { i ->
                val p = periods.getJSONObject(i)
                Period(
                    p.optString("name"),
                    if (p.isNull("temperature")) null else p.optInt("temperature"),
                    p.optBoolean("isDaytime", true),
                    listOf(p.optString("windSpeed"), p.optString("windDirection")).filter { it.isNotBlank() }.joinToString(" "),
                    p.optString("shortForecast"),
                    p.optString("detailedForecast"),
                    p.optJSONObject("probabilityOfPrecipitation")?.let { if (it.isNull("value")) null else it.optInt("value") },
                )
            },
            area,
        )
    }

    // ---- The last forecasts, kept for offline reading ----

    private fun file(context: Context) = File(context.filesDir, "weather.json")

    /** Saved forecasts by key (a route id or "place:lat,lon"). */
    fun saved(context: Context, key: String): List<Forecast> = runCatching {
        val arr = JSONObject(file(context).readText()).optJSONArray(key) ?: return emptyList()
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(context: Context, key: String, list: List<Forecast>) {
        val o = runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject())
        o.put(key, JSONArray().apply { list.forEach { put(toJson(it)) } })
        // Keep the 20 most recent keys.
        val keys = o.keys().asSequence().toList()
        if (keys.size > 20) {
            keys.sortedBy { k -> o.optJSONArray(k)?.optJSONObject(0)?.optLong("fetched") ?: 0L }
                .take(keys.size - 20).forEach { o.remove(it) }
        }
        file(context).writeText(o.toString())
    }

    private fun toJson(f: Forecast) = JSONObject().put("label", f.label).put("lat", f.lat).put("lon", f.lon)
        .put("spot", f.spotEleM ?: JSONObject.NULL).put("grid", f.gridEleM ?: JSONObject.NULL)
        .put("updated", f.updated ?: JSONObject.NULL).put("fetched", f.fetchedAt).put("area", f.area ?: JSONObject.NULL)
        .put("periods", JSONArray().apply {
            f.periods.forEach { p ->
                put(JSONObject().put("name", p.name).put("temp", p.tempF ?: JSONObject.NULL).put("day", p.isDay)
                    .put("wind", p.wind).put("short", p.short).put("detail", p.detail).put("rain", p.rainChance ?: JSONObject.NULL))
            }
        })

    private fun fromJson(o: JSONObject): Forecast {
        val ps = o.getJSONArray("periods")
        fun d(k: String) = if (o.isNull(k)) null else o.optDouble(k)
        return Forecast(
            o.getString("label"), o.getDouble("lat"), o.getDouble("lon"), d("spot"), d("grid"),
            if (o.isNull("updated")) null else o.optString("updated"), o.getLong("fetched"),
            (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                Period(p.optString("name"), if (p.isNull("temp")) null else p.optInt("temp"), p.optBoolean("day", true),
                    p.optString("wind"), p.optString("short"), p.optString("detail"), if (p.isNull("rain")) null else p.optInt("rain"))
            },
            if (o.isNull("area")) null else o.optString("area"),
        )
    }
}
