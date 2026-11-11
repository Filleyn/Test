package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackStatsTest {
    // About 111 m apart, a minute between each; the first point has no elevation.
    private val pts = listOf(
        TrackPoint(44.000, -121.0, null, 0),
        TrackPoint(44.001, -121.0, 100.0, 60_000),
        TrackPoint(44.002, -121.0, 110.0, 120_000),
        TrackPoint(44.003, -121.0, 108.0, 180_000),   // a 2 m wiggle: ignored
        TrackPoint(44.004, -121.0, 104.0, 240_000),
        TrackPoint(44.004, -121.0, 104.0, 600_000),   // a long stop
    )

    @Test fun totals() {
        val s = TrackStats.of(pts)
        assertEquals(444.8, s.distanceM, 1.0)
        assertEquals(10.0, s.gainM, 1e-9)
        assertEquals(6.0, s.lossM, 1e-9)
        assertEquals(240_000L, s.movingMs)
        assertEquals(600_000L, s.totalMs)
    }

    @Test fun runningMatchesAtEveryPoint() {
        val r = TrackStats.Running()
        assertEquals(TrackStats(0.0, 0.0, 0.0, 0, 0), r.stats())
        pts.forEachIndexed { i, p ->
            r.add(p)
            assertEquals(TrackStats.of(pts.take(i + 1)), r.stats())
        }
    }
}
