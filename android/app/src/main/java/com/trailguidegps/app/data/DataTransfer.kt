package com.trailguidegps.app.data

import android.app.Application
import com.trailguidegps.app.geo.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Getting your data in and out of the app: backups, restore, and GPX import and export. Split
 * out of the view model, which passes in the stores, where to report ([message]), what to
 * refresh after a restore ([afterRestore]), the elevation data ([terrain]) and how to show
 * what was imported ([focusOn]).
 */
class DataTransfer(
    private val app: Application,
    private val scope: CoroutineScope,
    private val waypoints: WaypointStore,
    private val tracks: TrackStore,
    private val routes: RouteStore,
    private val waterNotes: WaterNotes,
    private val folders: FolderStore,
    private val trailNotes: TrailNoteStore,
    private val message: (String) -> Unit,
    private val afterRestore: () -> Unit,
    private val terrain: () -> Terrain?,
    private val focusOn: (List<Pair<Double, Double>>) -> Unit,
) {
    private val backupPrefs = app.getSharedPreferences("backup", android.content.Context.MODE_PRIVATE)
    private val _lastBackup = MutableStateFlow(backupPrefs.getLong("last", 0L).takeIf { it > 0 })
    /** When you last made a backup, or null. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    fun backupFileName() = "TrailGuide backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes the backup to a file you picked (this phone, Google Drive, OneDrive…). */
    fun backup(uri: android.net.Uri) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val trk = tracks.summaries.value.mapNotNull { tracks.load(it.id) }
                    val loaded = routes.summaries.value.mapNotNull { routes.load(it.id) }
                    val rte = loaded.map { it.name to it.coords }
                    val gpx = Gpx.write(waypoints.items.value, trk, rte, loaded.map { it.notes })
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.write(app, it, gpx) }
                }
            }
            r.onSuccess { c ->
                val now = System.currentTimeMillis()
                backupPrefs.edit().putLong("last", now).apply()
                _lastBackup.value = now
                message("Backed up ${c.routes} routes, ${c.tracks} tracks and ${c.waypoints} waypoints.")
            }.onFailure { message("Backup failed: ${it.message ?: "couldn't write the file"}") }
        }
    }

    /**
     * Makes a backup file and hands it to Android's share sheet, so apps that don't
     * appear in the file picker (OneDrive, email) can take it.
     */
    fun shareBackup(onReady: (android.net.Uri) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                    val f = java.io.File(dir, backupFileName())
                    val trk = tracks.summaries.value.mapNotNull { tracks.load(it.id) }
                    val loaded = routes.summaries.value.mapNotNull { routes.load(it.id) }
                    val rte = loaded.map { it.name to it.coords }
                    f.outputStream().use { Backup.write(app, it, Gpx.write(waypoints.items.value, trk, rte, loaded.map { it.notes })) }
                    androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                }
            }
            r.onSuccess { uri ->
                val now = System.currentTimeMillis()
                backupPrefs.edit().putLong("last", now).apply()
                _lastBackup.value = now
                onReady(uri)
            }.onFailure { message("Backup failed: ${it.message ?: "couldn't write the file"}") }
        }
    }

    /**
     * One route with its camps, notes, dates and checklist as a small backup file, for another
     * phone with the app ("Restore from a backup" there adds it).
     */
    fun shareRoute(id: String, name: String, onReady: (android.net.Uri) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "Route" }
                    val f = java.io.File(dir, "TrailGuide route - $safe.zip")
                    f.outputStream().use { Backup.writeRoute(app, it, id) }
                    androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                }
            }
            r.onSuccess(onReady).onFailure { message("Couldn't share the route: ${it.message ?: "couldn't write the file"}") }
        }
    }

    /** Restores a backup file, adding to what's already on the phone. */
    fun restore(uri: android.net.Uri) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use { Backup.restore(app, it) } }
            }
            r.onSuccess { c ->
                waypoints.reload(); tracks.reload(); routes.reload(); waterNotes.reload(); folders.reload(); trailNotes.reload()
                afterRestore()
                message("Restored ${c.routes} routes, ${c.tracks} tracks, ${c.waypoints} waypoints and ${c.notes} notes.")
            }.onFailure { message(it.message ?: "That file couldn't be restored.") }
        }
    }

    fun importGpx(uri: android.net.Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { Gpx.read(it) }
                }
            }
            result.onSuccess { imp ->
                withContext(Dispatchers.IO) {
                    waypoints.addAll(imp.waypoints)
                    imp.tracks.forEach { (n, pts) -> tracks.save(n, pts) }
                    imp.routes.forEachIndexed { i, (n, pts) ->
                        val coords = pts.map { doubleArrayOf(it.lat, it.lon) }
                        val dist = coords.zipWithNext { a, b -> Geo.distanceM(a[0], a[1], b[0], b[1]) }.sum()
                        routes.save(SavedRoute(
                            RouteStore.newId(), n, System.currentTimeMillis(),
                            listOfNotNull(pts.firstOrNull(), pts.lastOrNull()).map { RoutePoint(it.lat, it.lon, true) },
                            coords, RouteStats.compute(coords, dist, terrain()), emptyList(),
                            notes = imp.routeNotes.getOrNull(i).orEmpty(),
                        ))
                    }
                }
                message("Imported ${imp.waypoints.size} waypoints, ${imp.routes.size} routes and ${imp.tracks.size} tracks.")
                val pts = imp.waypoints.map { it.lat to it.lon } + imp.tracks.flatMap { t -> t.second.map { it.lat to it.lon } } +
                    imp.routes.flatMap { r -> r.second.map { it.lat to it.lon } }
                focusOn(pts)
            }.onFailure {
                message("That file couldn't be read as GPX.")
            }
        }
    }

    /** Writes a GPX file to the app's share folder and returns a link other apps can open. */
    fun exportGpx(fileName: String, wps0: List<Waypoint>, trackIds: List<String>, routeIds: List<String> = emptyList(),
                  noteIds: List<String> = emptyList()): android.net.Uri? {
        // Trail notes go out as waypoints, so other apps and GPS units can show them.
        val wps = wps0 + noteIds.mapNotNull { trailNotes.get(it) }.map { n ->
            Waypoint(id = n.id, name = n.title, lat = n.lat, lon = n.lon, note = n.text, createdAt = n.createdAt)
        }
        return runCatching {
            val trk = trackIds.mapNotNull { tracks.load(it) }
            val loaded = routeIds.mapNotNull { routes.load(it) }
            val rte = loaded.map { it.name to it.coords }
            val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
            val safe = fileName.replace(Regex("[^A-Za-z0-9 _.-]"), "").ifBlank { "TrailGuide" }
            val file = java.io.File(dir, "$safe.gpx")
            file.writeText(Gpx.write(wps, trk, rte, loaded.map { it.notes }))
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }.getOrNull()
    }
}
