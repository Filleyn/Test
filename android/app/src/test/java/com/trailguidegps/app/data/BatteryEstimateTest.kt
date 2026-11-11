package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryEstimateTest {
    private val hour = 3_600_000L

    @Test fun lines() {
        assertEquals(null, BatteryEstimate.line(80, null, hour))
        assertEquals("Battery 80%", BatteryEstimate.line(null, 80, hour))
        assertEquals("Battery 79%", BatteryEstimate.line(80, 79, 10 * 60_000L))              // too soon to say
        assertEquals("Battery 79%", BatteryEstimate.line(80, 79, 40 * 60_000L))              // too small a drop
        assertEquals("Battery 79% · barely dropping", BatteryEstimate.line(80, 79, 2 * hour))
        assertEquals(null, BatteryEstimate.line(60, 70, hour))                                // charging
        // 10% in 2 hours is 5% an hour: 70% lasts 14 hours.
        assertEquals("Battery 70% · about 14h left at this rate", BatteryEstimate.line(80, 70, 2 * hour))
        // 20% in an hour: 30% lasts an hour and a half.
        assertEquals("Battery 30% · about 1h 30m left at this rate. Dim the screen or turn on battery saver.", BatteryEstimate.line(50, 30, hour))
    }
}
