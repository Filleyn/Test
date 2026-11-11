package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterCarryTest {
    // 10 km, flat, so climbing doesn't come into it. Default pace: 2 mph, 10 minutes' break an hour.
    private val flat = RouteStats(10_000.0, 0.0, 0.0, listOf(0.0 to 100.0, 10_000.0 to 100.0))
    private val pace = Pace()

    @Test fun longestStretch() {
        val gaps = WaterCarry.gaps(flat, listOf(2_000.0, 8_000.0), emptyList(), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        assertEquals(3, gaps.size)
        val w = WaterCarry.worst(gaps)!!
        assertEquals(6_000.0, w.lengthM, 0.1)
        assertEquals(130, w.minutes)          // 3.7 mi at 2 mph plus breaks
        assertEquals(2.0, w.carryL, 1e-9)    // 2.17 h × 0.5 L + 0.5 L = 1.6, rounded up
    }

    @Test fun dryCampAddsANight() {
        val gaps = WaterCarry.gaps(flat, listOf(2_000.0, 8_000.0), listOf(5_000.0 to false), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        val w = WaterCarry.worst(gaps)!!
        assertEquals(1, w.dryCamps)
        assertEquals(5.0, w.carryL, 1e-9)    // 1.1 + 3 + 0.5 = 4.6, rounded up
    }

    @Test fun campWithWaterSplitsTheStretch() {
        val gaps = WaterCarry.gaps(flat, listOf(2_000.0, 8_000.0), listOf(5_000.0 to true), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        assertEquals(4, gaps.size)
        assertEquals(1.5, WaterCarry.worst(gaps, 2_000.0, 5_000.0)!!.carryL, 1e-9)
    }

    @Test fun heatAndRateScale() {
        val hot = WaterCarry.worst(WaterCarry.gaps(flat, emptyList(), emptyList(), pace, WaterCarry.Heat.HOT, WaterCarry.Rate.MORE))!!
        // 6.2 mi ≈ 3h 37m; × 1.0 × 1.3 = 4.7 L + 0.5 = 5.2 → 5.5
        assertEquals(5.5, hot.carryL, 1e-9)
    }

    @Test fun climbingCountsOneAndAHalfTimes() {
        // 10 km with 1,000 ft of climb: 186 min of walking + 30 min for the climb, plus breaks = 252 min,
        // 35 of them from the climb. (252 + 0.5 × 35) / 60 × 0.5 L = 2.25 L.
        val hill = RouteStats(10_000.0, 304.8, 0.0, listOf(0.0 to 100.0, 10_000.0 to 404.8))
        val g = WaterCarry.worst(WaterCarry.gaps(hill, emptyList(), emptyList(), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL))!!
        assertEquals(252, g.minutes)
        assertEquals(35, g.climbMin)
        assertEquals(2.2458, g.walkL, 0.001)
        // Flat ground is unchanged.
        val f = WaterCarry.worst(WaterCarry.gaps(flat, emptyList(), emptyList(), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL))!!
        assertEquals(0, f.climbMin)
        assertEquals(f.minutes / 60.0 * 0.5, f.walkL, 1e-9)
    }

    @Test fun guesses() {
        assertEquals(WaterCarry.Heat.HOT, WaterCarry.guess(8, 2_000.0))
        assertEquals(WaterCarry.Heat.WARM, WaterCarry.guess(8, 5_000.0))
        assertEquals(WaterCarry.Heat.MILD, WaterCarry.guess(8, 8_000.0))
        assertEquals(WaterCarry.Heat.COOL, WaterCarry.guess(1, 1_000.0))
        assertEquals(WaterCarry.Heat.WARM, WaterCarry.Heat.of(80))
        assertEquals(2.5, WaterCarry.roundUp(2.01), 1e-9)
        assertEquals(2.0, WaterCarry.roundUp(2.0), 1e-9)
    }

    @Test fun eachDayOnlyItsOwnMiles() {
        val days = listOf(0.0 to 5_000.0, 5_000.0 to 10_000.0)
        // Dry camp at 5 km: day 1's walk stops at the last water before camp, the fill-up covers
        // 2–8 km including the night, and day 2 starts counting after the morning's first water.
        val dry = WaterCarry.days(flat, listOf(2_000.0, 8_000.0), days, listOf(false), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        assertEquals(0.0, dry[0].walk!!.fromM, 0.1); assertEquals(2_000.0, dry[0].walk!!.toM, 0.1)
        assertEquals(2_000.0, dry[0].campFill!!.fromM, 0.1); assertEquals(8_000.0, dry[0].campFill!!.toM, 0.1)
        assertEquals(5.0, dry[0].campFill!!.carryL, 1e-9)
        assertEquals(8_000.0, dry[1].walk!!.fromM, 0.1); assertEquals(10_000.0, dry[1].walk!!.toM, 0.1)
        assertEquals(null, dry[1].campFill)
        // Water at camp: each day's longest stretch is its own 3 km to or from camp.
        val wet = WaterCarry.days(flat, listOf(2_000.0, 8_000.0), days, listOf(true), pace, WaterCarry.Heat.MILD, WaterCarry.Rate.NORMAL)
        assertEquals(3_000.0, wet[0].walk!!.lengthM, 0.1); assertEquals(5_000.0, wet[0].walk!!.toM, 0.1)
        assertEquals(3_000.0, wet[1].walk!!.lengthM, 0.1); assertEquals(5_000.0, wet[1].walk!!.fromM, 0.1)
        assertEquals(null, wet[0].campFill)
        // The whole day: 5 km is 109 minutes, × 0.5 L an hour = 0.9 L, rounded up.
        assertEquals(5_000.0, wet[0].total!!.lengthM, 0.1)
        assertEquals(109, wet[0].total!!.minutes)
        assertEquals(1.0, wet[0].total!!.carryL, 1e-9)
    }

    @Test fun whichWaterCountsAsReliable() {
        assertEquals(false, WaterTools.unreliable(Features.Point("Minnie Scott Spring", "spring", 44.0, -121.0)))
        assertEquals(false, WaterTools.unreliable(Features.Point("White Branch", "stream", 44.0, -121.0)))
        assertEquals(true, WaterTools.unreliable(Features.Point("White Branch", "stream", 44.0, -121.0, seasonal = true)))
        assertEquals(true, WaterTools.unreliable(Features.Point(null, "stream", 44.0, -121.0)))   // small unnamed crossing
        assertEquals(false, WaterTools.unreliable(Features.Point(null, "spring", 44.0, -121.0)))
    }
}
