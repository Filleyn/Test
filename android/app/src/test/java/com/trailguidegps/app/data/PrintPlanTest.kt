package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPlanTest {
    @Test fun paperAndGround() {
        // 540 pt of paper is 7.5 in; at 1:24,000 that's 4,572 m of ground.
        assertEquals(4572.0, PrintPlan.groundM(540.0, 24_000), 0.5)
        assertEquals(540.0, PrintPlan.paperPt(4572.0, 24_000), 0.1)
        // A mile at 1:24,000 is 2.64 in.
        assertEquals(190.1, PrintPlan.paperPt(Geo.M_PER_MI, 24_000), 0.2)
    }

    @Test fun zoom() {
        // At the equator, zoom 0 spreads 40,075 km over 512 points.
        assertEquals(0.0, PrintPlan.zoomFor(0.0, 40_075_016.686, 512.0), 1e-6)
        assertEquals(10.0, PrintPlan.zoomFor(0.0, 40_075_016.686 / 1024, 512.0), 1e-6)
    }

    @Test fun northSouthRouteIsOneColumnInOrder() {
        // 20 km due north from 44° N.
        val coords = (0..20).map { doubleArrayOf(44.0 + it * 1000 / 110_574.0, -121.0) }
        val pages = PrintPlan.pages(coords, 24_000)
        // Each page covers 5,351 m of ground north–south, stepping 4,923 m with the overlap.
        assertEquals(4, pages.size)
        assertEquals(listOf(1, 2, 3, 4), pages.map { it.n })
        assertTrue(pages.zipWithNext().all { (a, b) -> b.lat > a.lat })
        assertTrue(pages.all { kotlin.math.abs(it.lon + 121.0) < 1e-9 })
        assertEquals(0.0, pages.first().fromM, 1.0)
        assertEquals(20_110.0, pages.last().toM, 200.0)
        // Every spot on the route is on some page.
        coords.forEach { c ->
            assertTrue(pages.any { p -> PrintPlan.corners(p).let { k -> c[0] <= k[0][0] && c[0] >= k[2][0] && c[1] >= k[0][1] && c[1] <= k[1][1] } })
        }
    }

    @Test fun smallerScaleNeedsFewerPages() {
        val coords = (0..30).map { doubleArrayOf(44.0 + it * 0.003, -121.0 + it * 0.004) }
        val big = PrintPlan.pages(coords, 24_000).size
        val small = PrintPlan.pages(coords, 62_500).size
        assertTrue("$big vs $small", small < big)
        assertEquals(1, small)
    }
}
