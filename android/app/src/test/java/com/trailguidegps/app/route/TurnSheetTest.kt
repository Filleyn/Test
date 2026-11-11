package com.trailguidegps.app.route

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnSheetTest {
    // North for 600 m on A, a 60 m wobble onto "X" at the junction, then east for 600 m on B.
    private fun samples(): List<TurnSheet.Sample> {
        val out = ArrayList<TurnSheet.Sample>()
        val dLat = 60.0 / 111_320.0
        val dLon = 60.0 / (111_320.0 * Math.cos(Math.toRadians(44.0)))
        for (i in 0..10) out += TurnSheet.Sample(i * 60.0, 44.0 + i * dLat, -121.0, if (i == 10) "X" else "A Trail")
        for (i in 1..10) out += TurnSheet.Sample(600.0 + i * 60.0, 44.0 + 10 * dLat, -121.0 + i * dLon, "B Trail")
        return out
    }

    @Test fun listsEachChangeOfTrailWithItsTurn() {
        val steps = TurnSheet.steps(samples(), 1200.0)
        assertEquals(listOf("Start on A Trail", "Right onto B Trail"), steps.map { "${it.turn} ${it.name}" })
        assertEquals(0.0, steps[0].atM, 0.1)
        assertEquals(600.0, steps[0].lengthM, 61.0)       // the wobble's 60 m goes to one side or the other
        assertEquals(1200.0, steps[0].lengthM + steps[1].lengthM, 0.1)
    }

    @Test fun oneTrailIsOneStep() {
        val one = samples().map { TurnSheet.Sample(it.atM, it.lat, it.lon, "A Trail") }
        val steps = TurnSheet.steps(one, 1200.0)
        assertEquals(1, steps.size)
        assertEquals("0.0 mi: Start on A Trail (0.7 mi)", steps[0].line())
    }

    @Test fun backToTheSameTrailAfterAShortConnectorIsNotATurn() {
        val s = samples().map { TurnSheet.Sample(it.atM, it.lat, it.lon, if (it.name == "X") "X" else "A Trail") }
        assertEquals(1, TurnSheet.steps(s, 1200.0).size)
    }
}
