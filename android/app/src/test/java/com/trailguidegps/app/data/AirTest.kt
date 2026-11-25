package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirTest {
    // Shaped like NOAA's file: a light plume over central Oregon, a heavy one in Mexico.
    private val kml = """<?xml version="1.0" encoding="UTF-8"?><kml><Document>
        <Folder><name>Smoke (Light)</name>
        <Placemark><description><![CDATA[<div>Density: Light</div>]]></description><styleUrl>#Smoke_Light_style</styleUrl>
        <Polygon><outerBoundaryIs><LinearRing><coordinates>
        -122.0,43.5,0 -120.5,43.5,0 -120.5,44.5,0 -122.0,44.5,0 -122.0,43.5,0
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Folder>
        <Folder><name>Smoke (Heavy)</name>
        <Placemark><styleUrl>#Smoke_Heavy_style</styleUrl><Polygon><outerBoundaryIs><LinearRing><coordinates>
        -100,20,0 -99,20,0 -99,21,0 -100,21,0 -100,20,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Folder>
        </Document></kml>"""
    private val oregon = listOf(-125.0, 41.5, -116.0, 46.5)

    @Test fun smokeKeepsOnlyPlumesInTheArea() {
        val g = JSONObject(AirStore.smokeGeoJson(kml, oregon))
        val f = g.getJSONArray("features")
        assertEquals(1, f.length())
        assertEquals("Light", f.getJSONObject(0).getJSONObject("properties").getString("density"))
        assertEquals(5, f.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).length())
    }

    private val aqi = """{"type":"FeatureCollection","features":[
        {"type":"Feature","properties":{"gridcode":1,"Timestamp":1795300000000},"geometry":{"type":"Polygon","coordinates":[[[-125,41],[-116,41],[-116,47],[-125,47],[-125,41]]]}},
        {"type":"Feature","properties":{"gridcode":3,"Timestamp":1795300000000},"geometry":{"type":"MultiPolygon","coordinates":[[[[-121.5,44.0],[-121.0,44.0],[-121.0,44.3],[-121.5,44.3],[-121.5,44.0]]]]}}]}"""

    @Test fun valueAtTakesTheWorstArea() {
        assertEquals(1, AirStore.valueAt(aqi, 43.0, -123.0, "gridcode"))
        assertEquals(3, AirStore.valueAt(aqi, 44.1, -121.2, "gridcode"))
        assertNull(AirStore.valueAt(aqi, 30.0, -100.0, "gridcode"))
    }

    @Test fun routeLine() {
        val now = 1_795_300_000_000L
        val data = AirData(AirStore.smokeGeoJson(kml, oregon), aqi, "2026-11-23", now, now)
        val route = listOf(doubleArrayOf(43.0, -123.0), doubleArrayOf(44.1, -121.2))
        val line = AirStore.routeLine(data, route, now)!!
        assert(line.contains(": Good at the start, unhealthy for sensitive groups in places along the route.")) { line }
        assert(line.endsWith("Smoke seen by satellite on Nov 23: light, over part of the route.")) { line }
        assertNull(AirStore.routeLine(data, route, now + 3 * 86_400_000L))
    }
}
