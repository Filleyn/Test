package com.trailguidegps.app.route

import android.location.Location
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.RouteNear
import com.trailguidegps.app.data.RouteStats
import com.trailguidegps.app.data.SavedRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Out-and-backs: Follow stays on the right half, and water is counted on both passes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteFollowerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** 5 km north in 100 m steps, then back the same way: 10 km in all. */
    private val out = (0..50).map { doubleArrayOf(44.0 + it * 0.0008993, -121.0) }
    private val coords = out + out.reversed().drop(1)
    private val total = 10_000.0
    private val route = SavedRoute("r", "Lake and back", 0, emptyList(), coords, RouteStats(total, 0.0, 0.0, listOf(0.0 to 100.0, total to 100.0)),
        emptyList(), outAndBack = true, outCount = out.size)

    /** A fix [km] north of the start and [eastM] metres east of the trail. */
    private fun fix(km: Double, eastM: Double = 0.0) = Location("gps").apply {
        latitude = 44.0 + km * 0.008993; longitude = -121.0 + eastM / 79_900.0; accuracy = 5f; time = System.currentTimeMillis()
    }
    private fun at(km: Double, eastM: Double = 0.0): Double {
        RouteFollower.update(app, fix(km, eastM))
        return RouteFollower.state.value!!.remainingM
    }

    @After fun stop() { RouteFollower.stop(app) }

    @Test fun staysOnTheWayInThenMovesToTheWayBack() {
        RouteFollower.start(route)
        assertEquals(10_000.0, at(0.0), 60.0)       // at the trailhead: all of it to go, not "arrived"
        assertFalse(RouteFollower.state.value!!.arrived)
        assertEquals(8_000.0, at(2.0), 60.0)
        assertEquals(6_000.0, at(4.0), 60.0)
        assertEquals(5_000.0, at(5.0), 60.0)        // the turnaround
        assertEquals(4_000.0, at(4.0), 60.0)        // heading back
        assertEquals(1_000.0, at(1.0), 60.0)
        assertEquals(0.0, at(0.0), 60.0)
        assertTrue(RouteFollower.state.value!!.arrived)
    }

    @Test fun rejoiningOnTheWayBackDoesNotJumpToTheWayIn() {
        RouteFollower.start(route)
        listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 4.0, 3.0).forEach { at(it) }
        // Wander 300 m off the trail for a while, then come back onto it further down.
        at(2.8, 300.0); at(2.6, 300.0); at(2.4, 300.0)
        assertTrue(RouteFollower.state.value!!.offRoute)
        assertEquals(2_000.0, at(2.0), 80.0)        // 2 km from the trailhead on the way back, not 8 km to go
        assertFalse(RouteFollower.state.value!!.offRoute)
    }

    @Test fun quietGpsIsFlaggedAndClears() {
        RouteFollower.start(route)
        at(1.0)
        RouteFollower.checkQuiet(app, System.currentTimeMillis() + 60_000)
        assertFalse(RouteFollower.state.value!!.gpsQuiet)
        RouteFollower.checkQuiet(app, System.currentTimeMillis() + 4 * 60_000)
        assertTrue(RouteFollower.state.value!!.gpsQuiet)
        at(1.2)
        assertFalse(RouteFollower.state.value!!.gpsQuiet)
    }

    @Test fun waterIsCountedOnBothPasses() {
        val spring = Features.Point("Spring", "spring", 44.0 + 2.0 * 0.008993, -121.0005)   // 40 m off the trail, 2 km in
        val lakeAtTurn = Features.Point("Lake", "spring", 44.0 + 5.0 * 0.008993, -121.0003) // at the turnaround
        val hits = RouteNear.project(coords, listOf(spring, lakeAtTurn), 400.0) { true }
        val springs = hits.filter { it.first.name == "Spring" }.map { it.second }
        assertEquals(2, springs.size)
        assertEquals(2_000.0, springs[0], 60.0)
        assertEquals(8_000.0, springs[1], 60.0)
        assertEquals(1, hits.count { it.first.name == "Lake" })     // one pass: you're only there once
        // A one-way walk past the spring counts it once.
        assertEquals(1, RouteNear.project(out, listOf(spring), 400.0) { true }.size)
    }
}
