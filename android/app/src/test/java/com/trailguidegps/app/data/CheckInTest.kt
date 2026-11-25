package com.trailguidegps.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.TimeZone

class CheckInTest {
    private val pst = TimeZone.getTimeZone("America/Los_Angeles")
    // Sat Nov 21 2026, 2:14 PM Pacific.
    private val now = 1_795_299_240_000L
    private val base = CheckIn.Info(44.061812, -121.315314, 5f, now - 60_000, "Three Sisters Loop", 10_000.0, 20_000.0,
        backBy = now + 3 * 3_600_000L, callBy = now + 6 * 3_600_000L, now = now, zone = pst)

    @Test fun okMessage() {
        val m = CheckIn.message(CheckIn.Kind.OK, base)
        assertEquals("TrailGuide check-in: I'm OK. At 44.06181, -121.31531 (±16 ft) at 2:13 PM: https://maps.google.com/?q=44.06181,-121.31531. " +
            "6.2 of 12.4 mi on Three Sisters Loop. Planned out by Sat 5:14 PM. Call for help if no word by Sat 8:14 PM. Sent 2:14 PM.", m)
    }

    @Test fun lateAndOut() {
        val late = CheckIn.message(CheckIn.Kind.LATE, base)
        assertTrue(late.startsWith("TrailGuide check-in: running late, but I'm OK."))
        assertTrue(late.contains("I'd planned to be out by Sat 5:14 PM; I'll text again when I'm out."))
        val out = CheckIn.message(CheckIn.Kind.OUT, base)
        assertTrue(out.startsWith("TrailGuide check-in: I'm out safely. Trip done."))
        assertFalse(out.contains("Planned out"))
        assertFalse(out.contains("Three Sisters"))
    }

    @Test fun noFixNoPlan() {
        val m = CheckIn.message(CheckIn.Kind.OK, CheckIn.Info(null, null, now = now, zone = pst))
        assertEquals("TrailGuide check-in: I'm OK. No GPS position right now. Sent 2:14 PM.", m)
    }

    @Test fun recipients() {
        assertEquals("+15415551234;5035550000", CheckIn.recipients(listOf("+1 (541) 555-1234", "503-555-0000", "  "), samsung = false))
        assertEquals("5415551234,5035550000", CheckIn.recipients(listOf("541 555 1234", "503.555.0000"), samsung = true))
    }
}
