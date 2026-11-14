package com.offerscorer.core

import kotlin.math.abs

/**
 * Text patterns used to read offers off the screen. The defaults below match the
 * common Uber layout ("7 min (3.1 mi) away", "20 min (9.0 mi) trip", "$14.20").
 * The same fields can be supplied in a rules.json file so wording changes can be
 * fixed without rebuilding the app. See README.md for the file format.
 */
data class ParserRules(
    val version: Int = 1,
    /** Apps whose screens are read. Uber rides and Uber Eats both use the Uber Driver app. */
    val packages: List<String> = listOf("com.ubercab.driver"),
    val pickupWords: List<String> = listOf("away", "to pickup", "pickup"),
    val tripWords: List<String> = listOf("trip"),
    val totalWords: List<String> = listOf("total"),
    /** A line that equals one of these (a button) plus a dollar amount means "this looks like an offer". */
    val offerHintWords: List<String> = listOf("Accept", "Match", "Tap to accept"),
    /** Any of these words on the card marks the offer as a delivery. */
    val deliveryWords: List<String> = listOf("Delivery", "Deliveries", "Uber Eats", "Order", "Orders"),
    /** Seen on screen shortly after an offer closes means it was accepted. */
    val acceptedWords: List<String> = listOf("Picking up", "Pick up", "Arriving", "Arrived", "Start trip", "Swipe to start", "Head to"),
    val pickupCountRegex: String = """(\d+)\s*(?:pickups|restaurants|stores)\b""",
    val dropoffCountRegex: String = """(\d+)\s*(?:deliveries|orders|drop-?offs|stops)\b""",
    /** Optional override. Group 1 must be the amount. */
    val fareRegex: String? = null,
    /** Optional override for every segment. Groups: 1 hours, 2 minutes, 3 distance, 4 unit. Use {words} where the suffix goes. */
    val segmentRegex: String? = null,
    val maxFare: Double = 500.0,
)

sealed class ParseResult {
    object NotOffer : ParseResult()
    data class Found(val offer: Offer) : ParseResult()
    data class Unreadable(val reason: String) : ParseResult()
}

class OfferParser(val rules: ParserRules) {

    private val opts = setOf(RegexOption.IGNORE_CASE)

    private val fare = Regex(rules.fareRegex ?: """(?<!\+\s?)\$\s?(\d{1,4}(?:[.,]\d{2})?)""", opts)

    private fun wordsAlt(words: List<String>) = words.joinToString("|") { Regex.escape(it).replace(" ", "\\s+") }

    // "1 hr 5 min (40.2 mi) trip", "7 mins (3.1 mi) away", "7 min · 3.1 mi away"
    private val durThenDist = """(?=\d)(?:(\d+)\s*(?:h|hr|hrs|hour|hours)\.?\s*)?(?:(\d+)\s*(?:m|min|mins|minute|minutes)\.?)?\s*[(\[·•,\-]?\s*(\d+(?:[.,]\d+)?)\s*(mi|mile|miles|km)\.?\s*[)\]]?\s*(?:{words})\b"""
    // "3.1 mi (7 min) away", "3.1 mi · 7 min away"
    private val distThenDur = """(\d+(?:[.,]\d+)?)\s*(mi|mile|miles|km)\.?\s*[(\[·•,\-]?\s*(?=\d)(?:(\d+)\s*(?:h|hr|hrs|hour|hours)\.?\s*)?(?:(\d+)\s*(?:m|min|mins|minute|minutes)\.?)?\s*[)\]]?\s*(?:{words})\b"""

    private class Seg(val minutes: Double, val miles: Double, val start: Int)

    private inner class SegFinder(words: List<String>) {
        private val alt = wordsAlt(words)
        private val primary = Regex((rules.segmentRegex ?: durThenDist).replace("{words}", alt), opts)
        private val secondary = if (rules.segmentRegex == null) Regex(distThenDur.replace("{words}", alt), opts) else null

        fun find(text: String): Seg? {
            primary.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                val d = toMiles(m.groupValues[3], m.groupValues[4]) ?: return@let
                if (h == 0 && mi == 0 && m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return@let
                return Seg(h * 60.0 + mi, d, m.range.first)
            }
            secondary?.find(text)?.let { m ->
                val d = toMiles(m.groupValues[1], m.groupValues[2]) ?: return null
                val h = m.groupValues[3].toIntOrNull() ?: 0
                val mi = m.groupValues[4].toIntOrNull() ?: 0
                if (m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()) return null
                return Seg(h * 60.0 + mi, d, m.range.first)
            }
            return null
        }
    }

