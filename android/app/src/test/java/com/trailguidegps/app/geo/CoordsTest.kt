package com.trailguidegps.app.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordsTest {
    private fun check(input: String, lat: Double, lon: Double, tol: Double = 1e-4) {
        val p = Coords.parse(input) ?: throw AssertionError("\"$input\" didn't parse")
        assertEquals("lat for \"$input\"", lat, p.lat, tol)
        assertEquals("lon for \"$input\"", lon, p.lon, tol)
    }

    @Test fun decimal() {
        check("44.0582, -121.3153", 44.0582, -121.3153)
        check("44.0582 -121.3153", 44.0582, -121.3153)
        check("44.0582,-121.3153", 44.0582, -121.3153)
        check("geo:44.0582,-121.3153", 44.0582, -121.3153)
        check("44.0582 N 121.3153 W", 44.0582, -121.3153)
        check("44.0582N, 121.3153W", 44.0582, -121.3153)
        check("W 121.3153 N 44.0582", 44.0582, -121.3153)
        check("61.2181° N, 149.9003° W", 61.2181, -149.9003)
        check("-121.3153, 44.0582", 44.0582, -121.3153)
    }

    @Test fun westAssumed() {
        val p = Coords.parse("44.0582 121.3153")!!
        assertEquals(-121.3153, p.lon, 1e-6)
        assertTrue(p.assumedWest)
        check("44.0582 N 121.3153", 44.0582, -121.3153)
    }

    @Test fun degreesMinutesSeconds() {
        val lat = 44 + 3 / 60.0 + 29.5 / 3600
        val lon = -(121 + 18 / 60.0 + 55.1 / 3600)
        check("44°3'29.5\"N 121°18'55.1\"W", lat, lon)
        check("44° 3′ 29.5″ N, 121° 18′ 55.1″ W", lat, lon)
        check("44 03 29.5 N 121 18 55.1 W", lat, lon)
        check("44 3 29.5 121 18 55.1", lat, lon)
        check("N44° 03.492' W121° 18.918'", 44 + 3.492 / 60, -(121 + 18.918 / 60))
    }

    @Test fun utm() {
        check("10T 600000 4900000", 44.246371, -121.747523, 1e-5)
        check("10T 600000E 4900000N", 44.246371, -121.747523, 1e-5)
        check("10 T 600000 4900000", 44.246371, -121.747523, 1e-5)
        check("4Q 441000 2450000", 22.154146, -159.572204, 1e-5)
        check("6V 344000 6790000", 61.213188, -149.904452, 1e-5)
    }

    @Test fun utmRoundTrip() {
        for ((la, lo) in listOf(44.0582 to -121.3153, 36.1 to -112.1, 21.3 to -157.8, 64.8 to -147.7)) {
            val u = Geo.utm(la, lo)
            check("${u.zone}${u.band} ${u.easting} ${u.northing}", la, lo, 2e-5)
        }
    }

    @Test fun notCoordinates() {
        for (s in listOf("pacific", "news", "Lost Lake", "44", "", "  ", "95 200", "44.5 30 121 W", "N S", "PCT 2650"))
            assertNull("\"$s\" shouldn't parse", Coords.parse(s))
    }
}
