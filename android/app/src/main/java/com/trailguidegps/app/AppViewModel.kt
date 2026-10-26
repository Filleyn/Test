package com.trailguidegps.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailguidegps.app.data.Features
import com.trailguidegps.app.data.MapStore
import com.trailguidegps.app.location.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Checking : DownloadState
    data class Downloading(val file: String, val done: Long, val total: Long) : DownloadState
    data class UpToDate(val tag: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = MapStore(app)
    val tracker = LocationTracker(app)

    private val _mapsReady = MutableStateFlow(store.isReady())
    val mapsReady: StateFlow<Boolean> = _mapsReady.asStateFlow()

    /** Bumped whenever new map files are installed so the map reloads its style. */
    private val _mapVersion = MutableStateFlow(0)
    val mapVersion: StateFlow<Int> = _mapVersion.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _features = MutableStateFlow<Features?>(null)
    val features: StateFlow<Features?> = _features.asStateFlow()

    init {
        loadFeatures()
    }

    private fun loadFeatures() {
        if (!store.isReady()) return
        viewModelScope.launch {
            _features.value = withContext(Dispatchers.IO) { runCatching { Features.load(store) }.getOrNull() }
        }
    }

    /** Download the newest map files, or report that the installed ones are current. */
    fun downloadMaps(force: Boolean = false) {
        if (_download.value is DownloadState.Checking || _download.value is DownloadState.Downloading) return
        viewModelScope.launch {
            _download.value = DownloadState.Checking
            try {
                val release = store.findLatestRelease()
                if (!force && store.isReady() && store.installedTag() == release.tag) {
                    _download.value = DownloadState.UpToDate(release.tag)
                    return@launch
                }
                store.download(release) { file, done, total ->
                    _download.value = DownloadState.Downloading(file, done, total)
                }
                _mapsReady.value = store.isReady()
                _mapVersion.value += 1
                loadFeatures()
                _download.value = DownloadState.UpToDate(release.tag)
            } catch (e: Exception) {
                _download.value = DownloadState.Error(friendly(e))
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection. Connect to Wi-Fi or data and try again."
        is java.net.SocketTimeoutException -> "The download timed out. Check your connection and try again."
        else -> e.message ?: "Something went wrong while downloading."
    }

    override fun onCleared() {
        tracker.stop()
    }
}
