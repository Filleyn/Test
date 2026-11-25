package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpxCourseTest {
    @Test fun courseIsOneTrackThatReadsBack() {
        val coords = listOf(doubleArrayOf(44.0, -121.0), doubleArrayOf(44.001, -121.001), doubleArrayOf(44.002, -121.0))
        val xml = Gpx.course("Lake & Back <loop>", coords, listOf(1500.0, 1512.5, null), "Bring bug spray")
        val back = Gpx.read(xml.byteInputStream())
        assertEquals(1, back.tracks.size)
        assertEquals(0, back.routes.size)
        assertEquals("Lake & Back <loop>", back.tracks[0].first)
        assertEquals(3, back.tracks[0].second.size)
        assertEquals(1512.5, back.tracks[0].second[1].eleM!!, 0.01)
        assertEquals(null, back.tracks[0].second[2].eleM)
        assertEquals(44.002, back.tracks[0].second[2].lat, 1e-9)
    }
}
