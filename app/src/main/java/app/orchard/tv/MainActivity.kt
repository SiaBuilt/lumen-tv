package app.orchard.tv

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.ui.home.HomeScreen
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.system.MissingClipboard
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.system.settingsLaunchIntent
import app.orchard.tv.update.Updates
import app.orchard.tv.weather.Weather
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshSystem()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            vm.refreshNetwork()
            Wallpapers.refreshDailyIfStale(networkCameUp = true)
            Weather.refreshIfStale(networkCameUp = true)
            Updates.checkIfStale()
        }
        override fun onLost(network: Network) = runOnUiThread { vm.refreshNetwork() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = runOnUiThread { vm.refreshNetwork() }
    }

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        TileArtFactory.useCanvasDensity(canvasDensity(resources.displayMetrics))
        Wallpapers.init(this)
        Weather.init(this)
        Updates.init(this)
        CustomIcons.init(this)
        AerialSettings.init(this)
        Sounds.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        // The home screen paints every pixel itself; a window background would be one more
        // full-screen fill per frame for the TV's GPU.
        window.setBackgroundDrawable(null)
        preferSmoothestRefreshRate()

        setContent {
            // TVs ship at different densities for the same 1080p panel (Xiaomi 320dpi, TCL 240dpi),
            // which would make the same layout bigger on one and smaller on the other. Aerial is
            // designed on a 960×540 canvas, so it scales that canvas to whatever the panel is.
            val canvas = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvas) {
                HomeScreen(
                    vm = vm,
                    onLaunch = ::launch,
                    onOpen = ::open,
                    onAppInfo = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onUninstall = { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    // Posted so the key-up of the Back press that started it does not end it at once.
                    onStartScreensaver = { idleHandler.postDelayed({ showScreensaver.run() }, 150) },
                    onResume = ::resume,
                )
            }
        }

        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null && firstStartSinceBoot()) openBootSource("boot")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }

    // ---- The input the TV starts on (issue #25) ----

    private var bootSourceOpenedAt = Long.MIN_VALUE / 2

    /**
     * Woken with Aerial still in front (TCL keeps it through standby). Where the firmware killed
     * Aerial instead (Xiaomi), Home Guard brings it back with [EXTRA_WOKE].
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            idleHandler.postDelayed({
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openBootSource("screen on")
            }, 1500)
        }
    }

    /** Once per boot (Settings.Global.BOOT_COUNT, Android 7+), within a few minutes of it. */
    private fun firstStartSinceBoot(): Boolean {
        if (SystemClock.elapsedRealtime() > 3 * 60 * 1000L) return false
        if (Build.VERSION.SDK_INT < 24) return true
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = getSharedPreferences("system", MODE_PRIVATE)
        if (boot < 0 || prefs.getInt("boot_source_boot", -2) == boot) return false
        prefs.edit().putInt("boot_source_boot", boot).apply()
        return true
    }

    /**
     * Opens the input the owner wants the TV to start on, if any. The list of inputs loads with
     * the apps, so wait for it a moment. Several routes may fire for one wake; the first wins.
     */
    private fun openBootSource(reason: String) {
        val id = AerialSettings.bootSource ?: return
        if (SystemClock.elapsedRealtime() - bootSourceOpenedAt < 15_000) return
        bootSourceOpenedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            // Inputs can take a while to list right after a wake (Xiaomi MiTV-MTEQ0, issue #111).
            val source = withTimeoutOrNull(30_000) {
                snapshotFlow { vm.sources.firstOrNull { it.id == id } }.filterNotNull().first()
            } ?: run {
                android.util.Log.w("Aerial", "$reason: input $id not listed; not opening it")
                return@launch
            }
            android.util.Log.i("Aerial", "$reason: opening ${source.label}, the input the TV starts on")
            openedAppSinceHome = true
            open(source.intent)
        }
    }

    /**
     * Runs Home at the fastest refresh rate the panel can switch to without going black, so focus
     * and scrolling move as smoothly as the TV allows (a 120 Hz TV that idles its UI at 60 Hz).
     * Only seamless switches, which Android 12 can name: on a TV a mode change is otherwise a second
     * of black screen, every time Home comes back. Xiaomi MiTV-MFTR0 and MFFU1 have a single 60 Hz
     * mode; TCL MT5879 (Android 11) lists 50 to 120 Hz but cannot say which switches are seamless,
     * so both stay as they are.
     */
    private fun preferSmoothestRefreshRate() {
        if (Build.VERSION.SDK_INT < 31) return
        val display = display ?: return
        val current = display.mode
        val seamless = current.alternativeRefreshRates
        val best = display.supportedModes
            .filter { m ->
                m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight &&
                    m.refreshRate <= 120.5f && seamless.any { kotlin.math.abs(it - m.refreshRate) < 0.5f }
            }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate + 0.5f } ?: return
        android.util.Log.i("Aerial", "refresh rate ${current.refreshRate} -> ${best.refreshRate} Hz (mode ${best.modeId})")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    // ---- Screensaver: Aerial's own, before the firmware's ad-carrying one can start ----

    private val idleHandler = Handler(Looper.getMainLooper())
    private val showScreensaver = Runnable {
        vm.screensaverVisible = true
        // Nobody is watching: a good moment for an update, which needs no one.
        app.orchard.tv.update.Updates.installWhenIdle(this)
        // Holding the screen on keeps the firmware's screensaver from starting over ours. After a
        // long stretch, let go so the TV's own power policy can still turn the panel off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        idleHandler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var swallowKeyUp = -1
    private var swallowTouch = false

    /** An app (or the stock launcher) was opened from the grid and Home has not been pressed since. */
    private var openedAppSinceHome = false

    private fun restartIdleTimer() {
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.postDelayed(showScreensaver, idleBeforeScreensaver())
    }

    /**
     * Aerial's screensaver has to start before the firmware's (ad-carrying) one. Xiaomi keeps its
     * delay in system/screen_saver_timeout: 120000 on some MiTV-MFTR0 units, 480000 on others, -1
     * when off. Start 15 s ahead of it when it is shorter than the delay chosen in Personalize.
     */
    private fun idleBeforeScreensaver(): Long {
        val chosen = AerialSettings.screensaverMinutes * 60_000L
        val firmware = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()
        return if (firmware != null && firmware > 0) minOf(chosen, maxOf(30_000L, firmware - 15_000L))
        else chosen
    }

    private fun hideScreensaver() {
        vm.screensaverVisible = false
        idleHandler.removeCallbacks(releaseScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdleTimer()
        // The key that wakes the screensaver only wakes it, silently.
        if (!vm.screensaverVisible && event.keyCode != swallowKeyUp) Sounds.onKey(event)
        // Left / right step through the films or photos and keep the screensaver up.
        if (vm.screensaverVisible && vm.screensaverSkips.onKey(event)) return true
        if (vm.screensaverVisible && event.action == KeyEvent.ACTION_DOWN) {
            // The key that wakes the screen only wakes it; it does not also move focus or open an app.
            hideScreensaver()
            swallowKeyUp = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
            swallowKeyUp = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        restartIdleTimer()
        // As with keys: the touch that ends the screensaver only ends it, through to the lift.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowTouch = vm.screensaverVisible
            if (swallowTouch) hideScreensaver()
        }
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        if (intent.action == ACTION_PREVIEW_SCREENSAVER) {
            idleHandler.post { showScreensaver.run() }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            // Since 1.11 Home Guard answers Home from an app by bringing Aerial back with this same
            // intent, so the intent alone cannot tell Home-on-Home from coming back from an app.
            hideScreensaver()
            vm.onHomePressed(resetFocus = !openedAppSinceHome)
            openedAppSinceHome = false
        }
    }

    override fun onStart() {
        super.onStart()
        GuardSetup.ensureEnabled(this)
        idleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        vm.reloadIfPackagesChanged()
        vm.refreshHistory()
        // And once more shortly after, for apps that write their history only as they stop.
        idleHandler.postDelayed({ vm.refreshHistory() }, 3_000)
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        getSystemService<ConnectivityManager>()?.let { cm ->
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback)
            else cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        }
        // Apps can change while Aerial is in the background without it hearing about it.
        vm.reload()
        Wallpapers.refreshDailyIfStale()
        Wallpapers.rotateIfDue()
        Weather.refreshIfStale()
        Updates.checkIfStale()
    }

    override fun onResume() {
        super.onResume()
        restartIdleTimer()
        GuardSetup.setHomeInFront(this, true)
        HomeGuardService.onAerialResumed()
        vm.refreshSystem()
        // Back in front by any route (Home was handled in onNewIntent, which runs first; Back from
        // the app lands here directly), so the next Home press is a Home-on-Home again.
        openedAppSinceHome = false
    }

    override fun onPause() {
        super.onPause()
        GuardSetup.setHomeInFront(this, false)
        idleHandler.removeCallbacks(showScreensaver)
        hideScreensaver()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(volumeReceiver)
        runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback) }
    }

    /** Opens an app, growing it out of its tile. */
    private fun launch(app: AppEntry, bounds: Rect?) {
        // The TV's settings app behind the parental PIN as well, not only Control Center's button.
        if (AerialSettings.locked && SystemProbe.isSettingsApp(this, app.packageName)) {
            vm.whenUnlocked { launch(app, bounds) }
            return
        }
        openedAppSinceHome = true
        // The stock launcher (Xiaomi's PatchWall, with the maker's own iQIYI / Mango content) can
        // only be opened through the system Home action; see HomeGuardService.openStockLauncher.
        // Hisense VIDAA hands the Home action to Aerial once persist.vt.use_home_pkg points at it,
        // so there it went nowhere; its launcher opens like any app (issue #87). Xiaomi's throws.
        if (HomeGuardService.isStockHome(app.packageName)) {
            HomeGuardService.allowStockLauncher()
            if (runCatching { startActivity(app.launchIntent()) }.isSuccess || HomeGuardService.openStockLauncher()) return
        }
        val options = bounds?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(),
            ).toBundle()
        }
        try {
            startActivity(settingsLaunchIntent(this, app.launchIntent()), options)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    /** Resumes a title from the TV's watch history; if its app will not take it, opens the app. */
    private fun resume(item: app.orchard.tv.history.HistoryItem) {
        openedAppSinceHome = true
        val resumed = runCatching { startActivity(item.resumeIntent()) }
            .onFailure { android.util.Log.w("LumenHistory", "Resume failed for ${item.packageName}: $it") }
            .isSuccess
        if (resumed) return
        vm.apps.firstOrNull { it.packageName == item.packageName }?.let { launch(it, null) }
            ?: Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(settingsLaunchIntent(this, intent))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** How Home Guard and the boot receiver bring Aerial back: the same task the launcher entry uses. */
        fun bringBackIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L

        /** `adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity` */
        const val ACTION_PREVIEW_SCREENSAVER = "app.orchard.tv.PREVIEW_SCREENSAVER"

        /** Set by Home Guard when it brings Aerial back after standby or a boot. */
        const val EXTRA_WOKE = "app.orchard.tv.WOKE"
    }
}
