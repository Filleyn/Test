package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDaysTest {
    private val pace = Pace()

    /** A straight 10 km line north, flat. */
    private fun line(): List<DoubleArray> = (0..100).map { doubleArrayOf(44.0 + it * 0.0008993, -121.0) }

    private fun route(coords: List<DoubleArray>, outCount: Int = 0): SavedRoute {
        var d = 0.0
        for (i in 1 until coords.size) d += com.trailguidegps.app.geo.Geo.distanceM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1])
        val stats = RouteStats(d, 0.0, 0.0, listOf(0.0 to 100.0, d to 100.0))
        return SavedRoute("r", "Test", 0, emptyList(), coords, stats, emptyList(), outAndBack = outCount > 0, outCount = outCount)
    }

    @Test fun extraNightsAddRestDays() {
        val r = route(line())
        val half = r.stats.distanceM / 2
        val days = TripDays.days(r, listOf(Camp(half, "Lake", nights = 2)), pace)
        assertEquals(3, days.size)
        assertEquals(false, days[0].rest); assertEquals(1, days[0].night)
        assertEquals(true, days[1].rest); assertEquals(2, days[1].night); assertEquals(0, days[1].minutes)
        assertEquals(false, days[2].rest); assertEquals(3, days[2].day)
        assertEquals("Nights 1–2", TripDays.nightsLabel(days, 0))
        assertEquals(3, TripDays.totalDays(listOf(Camp(half, "Lake", nights = 2))))
        // Rest days don't count toward the rating.
        assertEquals(2, Difficulty.of(r.stats, null, days).days.size)
    }

    @Test fun outAndBackCampsAtTheTurnaround() {
        val out = line()
        val r = route(out + out.reversed().drop(1), outCount = out.size)
        val turn = r.turnM!!
        assertEquals(r.stats.distanceM / 2, turn, 1.0)
        val camps = TripDays.suggest(r, 2, pace, emptyList())
        assertEquals(1, camps.size)
        assertEquals(turn, camps[0].alongM, 1.0)
    }

    @Test fun waterAcrossTwoNightsAtADryCamp() {
        val flat = RouteStats(10_000.0, 0.0, 0.0, listOf(0.0 to 100.0, 10_000.0 to 100.0))
        val days = listOf(0.0 to 5_000.0, 5_000.0 to 5_000.0, 5_000.0 to 10_000.0)
        val w = WaterCarry.days(flat, listOf(2_000.0, 8_000.0), days, listOf(false, false), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        assertEquals(3, w.size)
        val fill = w[0].campFill!!
        assertEquals(2, fill.dryCamps)               // two nights carried in
        assertEquals(8.0, fill.carryL, 1e-9)        // 1.1 L walking + 6 L for two nights + 0.5 L, rounded up
        assertNull(w[1].walk); assertNull(w[1].campFill)
        assertTrue(w[2].walk!!.fromM >= 8_000.0 - 0.1)
    }
}
