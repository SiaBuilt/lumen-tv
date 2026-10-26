package app.orchard.tv.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.settings.AerialSettings

/**
 * Keeps Aerial as the home screen on firmwares that will not let a third-party app own HOME
 * (Xiaomi PatchWall, some TCL and Hisense builds), without disabling any system app:
 *
 * 1. When the stock launcher's home activity comes to the front (the Home key, an app exiting to
 *    it, the TV booting into it), Aerial is brought back on top.
 * 2. The viewer can still open the stock launcher on purpose (its tile in Aerial's grid, for the
 *    maker's own content). While it is in front the Home key is taken before the system sees it
 *    and answered with Aerial, since pressing Home there would otherwise just stay put.
 *
 * Key filtering is on only in that second case. With it on, every key press on the TV detours
 * through this service; some apps then see a press as held and move twice (云视听小电视 on Xiaomi
 * MiTV-MFTR0, reported by a user and gone once Aerial was uninstalled).
 *
 * Both only act while Aerial is not the resolved HOME app; once it is, the system does the work.
 * The service reads window-change events only; it never reads screen content.
 */
class HomeGuardService : AccessibilityService() {

    /** HOME activities that are not ours: package to class names. Refreshed on every connect. */
    private var stockHomes: Map<String, Set<String>> = emptyMap()

    /** Set when the user opens the stock launcher on purpose from Aerial's grid. */
    private var allowStockUntil = 0L

