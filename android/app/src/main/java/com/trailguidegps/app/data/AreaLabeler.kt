package com.trailguidegps.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the "where it is" line for every saved route, track, waypoint and trail note filled in
 * and up to date ("Mt. Hood National Forest · 12 mi SE of Estacada"), in the background. Split
 * out of the view model; it reads the downloaded maps through [stores] and [features].
 */
class AreaLabeler(
    val store: AreaStore,
    private val stores: () -> List<MapStore>,
    private val features: () -> Features?,
) {
    val areas: StateFlow<Map<String, AreaStore.Info>> get() = store.areas
    private val lookups = HashMap<String, LandLookup>()

    /** Public land at a spot, from whichever downloaded state covers it (null if none does). */
    fun landAt(lat: Double, lon: Double): LandLookup.Hit? {
        var empty: LandLookup.Hit? = null
        for (st in stores()) {
            val b = st.bbox() ?: continue
            if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) continue
            val f = File(st.dir, "publiclands.pmtiles")
            if (!f.exists()) continue
            val hit = runCatching { synchronized(lookups) { lookups.getOrPut(f.path) { LandLookup(f) } }.at(lat, lon) }.getOrNull() ?: continue
            if (hit.special != null || hit.boundary != null || hit.land != null) return hit
            empty = hit
        }
        return empty
    }

    private fun info(points: List<DoubleArray>, sig: String): AreaStore.Info? {
        val start = points.firstOrNull() ?: return null
        val hits = AreaStore.samples(points).map { landAt(it[0], it[1]) }
        return AreaStore.describe(hits, features()?.nearTown(start[0], start[1]), start[0], start[1], sig)
    }

    /**
     * Watches the saved items and the maps, and fills in any line that's missing or out of date.
     * [maps] changes whenever a state is added or removed or its places finish loading.
     */
    fun start(scope: CoroutineScope, routes: RouteStore, tracks: TrackStore, waypoints: WaypointStore, notes: TrailNoteStore, maps: Flow<String>) {
        scope.launch {
            combine(routes.summaries, tracks.summaries, waypoints.items, notes.items, maps) { r, t, w, n, m -> Snapshot(r, t, w, n, m) }.collectLatest { s ->
                delay(1500)
                withContext(Dispatchers.IO) {
                    val have = store.areas.value
                    val changes = HashMap<String, AreaStore.Info>()
                    val keep = HashSet<String>()
                    fun want(key: String, sig: String, points: () -> List<DoubleArray>) {
                        keep += key
                        val full = "$sig|${s.maps}"
                        if (have[key]?.sig == full) return
                        runCatching { info(points(), full) }.getOrNull()?.let { changes[key] = it }
                    }
                    s.routes.forEach { r -> want("r:${r.id}", "%.0f".format(r.distanceM)) { routes.load(r.id)?.coords.orEmpty() } }
                    s.tracks.forEach { t -> want("t:${t.id}", "${t.pointCount}") { tracks.load(t.id)?.second.orEmpty().map { doubleArrayOf(it.lat, it.lon) } } }
                    s.waypoints.forEach { p -> want("w:${p.id}", "${p.lat},${p.lon}") { listOf(doubleArrayOf(p.lat, p.lon)) } }
                    s.notes.forEach { p -> want("n:${p.id}", "${p.lat},${p.lon}") { listOf(doubleArrayOf(p.lat, p.lon)) } }
                    store.update(changes, keep)
                }
            }
        }
    }

    private class Snapshot(val routes: List<RouteSummary>, val tracks: List<TrackSummary>, val waypoints: List<Waypoint>,
                           val notes: List<TrailNote>, val maps: String)
}
