package com.trailguidegps.app.data

import com.trailguidegps.app.geo.Geo

/** One search hit: a trail (at its closest point to you) or a place. */
data class SearchResult(
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val point: Features.Point? = null,
    /** "14 mi NE of Sisters", filled in for the results shown. */
    val near: String? = null,
)

/** Offline search over the named places and trails in the map files. */
object Search {
    private const val LIMIT = 60

    /** Search filters: label and the kinds it keeps ("trail" means named trails). Null keeps everything. */
    val FILTERS: List<Pair<String, Set<String>?>> = listOf(
        "All" to null,
        "Trails" to setOf("trail"),
        "Trailheads" to setOf("trailhead"),
        "Campsites" to setOf("campsite", "shelter"),
        "Water" to setOf("spring", "drinking_water", "water_point", "well", "stream", "lake"),
        "Peaks" to setOf("peak", "saddle", "pass", "viewpoint"),
        "Waterfalls" to setOf("waterfall", "hot_spring"),
        "History" to setOf("lookout", "historic"),
        "Towns" to setOf("town"),
    )

    fun search(features: Features, query: String, refLat: Double, refLon: Double, kinds: Set<String>? = null): List<SearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+"))

        fun score(name: String): Int? {
            val n = name.lowercase()
            if (!tokens.all { n.contains(it) }) return null
            return when {
                n.startsWith(q) -> 0
                n.split(Regex("[^a-z0-9]+")).any { it.startsWith(tokens.first()) } -> 1
                else -> 2
            }
        }

        val hits = ArrayList<Pair<Int, SearchResult>>()
        features.allPoints.forEach { p ->
            if (kinds != null && p.kind !in kinds) return@forEach
            val name = p.name ?: return@forEach
            val s = score(name) ?: return@forEach
            hits += s to SearchResult(name, p.kind, p.lat, p.lon, Geo.distanceM(refLat, refLon, p.lat, p.lon), p)
        }
        if (kinds == null || "trail" in kinds) features.trailsByName.forEach { (name, parts) ->
            val s = score(name) ?: return@forEach
            var best = Double.MAX_VALUE
            var bestLat = 0.0
            var bestLon = 0.0
            for (t in parts) for (c in t.coords) {
                val d = Geo.distanceM(refLat, refLon, c[1], c[0])
                if (d < best) { best = d; bestLat = c[1]; bestLon = c[0] }
            }
            hits += s to SearchResult(name, "trail", bestLat, bestLon, best)
        }
        return hits.sortedWith(compareBy({ it.first }, { it.second.distanceM })).take(LIMIT)
            .map { it.second.withNear(features) }
    }

    /** Closest points of the given kinds, nearest first, within [maxM]. */
    fun nearest(features: Features, kinds: Set<String>, refLat: Double, refLon: Double, n: Int = 8, maxM: Double = 32_000.0): List<SearchResult> =
        features.allPoints.asSequence()
            .filter { it.kind in kinds }
            .map { p -> SearchResult(p.label, p.kind, p.lat, p.lon, Geo.distanceM(refLat, refLon, p.lat, p.lon), p) }
            .filter { it.distanceM <= maxM }
            .sortedBy { it.distanceM }
            .take(n)
            .map { it.withNear(features) }
            .toList()

    val WATER_KINDS = setOf("spring", "drinking_water", "water_point", "well", "stream")

    private fun SearchResult.withNear(f: Features) =
        copy(near = f.nearTown(lat, lon, exclude = if (kind == "town") name else null))
}