    /** The last window that came to the front, as package to class; null until one does. */
    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())

    /** When the screen last lit, for take-overs that are really a wake (issue #111). */
    private var screenOnAt = Long.MIN_VALUE / 2

    private var takeOverReason = ""
    private var takeOverOnWake = false
    private val takeOver = Runnable { takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake) }

    /**
     * Bound before unlock (Direct Boot), the guard takes over as soon as credential storage is
     * available, from a process that is already running: on TCL 75S365C (Amlogic t982) starting a
     * process right after unlock waits several seconds behind TCL's boot-time scheduling (PR #45:
     * unlock to Aerial 7.6 s -> 0.9 s). It looks at what is in front first, like every take-over, so
     * an input that HDMI-CEC switched to at power-on is left alone.
     */
    private val userUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Before unlock only Direct Boot aware launchers resolve.
            refreshStockHomes()
            // The ADB and screensaver checks were skipped while locked.
            GuardSetup.ensureEnabled(this@HomeGuardService)
            val skipReason = when {
                SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS -> "outside boot window"
                getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
                else -> null
            }
            if (skipReason == null) takeOverOnceSettled("user unlocked", woke = true)
            else Log.i(TAG, "user unlocked; not taking over Home ($skipReason)")
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            screenOnAt = SystemClock.elapsedRealtime()
            takeOverOnceSettled("screen on", woke = true)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.ensureAdb(this@HomeGuardService)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Register before querying homes or checking the state so an unlock cannot be missed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        refreshStockHomes()
        filterKeys(false)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        GuardSetup.ensureAdb(this)
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            return
        }
        // Bound a little after boot, or brought back after the firmware killed Aerial: either way
        // the stock launcher is what's in front, and the window change that showed it is long gone.
        // Not after an update, though: installing over Aerial also kills it, and whoever is watching
        // an input or another app at that moment must not be pulled back to Home.
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        // Aerial died with its home screen in front (it crashed, was updated, or was killed) and the
        // stock launcher was uncovered; pressing Home there changes nothing the guard could see
        // (TCL MT5879, after the screensaver crash of 1.14). Updates count too: Aerial was in front.
        val diedInFront = GuardSetup.wasHomeInFront(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000)
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        // Not gated on being the default Home: Skyworth/Coolita (8R145, Android 11) starts its own
        // launcher at boot whatever Home resolves to (issue #43). The take-over looks first, and
        // bringing Aerial back over itself changes nothing.
        if ((SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after Aerial died in front"
                    else -> "bound shortly after boot"
                },
                // Back from standby or a boot, not from a crash: the moment to open the input
                // the owner wants the TV to start on.
                woke = !diedInFront || revived,
            )
        }
    }

    /**
     * Takes Home back after a short wait, and only if nothing but the stock launcher came to the
     * front meanwhile. At power-on an HDMI-CEC source (an Apple TV) switches the TV to its input
     * just as the screen lights and the guard is bound again; taking Home at once bounced the
     * viewer straight back out of the input (reported by a user, reproduced on Xiaomi MiTV-MFTR0:
     * the input's ExternalSourceActivity started 130 ms before the guard was bound).
     */
    private fun takeOverOnceSettled(reason: String, woke: Boolean) {
        takeOverReason = reason
        takeOverOnWake = woke
        handler.removeCallbacks(takeOver)
        handler.postDelayed(takeOver, SETTLE_MS)
    }

    private fun hasBootSource(): Boolean = runCatching {
        AerialSettings.init(this)
        AerialSettings.bootSource != null
    }.getOrDefault(false)

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when Aerial is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops Aerial, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean) {
        if (!GuardSetup.isUserUnlocked(this)) return
        val last = lastWindow
        // Unknown means we were only just bound, which happens after a standby kill. Any screen of
        // the stock launcher counts: when Aerial is killed, whatever was on top of the launcher's
        // task is uncovered, not always its home activity (PatchWall's StrategicLayerActivity).
        // Unless the firmware keeps stopping Aerial: then an unknown front is most likely the app
        // the viewer is in, and Home stays with the Home key (LeTV eUI, issue #63).
        if (last == null && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but Aerial keeps being stopped and the front is unknown; leaving it")
            return
        }
        val stockInFront = last == null || last.first in stockHomes
        // Aerial is in front after a wake because the guard already brought it back when the stock
        // launcher showed, or because it survived standby; either way the input the TV starts on
        // still has to open. Only with one chosen: bringing Aerial back also resets its focus.
        // Xiaomi MiTV-MTEQ0, where the guard is revived after the screen lights (issue #111).
        if (woke && last?.first == packageName && hasBootSource()) {
            Log.i(TAG, "$reason with Aerial itself in front; flagging the wake")
            goHome(woke = true)
            return
        }
        if (!stockInFront && last != null) Log.i(TAG, "$reason with ${last.first} in front; leaving it there")
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        goHome(woke)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /**
     * Filters keys only while the viewer is in the stock launcher on purpose; see the class comment.
     * Up to Android 11 always: there, once Home reaches the system, it holds every background
     * activity start for five seconds, so Aerial came back only after five seconds of the TCL
     * launcher (TCL MT5879, since 1.11 stopped filtering by default).
     */
    private fun filterKeys(on: Boolean) {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val want = if (!keysPassThrough && (on || Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    /**
     * Hisense VIDAA handles volume in its own firmware, which never sees keys that went through an
     * accessibility filter: with the filter on, the volume keys did nothing anywhere, though this
     * service passes them on (VIDAA_TV, RTD2851D, Android 10, issue #7). Home is no help there
     * either: VIDAA starts its launcher by component, or Aerial through persist.vt.use_home_pkg.
     */
    private val keysPassThrough by lazy {
        VIDAA_PACKAGES.any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardSetup.isUserUnlocked(this)) return false
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        // Home over the screensaver: end it and go Home, whoever is the system Home.
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && app.orchard.tv.dream.AerialDream.wake()) {
            Log.i(TAG, "Home ended the screensaver")
            goHome()
            return true
        }
        if (isDefaultHome()) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.i(TAG, "Home key taken before the system saw it")
            goHome()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 多屏互动's Agree prompt is a floating window: it shows up as a windows change.
        if ((event.packageName == TCAST || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
            System.currentTimeMillis() < app.orchard.tv.update.Updates.confirmUntil(this)
        ) {
            listOf(0L, 700L, 1600L).forEach { confirmHandler.postDelayed({ agreeToOwnPush() }, it) }
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == INSTALLER && System.currentTimeMillis() < app.orchard.tv.update.Updates.confirmUntil(this)) {
            // The button can be disabled for a moment as the installer appears: try again shortly.
            listOf(0L, 700L, 1600L).forEach { confirmHandler.postDelayed({ confirmOwnUpdate() }, it) }
        }
        val cls = event.className?.toString() ?: return
        // Only activities count as what is in front. Overlays and dialogs also raise this event:
        // TCL's voice assistant (com.tcl.walleve) does at boot on TCL 75S365C, and taking it for
        // an app the viewer chose kept the guard from taking over (PR #45).
        if (isActivity(pkg, cls)) lastWindow = pkg to cls
        if (!GuardSetup.isUserUnlocked(this)) return
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            filterKeys(allowed)
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            if (cls !in stockHomes.getValue(pkg) || allowed) return
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            // Shortly after the screen lit, this is the wake itself (issue #111).
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
            return
        }
        filterKeys(false)
        // Leaving the stock launcher for a real app ends the visit: Home goes to Aerial again.
        if (allowed && pkg != packageName && isApp(pkg)) allowStockUntil = 0L
    }

    /**
     * Presses Update in the system installer, only for Lumen's own update started from Control
     * Center a moment ago (Updates.confirmSelfUntil), and only when the screen names Lumen.
     */
    private val confirmHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Presses Agree on 多屏互动's push prompt, only while Lumen's own push is under way. */
    private fun agreeToOwnPush() {
        if (System.currentTimeMillis() >= app.orchard.tv.update.Updates.confirmUntil(this)) return
        val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
        val agree = roots.asSequence()
            .flatMap { it.findAccessibilityNodeInfosByViewId("$TCAST:id/btn_positive").orEmpty().asSequence() }
            .firstOrNull { it.isEnabled } ?: return
        if (agree.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "pressed Agree on 多屏互动 for Lumen's own update")
            app.orchard.tv.update.Updates.stopConfirming(this)
        }
    }

    private fun confirmOwnUpdate() {
        if (System.currentTimeMillis() >= app.orchard.tv.update.Updates.confirmUntil(this)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != INSTALLER) return
        val label = applicationInfo.loadLabel(packageManager).toString()
        if (root.findAccessibilityNodeInfosByText(label).isNullOrEmpty()) return
        fun firstButton(words: List<String>) = words.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty().asSequence() }
            .map { n -> if (n.isClickable) n else n.parent ?: n }
            .firstOrNull { it.isClickable && it.isEnabled }
        // Installed: TCL then asks "安装已完成，是否立即体验?"; its 立即体验 is disabled while Lumen
        // restarts. Close it, and Home Guard brings Lumen back.
        val done = DONE_HINTS.any { !root.findAccessibilityNodeInfosByText(it).isNullOrEmpty() }
        if (done) {
            val close = firstButton(DONE_WORDS) ?: return
            if (close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "closed the installer's done dialog for Lumen's own update")
                app.orchard.tv.update.Updates.stopConfirming(this)
                confirmHandler.postDelayed({ goHome() }, 500)
            }
            return
        }
        val button = root.findAccessibilityNodeInfosByViewId("$INSTALLER:id/ok_button").firstOrNull()?.takeIf { it.isEnabled }
            ?: firstButton(CONFIRM_WORDS)
            ?: return
        if (button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "pressed Update in the installer for Lumen's own update")
        }
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    /** True for packages with a launcher entry, i.e. apps rather than system overlays and dialogs. */
    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    private fun isDefaultHome(): Boolean =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName

    private fun refreshStockHomes() {
        val standard = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        // Coocaa's launcher (Skyworth 9H89_G91 and 9S60_G60, Android 8) declares only private
        // categories such as android.intent.category.HOME.CC; the firmware starts it by component
        // (issues #69, #73). Coocaa 8R134_P6E (RTD2885N, Android 11) uses
        // smartdevice.intent.category.LITE_HOME instead (issue #122). Every MAIN activity is
        // listed so their filters can be read.
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any(::isVendorHomeCategory) == true }
        }.getOrDefault(emptyList())
        val homes = (standard + vendor)
            // Negative priority entries are fallbacks shown only while nothing else can be Home.
            .filter { it.activityInfo.packageName != packageName && it.priority >= 0 }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
        // Some launchers register Home on a trampoline that starts the screen actually shown, which
        // declares no Home filter itself: LeTV's ExternalJumpActivity opens ui.activity.Launcher
        // (LeTV Zero65 Pro, eUI 8.2, Android 6; reported with a local build in issue #121).
        stockHomes = homes.mapValues { (pkg, classes) ->
            classes + HOME_SCREENS_BEHIND_TRAMPOLINES[pkg].orEmpty().filter { isActivity(pkg, it) }
        }
    }

    /** Android's own SECONDARY_HOME is for other displays, and comes with CATEGORY_HOME anyway. */
    private fun isVendorHomeCategory(category: String): Boolean =
        category.startsWith(VENDOR_HOME_PREFIX) ||
            (category.endsWith(VENDOR_HOME_SUFFIX) && category != "android.intent.category.SECONDARY_HOME")

    /**
     * Brings Aerial's one task to the front. No CATEGORY_HOME: on firmwares where Aerial is not the
     * system Home, that category made the system open a second, home-type task with a fresh
     * MainActivity every time, which lost the focused tile (Xiaomi MiTV-MFTR0, 1.11).
     */
    private fun goHome(woke: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        // Starting an activity can light the screen: on OPPO K9 the TV came back on by itself a few
        // seconds into standby (issue #22). The screen-on check takes over at wake instead.
        if (getSystemService<PowerManager>()?.isInteractive == false) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check")
            return
        }
        runCatching {
            startActivity(MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke))
        }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
    }

    companion object {
        private const val INSTALLER = "com.android.packageinstaller"
        private const val TCAST = "com.tcl.MultiScreenInteraction_TV"
        private val CONFIRM_WORDS = listOf("立即更新", "更新", "安装", "Update", "Install")
        private val DONE_HINTS = listOf("安装已完成", "已安装", "App installed", "installed")
        private val DONE_WORDS = listOf("暂不体验", "完成", "Done")

        private const val TAG = "AerialGuard"
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L
        private const val SETTLE_MS = 1500L
        private const val WAKE_WINDOW_MS = 15_000L

        /** VIDAA's assistant and its launcher (聚好看), on the two VIDAA builds reported (issues #7, #40). */
        private val VIDAA_PACKAGES = listOf("com.hisense.vidaaassistant", "com.jamdeo.tv.vod")
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."
        private const val VENDOR_HOME_SUFFIX = "_HOME"

        /** Launcher package to the screens its Home trampoline opens; see refreshStockHomes. */
        private val HOME_SCREENS_BEHIND_TRAMPOLINES = mapOf(
            "com.stv.deskplatform" to listOf("com.stv.deskplatform.ui.activity.Launcher"),
        )

        @Volatile
        private var instance: HomeGuardService? = null

        val isRunning: Boolean get() = instance != null

        /** True if [packageName] is a stock launcher the guard keeps away from the front. */
        fun isStockHome(packageName: String): Boolean = instance?.stockHomes?.containsKey(packageName) == true

        /**
         * Opens the stock launcher on purpose (its tile in Aerial's grid). Xiaomi's launcher
         * activities require a signature permission (com.mitv.tvhome.permission.HOME_STATE), so an
         * app cannot start them; the system Home action can. Returns false if the guard is not running.
         */
        fun openStockLauncher(): Boolean {
            val s = instance ?: return false
            allowStockLauncher()
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Lets the stock launcher stay in front until Aerial is back: the viewer opened it on purpose. */
        fun allowStockLauncher() {
            instance?.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L
        }

        /** Called when Aerial comes back to the front: the stock launcher is fair game again. */
        fun onAerialResumed() {
            val s = instance ?: return
            s.allowStockUntil = 0L
            s.filterKeys(false)
        }
    }
}