    private val pickupFinder = SegFinder(rules.pickupWords)
    private val tripFinder = SegFinder(rules.tripWords)
    private val totalFinder = SegFinder(rules.totalWords)
    private val pickupCount = Regex(rules.pickupCountRegex, opts)
    private val dropoffCount = Regex(rules.dropoffCountRegex, opts)
    private val deliveryRegex = if (rules.deliveryWords.isEmpty()) null
        else Regex("""\b(?:${wordsAlt(rules.deliveryWords)})\b""", opts)

    private fun toMiles(num: String, unit: String): Double? {
        val v = num.replace(',', '.').toDoubleOrNull() ?: return null
        return if (unit.lowercase().startsWith("km")) v * 0.621371 else v
    }

    fun normalize(raw: List<String>): List<String> = raw.asSequence()
        .map { it.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace('−', '-').trim() }
        .filter { it.isNotEmpty() }
        .toList()

    fun parse(raw: List<String>): ParseResult {
        val lines = normalize(raw)
        if (lines.isEmpty()) return ParseResult.NotOffer
        val text = lines.joinToString("\n")
        val lineStarts = IntArray(lines.size)
        var pos = 0
        for (i in lines.indices) { lineStarts[i] = pos; pos += lines[i].length + 1 }
        fun lineOf(offset: Int): Int { var i = lines.size - 1; while (i > 0 && lineStarts[i] > offset) i--; return i }

        val pickup = pickupFinder.find(text)
        // Search for the trip after the pickup so "pickup" wording never doubles as the trip.
        val trip = tripFinder.find(text)
        val total = totalFinder.find(text)
        val anchorLine: Int
        val offerNoFare: Offer
        // Only the lines around the card decide "delivery" and stop counts, so words elsewhere
        // on the screen (menus, map labels) can't turn a ride into a delivery.
        val segStarts = listOfNotNull(pickup?.start, trip?.start, total?.start)
        val card = if (segStarts.isEmpty()) text else {
            val first = (lineOf(segStarts.min()) - 8).coerceAtLeast(0)
            val last = (lineOf(segStarts.max()) + 4).coerceAtMost(lines.size - 1)
            lines.subList(first, last + 1).joinToString("\n")
        }
        val isDeliveryWorded = deliveryRegex?.containsMatchIn(card) == true

        if (pickup != null && trip != null && pickup.start != trip.start) {
            anchorLine = lineOf(minOf(pickup.start, trip.start))
            offerNoFare = if (isDeliveryWorded) {
                Offer(JobType.DELIVERY, 0.0, pickup.minutes, pickup.miles, trip.minutes, trip.miles,
                    total?.minutes ?: 0.0, total?.miles ?: 0.0)
            } else {
                Offer(JobType.RIDE, 0.0, pickup.minutes, pickup.miles, trip.minutes, trip.miles)
            }
        } else if (total != null) {
            anchorLine = lineOf(total.start)
            offerNoFare = Offer(JobType.DELIVERY, 0.0, totalMin = total.minutes, totalMi = total.miles)
        } else if (pickup != null || trip != null) {
            return ParseResult.Unreadable(if (pickup != null) "Found the pickup but not the trip" else "Found the trip but not the pickup")
        } else {
            val hasHint = lines.any { l -> rules.offerHintWords.any { it.equals(l, ignoreCase = true) } }
            return if (hasHint && fare.containsMatchIn(text)) ParseResult.Unreadable("Looks like an offer, but no time or distance found")
            else ParseResult.NotOffer
        }

        // Pay: the dollar amount closest to the time/distance lines, preferring lines above them.
        var best: Double? = null
        var bestDist = Double.MAX_VALUE
        for (i in lines.indices) {
            val m = fare.find(lines[i]) ?: continue
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val d = if (i <= anchorLine) (anchorLine - i).toDouble() else (i - anchorLine) + 0.5
            if (d < bestDist) { bestDist = d; best = v }
        }
        val pay = best ?: return ParseResult.Unreadable("Found time and distance but no pay")
        if (pay <= 0 || pay > rules.maxFare) return ParseResult.Unreadable("Pay of $pay looks wrong")

        var offer = offerNoFare.copy(fare = pay)
        if (offer.type == JobType.DELIVERY) {
            val pu = pickupCount.find(card)?.groupValues?.get(1)?.toIntOrNull()
            val dr = dropoffCount.find(card)?.groupValues?.get(1)?.toIntOrNull()
            offer = offer.copy(pickups = (pu ?: 1).coerceIn(1, 6), dropoffs = (dr ?: 1).coerceIn(1, 6))
        }
        if (abs(offer.pickupMi + offer.tripMi + offer.totalMi) < 0.01) return ParseResult.Unreadable("Distance read as zero")
        return ParseResult.Found(offer)
    }

    fun looksAccepted(raw: List<String>): Boolean {
        val lines = normalize(raw)
        return lines.any { l -> rules.acceptedWords.any { l.contains(it, ignoreCase = true) } }
    }
}
