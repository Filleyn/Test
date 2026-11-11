package com.trailguidegps.app.route

import com.trailguidegps.app.data.RouteLeg
import com.trailguidegps.app.data.RoutePoint
import com.trailguidegps.app.data.RouteStats
import com.trailguidegps.app.data.RouteStore
import com.trailguidegps.app.data.SavedRoute
import com.trailguidegps.app.data.Terrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The route being tapped out (or a saved one being changed) in the planner. */
data class PlanState(
    val active: Boolean = false,
    val points: List<RoutePoint> = emptyList(),
    val legs: List<RouteLeg> = emptyList(),
    val stats: RouteStats = RouteStats.EMPTY,
    val followTrails: Boolean = true,
    val busy: Boolean = false,
    /** The saved route being changed, or null for a new one. */
    val editing: SavedRoute? = null,
    /** A point tapped to move or remove it. */
    val selected: Int? = null,
    /** New points go before the first one instead of after the last. */
    val addAtStart: Boolean = false,
    /** Come back the same way: the route returns to the start along the way in. */
    val outAndBack: Boolean = false,
) {
    val via: List<String> get() = legs.flatMap { it.names }.fold(mutableListOf()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
}

/**
 * Planning a route: tapping out points, joining them along trails or straight, moving and
 * removing points, coming back the same way, and saving. The view model owns what's on the
 * map; this asks it through the functions passed in.
 */
class RoutePlanner(
    private val scope: CoroutineScope,
    private val routes: RouteStore,
    /** One trail network per downloaded state. */
    private val graphs: () -> List<RouteGraph>,
    private val terrain: () -> Terrain?,
    private val message: (String) -> Unit,
    /** Closes whatever spot is selected on the map. */
    private val clearSelection: () -> Unit,
    private val addRouteToMap: (String) -> Unit,
    private val hideRoute: (String) -> Unit,
    /** A route was saved: put it on the map and, when a folder was picked, in that folder. */
    private val onSaved: (SavedRoute, String?) -> Unit,
) {
    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan.asStateFlow()
    private val planLock = kotlinx.coroutines.sync.Mutex()

    fun startPlanning() {
        clearSelection()
        _plan.value = PlanState(active = true)
    }

    fun cancelPlanning() {
        val editing = _plan.value.editing
        _plan.value = PlanState()
        // Leaving an edit without saving puts the original back on the map, unchanged.
        editing?.let { addRouteToMap(it.id) }
    }

    /**
     * Opens a saved route in the planner to change it. Its line is kept exactly as saved,
     * split at its points; only the parts you change are worked out again.
     */
    fun editRoute(id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { routes.load(id) } ?: return@launch
            // Imported routes have no planning points (or one per GPS fix): give them some along the line.
            val route = if (r.points.size < 2 || r.points.size > 120 || r.points.size == 2 && r.coords.size > 50) withContext(Dispatchers.Default) {
                val coords = if (r.coords.size > 2000) com.trailguidegps.app.data.RouteNear.simplify(r.coords, 5.0) else r.coords
                r.copy(coords = coords, points = com.trailguidegps.app.data.RouteNear.planningPoints(coords))
            } else r
            clearSelection()
            // An out-and-back is edited as its way in; the way back follows.
            val wayIn = if (route.outAndBack && route.outCount in 2..route.coords.size) route.copy(coords = route.coords.take(route.outCount)) else route
            val legs = withContext(Dispatchers.Default) { splitIntoLegs(wayIn) }
            _plan.value = PlanState(active = true, points = route.points, legs = legs, stats = route.stats,
                followTrails = route.points.last().followTrails, editing = route, outAndBack = route.outAndBack)
            hideRoute(id)
        }
    }

    /** The saved line cut at each planning point, with the route's trail/road mix shared out by length. */
    private fun splitIntoLegs(r: SavedRoute): List<RouteLeg> {
        val c = r.coords
        val geo = com.trailguidegps.app.geo.Geo::distanceM
        val idx = IntArray(r.points.size)
        for (j in 1 until r.points.size) {
            val p = r.points[j]
            if (j == r.points.size - 1) { idx[j] = c.size - 1; continue }
            var best = idx[j - 1]
            var bestD = Double.MAX_VALUE
            for (k in idx[j - 1] until c.size) {
                val d = geo(p.lat, p.lon, c[k][0], c[k][1])
                if (d < 3) { best = k; break }
                if (d < bestD) { bestD = d; best = k }
            }
            idx[j] = best
        }
        val t = r.terrain
        val tot = r.stats.distanceM.coerceAtLeast(1.0)
        return (1 until r.points.size).map { j ->
            val part = c.subList(idx[j - 1], (idx[j] + 1).coerceAtMost(c.size)).ifEmpty { listOf(c[idx[j - 1]]) }
            val len = (1 until part.size).sumOf { k -> geo(part[k - 1][0], part[k - 1][1], part[k][0], part[k][1]) }
            val share = len / tot
            RouteLeg(part, len, if (j == 1) r.via else emptyList(), r.points[j].followTrails,
                if (t != null) doubleArrayOf(t.trailM * share, t.trackM * share, t.roadM * share) else DoubleArray(3),
                if (j == 1) t?.sacMax ?: 0 else 0, (t?.sacRatedM ?: 0.0) * share)
        }
    }

    fun setAddAtStart(on: Boolean) { _plan.value = _plan.value.copy(addAtStart = on, selected = null) }
    fun selectPlanPoint(i: Int?) { _plan.value = _plan.value.copy(selected = i) }

    /** Runs a change to the points and legs under the planning lock, then recomputes the totals. */
    private fun changePlan(change: (PlanState) -> Pair<List<RoutePoint>, List<RouteLeg>>?) {
        scope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (!st.active) return@launch
                _plan.value = st.copy(busy = true)
                val result = withContext(Dispatchers.Default) { change(st) }
                if (result == null) { _plan.value = _plan.value.copy(busy = false); return@launch }
                val (pts, legs) = result
                val stats = withContext(Dispatchers.Default) { statsFor(fullLegs(legs, _plan.value.outAndBack)) }
                _plan.value = _plan.value.copy(points = pts, legs = legs, stats = stats, busy = false, selected = null)
            } finally {
                planLock.unlock()
            }
        }
    }

    /** The leg from [a] to [b], drawn the way [b] says (trails or straight). */
    private fun legBetween(a: RoutePoint, b: RoutePoint): Pair<RoutePoint, RouteLeg> {
        val (pt, leg, _) = buildLeg(a, b.lat, b.lon, b.followTrails, b.waypointName)
        return pt to (leg ?: straightLeg(a, pt))
    }

    /** Moves point [i] to a new spot; the legs on either side are worked out again. */
    fun movePlanPoint(i: Int, lat: Double, lon: Double) = changePlan { st ->
        if (i !in st.points.indices) return@changePlan null
        val pts = st.points.toMutableList()
        val legs = st.legs.toMutableList()
        val mode = st.points[i].followTrails
        if (i == 0) {
            pts[0] = buildLeg(null, lat, lon, st.followTrails, null).first.copy(followTrails = true)
        } else {
            val (pt, leg) = legBetween(pts[i - 1], RoutePoint(lat, lon, mode))
            pts[i] = pt; legs[i - 1] = leg
        }
        if (i < pts.size - 1) {
            val (next, leg) = legBetween(pts[i], pts[i + 1])
            pts[i + 1] = next; legs[i] = leg
        }
        pts to legs
    }

    /** Takes out point [i]; its neighbours are joined directly. */
    fun removePlanPoint(i: Int) = changePlan { st ->
        if (i !in st.points.indices) return@changePlan null
        val pts = st.points.toMutableList()
        val legs = st.legs.toMutableList()
        when {
            pts.size == 1 -> { pts.clear(); legs.clear() }
            i == 0 -> { pts.removeAt(0); legs.removeAt(0); pts[0] = pts[0].copy(followTrails = true) }
            i == pts.size - 1 -> { pts.removeAt(i); legs.removeAt(i - 1) }
            else -> {
                pts.removeAt(i)
                legs.removeAt(i)          // the leg out of the removed point
                val (next, leg) = legBetween(pts[i - 1], pts[i])
                pts[i] = next; legs[i - 1] = leg
            }
        }
        pts to legs
    }

    /** Adds a point before the first one, extending the route at its start. */
    private fun addPlanPointAtStart(lat: Double, lon: Double, waypointName: String?) = changePlan { st ->
        val first = buildLeg(null, lat, lon, st.followTrails, waypointName).first.copy(followTrails = true)
        if (st.points.isEmpty()) return@changePlan listOf(first) to emptyList()
        val (old, leg) = legBetween(first, st.points[0].copy(followTrails = st.followTrails))
        (listOf(first, old) + st.points.drop(1)) to (listOf(leg) + st.legs)
    }

    fun setFollowTrails(on: Boolean) { _plan.value = _plan.value.copy(followTrails = on) }

    /** The way in followed by the same legs walked back, for an out-and-back. */
    private fun fullLegs(legs: List<RouteLeg>, outAndBack: Boolean): List<RouteLeg> =
        if (!outAndBack || legs.isEmpty()) legs
        else legs + legs.reversed().map { it.copy(coords = it.coords.reversed(), names = it.names.reversed()) }

    /** Turns "come back the same way" on or off while planning; the totals include the way back. */
    fun setPlanOutAndBack(on: Boolean) {
        scope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (!st.active || st.outAndBack == on) return@launch
                val stats = withContext(Dispatchers.Default) { statsFor(fullLegs(st.legs, on)) }
                _plan.value = _plan.value.copy(outAndBack = on, stats = stats)
            } finally {
                planLock.unlock()
            }
        }
    }

    /** Adds a point; the leg to it follows trails when that mode is on and a connection exists. */
    fun addPlanPoint(lat: Double, lon: Double, waypointName: String? = null) {
        _plan.value.selected?.let { i -> movePlanPoint(i, lat, lon); return }
        if (_plan.value.addAtStart && _plan.value.points.isNotEmpty()) { addPlanPointAtStart(lat, lon, waypointName); return }
        scope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (!st.active) return@launch
                _plan.value = st.copy(busy = true)
                val (point, leg, note) = withContext(Dispatchers.Default) {
                    buildLeg(st.points.lastOrNull(), lat, lon, st.followTrails, waypointName)
                }
                val legs = if (leg != null) st.legs + leg else st.legs
                val stats = withContext(Dispatchers.Default) { statsFor(legs) }
                _plan.value = _plan.value.copy(points = st.points + point, legs = legs, stats = stats, busy = false)
                note?.let { message(it) }
            } finally {
                planLock.unlock()
            }
        }
    }

    fun undoPlanPoint() {
        if (_plan.value.addAtStart && _plan.value.points.size > 1) { removePlanPoint(0); return }
        scope.launch {
            planLock.lock()
            try {
                val st = _plan.value
                if (st.points.isEmpty()) return@launch
                // The first point has no leg; every later point has the leg leading to it.
                val legs = if (st.points.size > 1) st.legs.dropLast(1) else st.legs
                val stats = withContext(Dispatchers.Default) { statsFor(legs) }
                _plan.value = st.copy(points = st.points.dropLast(1), legs = legs, stats = stats)
            } finally {
                planLock.unlock()
            }
        }
    }

    private fun statsFor(legs: List<RouteLeg>): RouteStats {
        if (legs.isEmpty()) return RouteStats.EMPTY
        val coords = ArrayList<DoubleArray>()
        legs.forEach { leg -> leg.coords.forEach { c -> if (coords.lastOrNull()?.let { it[0] == c[0] && it[1] == c[1] } != true) coords += c } }
        return RouteStats.compute(coords, legs.sumOf { it.lengthM }, terrain())
    }

    private fun straightLeg(a: RoutePoint, b: RoutePoint) = RouteLeg(
        listOf(doubleArrayOf(a.lat, a.lon), doubleArrayOf(b.lat, b.lon)),
        com.trailguidegps.app.geo.Geo.distanceM(a.lat, a.lon, b.lat, b.lon), emptyList(), onTrail = false,
    )

    private fun buildLeg(prev: RoutePoint?, lat: Double, lon: Double, follow: Boolean, wp: String?): Triple<RoutePoint, RouteLeg?, String?> {
        // The trail networks (one per downloaded state) that reach each point.
        val gs = graphs()
        val newIn = gs.filter { it.snap(lat, lon) != null }
        val prevIn = if (prev == null) emptyList() else gs.filter { it.snap(prev.lat, prev.lon) != null }
        val g = newIn.firstOrNull { it in prevIn } ?: newIn.firstOrNull() ?: gs.firstOrNull()
        val dist = com.trailguidegps.app.geo.Geo::distanceM
        var snapNew = if (follow && g != null) g.snap(lat, lon) else null
        // A tapped point moves onto the trail; a waypoint keeps its exact spot.
        val point = if (snapNew != null && wp == null) RoutePoint(snapNew.lat, snapNew.lon, follow, wp) else RoutePoint(lat, lon, follow, wp)
        if (prev == null) {
            val note = when {
                follow && g == null -> "The trail network isn't downloaded yet, so routes use straight lines. Check for map updates."
                follow && snapNew == null -> "No trail within a quarter mile of this point."
                else -> null
            }
            return Triple(point, null, note)
        }
        if (!follow) return Triple(point, straightLeg(prev, point), null)
        if (g == null) {
            return Triple(point, straightLeg(prev, point), "The trail network isn't downloaded yet, so this part is a straight line.")
        }
        var snapPrev = g.snap(prev.lat, prev.lon)
        var routed = if (snapPrev != null && snapNew != null && g in prevIn) g.route(snapPrev, snapNew) else null
        if (routed == null) {
            // Across a state line: along one state's trails to the border, then the next state's.
            search@ for (ga in prevIn) for (gb in newIn) {
                if (ga === gb) continue
                val sa = ga.snap(prev.lat, prev.lon) ?: continue
                val sb = gb.snap(lat, lon) ?: continue
                val r = runCatching { com.trailguidegps.app.route.CrossState.route(ga, sa, gb, sb) }.getOrNull() ?: continue
                routed = r; snapPrev = sa; snapNew = sb
                break@search
            }
        }
        if (routed == null || snapPrev == null || snapNew == null) {
            val why = if (snapNew == null || snapPrev == null) "No trail within a quarter mile, so this part is a straight line."
            else "Couldn't find a trail connection, so this part is a straight line."
            return Triple(point, straightLeg(prev, point), why)
        }
        val coords = ArrayList<DoubleArray>()
        var len = routed.lengthM
        val offStart = dist(prev.lat, prev.lon, snapPrev.lat, snapPrev.lon)
        if (offStart > 15) { coords += doubleArrayOf(prev.lat, prev.lon); len += offStart }
        coords += routed.coords
        val offEnd = dist(snapNew.lat, snapNew.lon, point.lat, point.lon)
        if (offEnd > 15) { coords += doubleArrayOf(point.lat, point.lon); len += offEnd }
        return Triple(point, RouteLeg(coords, len, routed.names, onTrail = true, routed.kindM, routed.sacMax, routed.sacRatedM), null)
    }

    /** Saves the planned route; when editing, replaces the original unless [asNew]. */
    fun savePlan(name: String, color: String = com.trailguidegps.app.data.RouteColors.DEFAULT, folderId: String? = null, asNew: Boolean = false) {
        val st = _plan.value
        if (st.points.size < 2) return
        val old = st.editing?.takeIf { !asNew }
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                fun join(legs: List<RouteLeg>) = ArrayList<DoubleArray>().also { coords ->
                    legs.forEach { leg -> leg.coords.forEach { c -> if (coords.lastOrNull()?.let { it[0] == c[0] && it[1] == c[1] } != true) coords += c } }
                }
                val all = fullLegs(st.legs, st.outAndBack)
                val coords = join(all)
                SavedRoute(old?.id ?: RouteStore.newId(), name.ifBlank { "Route" }, old?.createdAt ?: System.currentTimeMillis(), st.points, coords,
                    st.stats, st.via, color, com.trailguidegps.app.data.RouteTerrain.of(all),
                    outAndBack = st.outAndBack, outCount = if (st.outAndBack) join(st.legs).size else 0,
                    // Camps stay if they're still on the route.
                    camps = old?.camps.orEmpty().filter { it.alongM < st.stats.distanceM - 200 },
                    notes = old?.notes.orEmpty(), permits = old?.permits.orEmpty(), tripStart = old?.tripStart ?: 0, tripEnd = old?.tripEnd ?: 0)
                    .also { routes.save(it) }
            }
            _plan.value = PlanState()
            onSaved(saved, folderId)
            message(if (old != null) "Saved changes to \"${saved.name}\"." else "Saved \"${saved.name}\".")
        }
    }
}
