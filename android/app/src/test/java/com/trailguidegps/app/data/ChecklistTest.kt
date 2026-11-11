package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistTest {
    private val dayHike = Checklist.Trip(days = 1, nights = 0, heat = WaterCarry.Heat.MILD, carryL = 2.0, dryCamp = false,
        month = 9, highFt = 5000.0, steepestDayFt = 1200.0)

    private fun ids(t: Checklist.Trip) = Checklist.suggest(t).map { it.id }.toSet()

    @Test fun dayHikeHasEssentialsButNoCampGear() {
        val ids = ids(dayHike)
        assertTrue(ids.containsAll(listOf("headlamp", "firstaid", "map", "filter", "plan")))
        assertFalse("tent" in ids); assertFalse("stove" in ids); assertFalse("drycamp" in ids)
        assertFalse("electrolytes" in ids); assertFalse("bugs" in ids); assertFalse("poles" in ids)
        assertEquals("Bottles or a bladder for at least 2 L", Checklist.suggest(dayHike).first { it.id == "bottles" }.label)
    }

    @Test fun overnightAddsCampGearAndFollowsConditions() {
        val trip = dayHike.copy(days = 3, nights = 2, heat = WaterCarry.Heat.HOT, carryL = 5.5, dryCamp = true, month = 7, steepestDayFt = 3100.0)
        val ids = ids(trip)
        assertTrue(ids.containsAll(listOf("tent", "bag", "stove", "bear", "drycamp", "electrolytes", "sunshirt", "bugs", "poles")))
        assertFalse("gloves" in ids)
        val items = Checklist.suggest(trip)
        assertEquals("Dinners and breakfasts for 2 nights", items.first { it.id == "meals" }.label)
        assertEquals("Lunch and snacks for 3 days", items.first { it.id == "food" }.label)
        assertEquals(items.size, items.map { it.id }.toSet().size)   // ids are unique
    }

    @Test fun coolAndSnow() {
        val ids = ids(dayHike.copy(heat = WaterCarry.Heat.COOL, month = 5, highFt = 7200.0))
        assertTrue(ids.containsAll(listOf("gloves", "insulation", "traction")))
        assertFalse("traction" in ids(dayHike.copy(month = 8, highFt = 7200.0)))
    }

    @Test fun stateIsKeptAndFeedsTheTripPlan() {
        val app = RuntimeEnvironment.getApplication()
        val store = ChecklistStore(app)
        val state = ChecklistStore.State(checked = setOf("headlamp", "x:1", "tent"), removed = setOf("whistle"), added = listOf("x:1" to "Camera"))
        store.put("r1", state)
        assertEquals(state, ChecklistStore(app).get("r1"))          // read back from the file
        assertEquals(ChecklistStore.State(), ChecklistStore(app).get("other"))
        // Only ticked things that are on this list: no tent on a day hike.
        assertEquals("Headlamp and spare batteries; Camera.", Checklist.packedLine(Checklist.suggest(dayHike), state))
        assertEquals(null, Checklist.packedLine(Checklist.suggest(dayHike), ChecklistStore.State()))
        store.drop("r1")
        assertEquals(ChecklistStore.State(), ChecklistStore(app).get("r1"))
    }
}
