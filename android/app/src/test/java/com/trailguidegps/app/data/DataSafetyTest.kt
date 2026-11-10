package com.trailguidegps.app.data

import android.location.Location
import com.trailguidegps.app.record.TrackRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The fixes that keep a recording or a list from being lost when something goes wrong. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSafetyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun fix(i: Int) = Location("gps").apply {
        latitude = 44.0 + i * 0.001; longitude = -121.0; altitude = 1000.0 + i; accuracy = 5f; time = 1_700_000_000_000L + i * 10_000L
    }

    @After fun tidy() {
        TrackRecorder.stop()
        TrackRecorder.discardRecoverable(app)
    }

    @Test fun stoppingKeepsTheOnDiskCopyUntilItIsDiscarded() {
        TrackRecorder.start(app)
        repeat(3) { TrackRecorder.add(fix(it)) }
        val pts = TrackRecorder.stop()
        assertEquals(3, pts.size)
        // Not yet saved anywhere else: the points must still be recoverable.
        assertEquals(3, TrackRecorder.recoverable(app).size)
        TrackRecorder.discardRecoverable(app)
        assertEquals(0, TrackRecorder.recoverable(app).size)
    }

    @Test fun aKilledRecordingCarriesOn() {
        TrackRecorder.start(app)
        repeat(3) { TrackRecorder.add(fix(it)) }
        TrackRecorder.stop()                       // as if the app was killed: memory gone, file still there
        assertTrue(TrackRecorder.resume(app))
        assertEquals(3, TrackRecorder.state.value!!.points.size)
        TrackRecorder.add(fix(3))
        assertEquals(4, TrackRecorder.state.value!!.points.size)
        TrackRecorder.stop()
        assertEquals(4, TrackRecorder.recoverable(app).size)   // the new point reached the file too
    }

    @Test fun nothingToResumeWithoutACutOffRecording() {
        assertEquals(false, TrackRecorder.resume(app))
    }

    @Test fun oneBadWaypointDoesNotEmptyTheList() {
        val f = File(app.filesDir, "waypoints.json")
        f.writeText("""[{"id":"a","name":"Camp","lat":44.1,"lon":-121.2},{"id":"b","name":"Broken"},{"id":"c","name":"Lake","lat":44.2,"lon":-121.3}]""")
        val store = WaypointStore(app)
        assertEquals(listOf("Camp", "Lake"), store.items.value.map { it.name })
        f.delete()
    }

    @Test fun anUnreadableWaypointFileIsSetAsideBeforeBeingReplaced() {
        val f = File(app.filesDir, "waypoints.json")
        f.writeText("this is not the list it should be")
        val store = WaypointStore(app)
        assertEquals(0, store.items.value.size)
        store.upsert(Waypoint(id = "n", name = "New", lat = 44.0, lon = -121.0))
        val aside = app.filesDir.listFiles { x -> x.name.startsWith("waypoints.json.unreadable-") }!!
        assertEquals(1, aside.size)
        assertEquals("this is not the list it should be", aside[0].readText())
        aside.forEach { it.delete() }; f.delete()
    }

    @Test fun savedTracksReadBack() {
        val store = TrackStore(app)
        val pts = (0..4).map { TrackPoint(44.0 + it * 0.001, -121.0, 1000.0, 1_700_000_000_000L + it * 10_000L) }
        val s = store.save("Test hike", pts)
        assertEquals(5, s.pointCount)
        assertEquals(5, store.load(s.id)!!.second.size)
        store.delete(s.id)
    }

    @Test fun trackListSurvivesARestartAndFollowsChanges() {
        val pts = (0..4).map { TrackPoint(44.0 + it * 0.001, -121.0, 1000.0, 1_700_000_000_000L + it * 10_000L) }
        val first = TrackStore(app)
        val s = first.save("Morning walk", pts)
        // A fresh store (the next launch) lists the same track with the same totals, from the index.
        val second = TrackStore(app)
        val again = second.summaries.value.single { it.id == s.id }
        assertEquals("Morning walk", again.name)
        assertEquals(s.stats.distanceM, again.stats.distanceM, 0.001)
        assertEquals(5, again.pointCount)
        // A rename, more points and a delete all show up, in this store and after another restart.
        second.rename(s.id, "Evening walk")
        assertEquals("Evening walk", TrackStore(app).summaries.value.single { it.id == s.id }.name)
        second.save("Evening walk", pts + TrackPoint(44.01, -121.0, 1000.0, 1_700_000_100_000L), s.id)
        assertEquals(6, TrackStore(app).summaries.value.single { it.id == s.id }.pointCount)
        second.delete(s.id)
        assertEquals(0, TrackStore(app).summaries.value.count { it.id == s.id })
    }

    @Test fun aFileChangedBehindTheIndexIsReadAgain() {
        val pts = (0..4).map { TrackPoint(44.0 + it * 0.001, -121.0, 1000.0, 1_700_000_000_000L + it * 10_000L) }
        val store = TrackStore(app)
        val s = store.save("Before", pts)
        // As a restored backup does: the file is replaced without the store knowing.
        val f = File(File(app.filesDir, "tracks"), "${s.id}.json")
        f.writeText(f.readText().replace("\"Before\"", "\"After restore\""))
        store.reload()
        assertEquals("After restore", store.summaries.value.single { it.id == s.id }.name)
        store.delete(s.id)
    }
}
