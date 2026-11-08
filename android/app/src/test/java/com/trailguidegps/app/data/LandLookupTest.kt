package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Real Oregon public-land tiles (zoom 12) around a few known spots, cut from the map build. */
class LandLookupTest {
    private val lookup = LandLookup(File(javaClass.classLoader!!.getResource("publiclands-test.pmtiles")!!.toURI()))

    @Test fun tileIds() {
        assertEquals(0L, LandLookup.tileId(0, 0, 0))
        assertEquals(2L, LandLookup.tileId(1, 0, 1))
        assertEquals(9244480L, LandLookup.tileId(12, 662, 1487))
        assertEquals(9237778L, LandLookup.tileId(12, 713, 1470))
    }

    @Test fun southSister() {
        val h = lookup.at(44.1035, -121.7693)!!
        assertEquals("Three Sisters Wilderness", h.special?.get("name"))
        assertEquals("WA", h.special?.get("des"))
        assertEquals("Willamette National Forest", h.boundary?.get("name"))
        assertEquals("USFS", h.land?.get("mgr"))
        val info = AreaStore.describe(listOf(h), "9 mi W of Sisters", 44.1035, -121.7693, "x")
        assertEquals("Three Sisters Wilderness · Willamette National Forest · 9 mi W of Sisters", info.line)
        assertEquals(listOf("Three Sisters Wilderness", "Willamette National Forest"), info.names)
    }

    @Test fun others() {
        assertEquals("Smith Rock State Park", lookup.at(44.3670, -121.1410)?.land?.get("name"))
        assertEquals("Eagle Cap Wilderness", lookup.at(45.2000, -117.3000)?.special?.get("name"))
        assertEquals("Crater Lake National Park", lookup.at(42.9400, -122.1000)?.boundary?.get("name"))
        assertEquals("Mt. Hood National Forest", lookup.at(45.0840, -121.9380)?.boundary?.get("name"))
        val crater = AreaStore.describe(listOf(lookup.at(42.9400, -122.1000)), null, 0.0, 0.0, "x")
        assertEquals("Crater Lake National Park", crater.line) // the "proposed wilderness" is left out
    }

    @Test fun privateLandAndMissingTiles() {
        val farm = lookup.at(44.6218, -123.0923)
        assertNotNull(farm)
        assertNull(farm!!.special); assertNull(farm.boundary); assertNull(farm.land)
        assertEquals("12 mi N of Corvallis", AreaStore.describe(listOf(farm), "12 mi N of Corvallis", 0.0, 0.0, "x").line)
        assertNull(lookup.at(46.5, -123.5)) // no tile there in the test file
    }
}
