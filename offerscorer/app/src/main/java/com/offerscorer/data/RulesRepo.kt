package com.offerscorer.data

import android.content.Context
import com.offerscorer.core.OfferParser
import com.offerscorer.core.ParserRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the screen-reading rules. Order: a downloaded rules file, then the copy
 * bundled in the app (assets/rules.json), then the built-in defaults.
 */
class RulesRepo(private val context: Context) {

    private val downloaded = File(context.filesDir, "rules.json")

    data class Loaded(val rules: ParserRules, val source: String)

    fun load(): Loaded {
        if (downloaded.exists()) {
            runCatching { return Loaded(fromJson(downloaded.readText()), "Downloaded") }
        }
        runCatching {
            val text = context.assets.open("rules.json").bufferedReader().use { it.readText() }
            return Loaded(fromJson(text), "Built-in")
        }
        return Loaded(ParserRules(), "Defaults")
    }

    /** Downloads, validates and saves rules. Call off the main thread. Returns a message for the user. */
    fun update(url: String): String {
        if (!url.startsWith("https://")) return "Use an https:// link to a rules.json file."
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000
            }
            val code = conn.responseCode
            if (code != 200) return "The server answered $code. Check the link."
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.length > 100_000) return "That file is too large to be a rules file."
            val rules = fromJson(text)
            OfferParser(rules) // throws if any pattern is invalid
            downloaded.writeText(text)
            "Rules updated to version ${rules.version}."
        } catch (e: Exception) {
            "Couldn't update rules: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun resetToBuiltIn() { downloaded.delete() }

    companion object {
        fun fromJson(text: String): ParserRules {
            val j = JSONObject(text)
            val d = ParserRules()
            fun list(key: String, def: List<String>): List<String> {
                val a: JSONArray = j.optJSONArray(key) ?: return def
                return (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
            }
            fun str(key: String, def: String?): String? = if (j.has(key) && !j.isNull(key)) j.getString(key) else def
            return ParserRules(
                version = j.optInt("version", d.version),
                packages = list("packages", d.packages),
                pickupWords = list("pickupWords", d.pickupWords),
                tripWords = list("tripWords", d.tripWords),
                totalWords = list("totalWords", d.totalWords),
                offerHintWords = list("offerHintWords", d.offerHintWords),
                deliveryWords = list("deliveryWords", d.deliveryWords),
                acceptedWords = list("acceptedWords", d.acceptedWords),
                pickupCountRegex = str("pickupCountRegex", d.pickupCountRegex)!!,
                dropoffCountRegex = str("dropoffCountRegex", d.dropoffCountRegex)!!,
                fareRegex = str("fareRegex", d.fareRegex),
                segmentRegex = str("segmentRegex", d.segmentRegex),
                maxFare = j.optDouble("maxFare", d.maxFare),
            )
        }
    }
}
