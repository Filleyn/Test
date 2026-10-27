package com.trailguidegps.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Current wildfires: perimeters and fire locations as GeoJSON, and when they were fetched. */
data class FireData(val perimeters: String, val points: String, val fetchedAt: Long, val count: Int)

/**
 * Current wildfires from the national fire agencies' live data (WFIGS, run by NIFC).
 * Needs a connection to update; the last copy is kept so it still shows offline,
 * marked with when it was fetched.
 */
class FireStore(context: Context) {
    private val dir = File(context.filesDir, "fires").apply { mkdirs() }
    private val _data = MutableStateFlow(loadCached())
    val data: StateFlow<FireData?> = _data.asStateFlow()

    private fun loadCached(): FireData? = runCatching {
        val meta = JSONObject(File(dir, "meta.json").readText())
        FireData(File(dir, "perimeters.json").readText(), File(dir, "points.json").readText(),
            meta.getLong("fetchedAt"), meta.getInt("count"))
    }.getOrNull()

    /** True if the data is missing or older than [maxAgeMs]. */
    fun stale(maxAgeMs: Long = 30 * 60_000L) = _data.value?.let { System.currentTimeMillis() - it.fetchedAt > maxAgeMs } ?: true

    /** Downloads the current fires around Oregon. Throws if offline. */
    suspend fun refresh(bbox: List<Double> = listOf(-125.5, 41.0, -115.5, 47.0)) = withContext(Dispatchers.IO) {
        val env = bbox.joinToString(",")
        fun query(service: String, where: String, fields: String, simplify: Boolean): String {
            val params = linkedMapOf(
                "where" to where, "geometry" to env, "geometryType" to "esriGeometryEnvelope", "inSR" to "4326",
                "spatialRel" to "esriSpatialRelIntersects", "outFields" to fields, "outSR" to "4326", "f" to "geojson",
            )
            if (simplify) params["maxAllowableOffset"] = "0.0003"
            val q = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val url = URL("$BASE/$service/FeatureServer/0/query?$q")
            val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            try {
                if (c.responseCode != 200) error("Fire data returned ${c.responseCode}")
                val text = c.inputStream.bufferedReader().readText()
                val o = JSONObject(text)
                if (o.has("error")) error("Fire data error: ${o.optJSONObject("error")?.optString("message")}")
                return text
            } finally { c.disconnect() }
        }
        val perims = query("WFIGS_Interagency_Perimeters_Current", "attr_IncidentTypeCategory = 'WF'",
            "poly_IncidentName,poly_GISAcres,attr_PercentContained,attr_ModifiedOnDateTime_dt,attr_FireDiscoveryDateTime", true)
        val points = query("WFIGS_Incident_Locations_Current", "IncidentTypeCategory = 'WF'",
            "IncidentName,IncidentSize,PercentContained,FireDiscoveryDateTime,ModifiedOnDateTime_dt", false)
        val count = JSONObject(points).optJSONArray("features")?.length() ?: 0
        val now = System.currentTimeMillis()
        File(dir, "perimeters.json").writeText(perims)
        File(dir, "points.json").writeText(points)
        File(dir, "meta.json").writeText(JSONObject().put("fetchedAt", now).put("count", count).toString())
        _data.value = FireData(perims, points, now, count)
    }

    companion object {
        private const val BASE = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services"
        const val INCIWEB_OREGON = "https://inciweb.wildfire.gov/state/oregon"
        val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    }
}
