package app.orchard.tv

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.LayoutStore
import app.orchard.tv.apps.allAppsEntry
import app.orchard.tv.apps.sourceEntry
import app.orchard.tv.history.HistoryItem
import app.orchard.tv.history.TclHistory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Brightness
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.system.TvSource
import app.orchard.tv.system.VolumeStatus
import app.orchard.tv.ui.home.PinMode
import app.orchard.tv.ui.home.ScreensaverSkips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Overlay {
    data object None : Overlay
    data class ControlCenter(val page: CcPage = CcPage.Main) : Overlay
    data class AppMenu(val key: String, val anchor: Rect) : Overlay
    data object HiddenApps : Overlay
    data class AppOrder(val initialKey: String? = null) : Overlay
    /** Every app past the dock, behind the dock's last tile. */
    data object AllApps : Overlay
    data object Wallpaper : Overlay
    data class Pin(val mode: PinMode, val onDone: () -> Unit) : Overlay
}

/** The streaming apps Lumen is made for, docked even when they came with the TV (TCL 65V8L). */
val DOCK_APPS = setOf("com.xiaodianshi.tv.yst", "com.ktcp.csvideo", "com.tcl.qiyiguo", "com.cibn.tv", "com.hunantv.market")
const val HOME_COLUMNS = 6

/** Covers are decoded at 720p: sharp enough under the shade, small enough for a 2 GB TV. */
const val COVER_W = 1280
const val COVER_H = 720

