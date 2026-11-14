package com.offerscorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    private val p = OfferParser(ParserRules())
    private val s = ScoreSettings()

    private fun found(lines: List<String>): Offer {
        val r = p.parse(lines)
        assertTrue("expected an offer, got $r", r is ParseResult.Found)
        return (r as ParseResult.Found).offer
    }

    @Test fun rideOffer() {
        val o = found(listOf("$123.45", "UberX", "Exclusive", "$14.00", "4.92 ★", "7 mins (3.1 mi) away", "123 Main St", "20 mins (9.0 mi) trip", "Pacific Blvd", "Accept"))
        assertEquals(JobType.RIDE, o.type)
        assertEquals(14.00, o.fare, 0.001)
        assertEquals(7.0, o.pickupMin, 0.001); assertEquals(3.1, o.pickupMi, 0.001)
        assertEquals(20.0, o.tripMin, 0.001); assertEquals(9.0, o.tripMi, 0.001)
        val sc = Scorer.score(o, s)!!
        assertEquals(12.1, sc.miles, 0.001)
        assertEquals(Verdict.BORDERLINE, sc.verdict)
    }

    @Test fun longTripWithHours() {
        val o = found(listOf("Comfort", "$58.20", "+$4.00 included", "5 min (1.8 mi) away", "1 hr 5 min (52.4 mi) trip"))
        assertEquals(58.20, o.fare, 0.001)
        assertEquals(65.0, o.tripMin, 0.001)
    }

    @Test fun deliveryOffer() {
        val o = found(listOf("Uber Eats", "$9.50", "Includes expected tip", "24 min (7.5 mi) total", "2 deliveries", "Accept"))
        assertEquals(JobType.DELIVERY, o.type)
        assertEquals(24.0, o.totalMin, 0.001); assertEquals(7.5, o.totalMi, 0.001)
        assertEquals(2, o.dropoffs)
    }

    @Test fun distanceFirst() {
        val o = found(listOf("$11.75", "2.4 mi · 6 min away", "8.8 mi · 18 min trip"))
        assertEquals(2.4, o.pickupMi, 0.001); assertEquals(18.0, o.tripMin, 0.001)
    }

    @Test fun deliveryWordElsewhereOnScreenKeepsRide() {
        val menu = listOf("Delivery", "Preferences", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val o = found(menu + listOf("UberX", "$14.00", "7 mins (3.1 mi) away", "Main St", "20 mins (9.0 mi) trip", "Accept"))
        assertEquals(JobType.RIDE, o.type)
    }

    @Test fun wholeCardInOneLine() {
        val o = found(listOf("$412.10", "UberX, $9.25, 4 minutes (1.2 miles) away, 11 minutes (4.6 miles) trip, Accept"))
        assertEquals(9.25, o.fare, 0.001); assertEquals(11.0, o.tripMin, 0.001)
    }

    @Test fun homeScreenIsNotAnOffer() {
        assertTrue(p.parse(listOf("$123.45", "You're online", "Finding trips")) is ParseResult.NotOffer)
    }

    @Test fun partialOfferIsUnreadable() {
        assertTrue(p.parse(listOf("$14.00", "7 mins (3.1 mi) away", "Accept")) is ParseResult.Unreadable)
    }
}