enum class CcPage { Main, Inputs, InputSettings, City }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val store = LayoutStore(app)
    private var all: List<AppEntry> = emptyList()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var hiddenApps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set

    private val dockSize: Int get() = AerialSettings.homeRows * HOME_COLUMNS - 1
    /** Keep the owner's fixed rows unless they opt into the upstream scrolling dock. */
    val dock: List<AppEntry> get() = (if (AerialSettings.scrollingHome) apps.filter(::inDock) else apps.take(dockSize)) + allApps
    /** The complement of Home, preserving the same stored app order in either layout. */
    val moreApps: List<AppEntry> get() = if (AerialSettings.scrollingHome) apps.filterNot(::inDock) else apps.drop(dockSize)
    private fun inDock(app: AppEntry) = app.removable || app.packageName in DOCK_APPS || app.source != null
    private val allApps by lazy { allAppsEntry(context) }

    /** What the owner was watching, newest first (TCL's launcher history); empty elsewhere. */
    var history by mutableStateOf<List<HistoryItem>>(emptyList()); private set
    var heroIndex by mutableStateOf(0)

    var overlay by mutableStateOf<Overlay>(Overlay.None)
    var movingKey by mutableStateOf<String?>(null); private set

    var network by mutableStateOf(NetworkStatus()); private set
    var volume by mutableStateOf(VolumeStatus()); private set
    var sources by mutableStateOf<List<TvSource>>(emptyList()); private set
    var homeStatus by mutableStateOf(HomeStatus.NotDefault); private set

    /** Panel brightness 0..1, or null when Aerial cannot change it on this TV. */
    var brightness by mutableStateOf<Float?>(null); private set

    /**
     * Set when Home is pressed: go back to the top row. Done once the window has focus again,
     * since a focus request made while another app is in front is lost.
     */
    var homeResetPending by mutableStateOf(false)

    /** Aerial's screensaver is up (see MainActivity's idle timer). */
    var screensaverVisible by mutableStateOf(false)
    val screensaverSkips = ScreensaverSkips()

    private var loadJob: Job? = null

    /**
     * Video apps write to TCL's history when playback stops, which can land just after Home is
     * back in front: a read on resume alone missed the title just watched. Watch the table too.
     */
    private val historyObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshHistory(debounceMs = 600)
    }

    init {
        runCatching { app.contentResolver.registerContentObserver(TclHistory.URI, true, historyObserver) }
        reload()
        // A tile picture set or removed from the phone page.
        viewModelScope.launch { snapshotFlow { CustomIcons.version }.drop(1).collect { reload() } }
    }

    /** PackageManager's change counter when the list was last loaded (Android 8+). */
    private var packagesSeen = 0

    /**
     * Apps installed or removed while Aerial was in the background (from an app store in front)
     * send no broadcast Aerial hears: it listens only while started. Catch up when Home comes back
     * (issue #55). Before Android 8 there is no change counter; the reload is cheap, tiles are cached.
     */
    fun reloadIfPackagesChanged() {
        if (!loaded) return
        if (Build.VERSION.SDK_INT >= 26 && context.packageManager.getChangedPackages(packagesSeen) == null) return
        reload()
    }

    fun reload() {
        if (Build.VERSION.SDK_INT >= 26) {
            packagesSeen = context.packageManager.getChangedPackages(0)?.sequenceNumber ?: packagesSeen
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = context
            all = AppCatalog.load(ctx)
            sources = withContext(Dispatchers.Default) { SystemProbe.sources(ctx) }
            arrange()
            loaded = true
        }
        refreshHistory()
    }

    private var historyJob: Job? = null

    fun refreshHistory(debounceMs: Long = 0) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            val list = TclHistory.load(context)
            if (list.map { it.id } != history.map { it.id }) heroIndex = 0
            history = list
            // Fetch every cover now, the first one first, so paging through never waits on the network.
            val urls = list.mapNotNull { it.coverUrl }
            TclHistory.keepOnly(urls.toSet())
            urls.forEach { TclHistory.cover(it, COVER_W, COVER_H) }
        }
    }

    private fun arrange() {
        // Pinned TV inputs share the grid, order and hiding with the apps.
        val pinned = sources.filter { it.id in AerialSettings.pinnedSources }.map(::sourceEntry)
        val (visible, hidden) = store.arrange(all + pinned)
        apps = visible
        hiddenApps = hidden
    }

    override fun onCleared() {
        runCatching { context.contentResolver.unregisterContentObserver(historyObserver) }
    }

    /** Drops the picture chosen on the phone page; the tile goes back to the app's own artwork. */
    fun resetIcon(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CustomIcons.clear(key) }
            CustomIcons.changed()
        }
    }

    /** Pins a TV input to Home (as the first tile, where it is easiest to reach) or takes it off. */
    fun setSourcePinned(source: TvSource, pinned: Boolean) {
        AerialSettings.setPinned(source.id, pinned)
        arrange()
        if (pinned) {
            val key = apps.firstOrNull { it.source?.id == source.id }?.key
            val first = apps.firstOrNull()?.key
            if (key != null && first != null && key != first) {
                store.move(key, first)
                arrange()
            }
        }
    }

    fun refreshSystem() {
        viewModelScope.launch {
            val ctx = context
            val (n, v, s) = withContext(Dispatchers.Default) {
                Triple(SystemProbe.network(ctx), SystemProbe.volume(ctx), SystemProbe.sources(ctx))
            }
            network = n; volume = v
            // TvSource holds an Intent, which has no value equality; compare what a tile shows.
            fun shown(list: List<TvSource>) = list.map { Triple(it.id, it.label, it.deviceName) }
            if (shown(s) != shown(sources)) { sources = s; arrange() } else sources = s
            homeStatus = SystemProbe.homeStatus(ctx)
            brightness = Brightness.level(ctx)
        }
    }

    fun refreshNetwork() { network = SystemProbe.network(context) }

    fun adjustVolume(direction: Int) {
        SystemProbe.adjustVolume(context, direction)
        volume = SystemProbe.volume(context)
    }

    fun adjustBrightness(direction: Int) {
        brightness = Brightness.adjust(context, direction)
    }

    fun toggleMute() {
        SystemProbe.toggleMute(context)
        volume = SystemProbe.volume(context)
    }

    /** Runs [action] at once, or after the parental PIN when one is set (issue #10). */
    fun whenUnlocked(action: () -> Unit) {
        if (!AerialSettings.locked) action()
        else overlay = Overlay.Pin(PinMode.Unlock) { overlay = Overlay.None; action() }
    }

    fun hide(key: String) {
        store.hide(key)
        arrange()
    }

    fun unhide(key: String) {
        store.unhide(key)
        arrange()
    }

    fun startMove(key: String) {
        overlay = Overlay.None
        movingKey = key
    }

    /** Moves the app being moved by [delta] places along the dock (All Apps stays last). */
    fun moveBy(delta: Int) {
        val key = movingKey ?: return
        val row = if (AerialSettings.scrollingHome) apps.filter(::inDock) else apps
        val from = row.indexOfFirst { it.key == key }
        if (from < 0) return
        // Fixed-row apps stay on their side of All Apps; the scrolling dock has no fixed limit.
        val first = if (AerialSettings.scrollingHome || from < dockSize) 0 else dockSize
        val last = if (AerialSettings.scrollingHome || from >= dockSize) row.lastIndex else minOf(dockSize, row.size) - 1
        val to = (from + delta).coerceIn(first, last)
        if (to == from) return
        store.move(key, row[to].key)
        arrange()
    }

    fun endMove() { movingKey = null }

    fun applyAppOrder(keys: List<String>) {
        val visible = apps.map { it.key }
        store.reorderVisible(keys.filter { it in visible } + visible.filter { it !in keys })
        arrange()
    }

    /**
     * Home was pressed. On Home itself focus goes back to the top row, as on tvOS; coming back
     * from an app opened from the grid, focus stays on that app's tile.
     */
    fun onHomePressed(resetFocus: Boolean = true) {
        screensaverVisible = false
        AerialSettings.relock()
        overlay = Overlay.None
        movingKey = null
        if (resetFocus) {
            homeResetPending = true
            heroIndex = 0
        }
    }
}
