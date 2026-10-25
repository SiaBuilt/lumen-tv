package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import java.security.MessageDigest

/**
 * How the focused app shows itself, named after tvOS's Accessibility > Increase Contrast > Focus Style.
 *
 * tvOS's standard focus relies on the Siri Remote's touch surface: the icon tilts with the thumb, and
 * that motion is what the eye follows. With the D-pad remotes these TVs ship with, the icon only
 * grows, and owners lost track of it. High Contrast adds a white frame and is the default here.
 */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        // Lumen: tvOS's lift and sheen without a frame, unless the owner chose the frame.
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Standard
    }
}

enum class AppNameMode(val key: String) {
    Always("always"), Focused("focused"), Hidden("hidden");
    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Always
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {
    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    var focusStyle by mutableStateOf(FocusStyle.Standard); private set

    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to Aerial. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /**
     * Only the top row on Home, low on the screen, so the wallpaper (a family photo) shows above it,
     * as tvOS does under a full-screen top shelf. The rest of the grid is one press down.
     */
    var homeRows by mutableStateOf(2); private set
    /** Opt-in upstream dock; existing row counts and app order remain unchanged. */
    var scrollingHome by mutableStateOf(false); private set
    val oneRowHome: Boolean get() = scrollingHome || homeRows == 1
    var appNameSize by mutableStateOf(14); private set
    var appNameMode by mutableStateOf(AppNameMode.Always); private set
    val appNameSizes = listOf(12, 14, 16, 18)

    /** Only the glass behind the apps fades; tile artwork and labels stay opaque. */
    var appBarTransparency by mutableStateOf(0); private set
    val appBarTransparencies = (0..100 step 10).toList()

    /** Interface sounds (system/Sounds), as tvOS plays them on every press. */
    var uiSounds by mutableStateOf(true); private set

    /** The clock, with the date and weather, in the top right instead of the top left. */
    var clockRight by mutableStateOf(false); private set

    /** Back on Home's top row starts the screensaver, as on tvOS; off for homes where it gets pressed by accident (issue #94). */
    var backStartsScreensaver by mutableStateOf(true); private set

    /**
     * Parental PIN (issue #10): asked before TV settings open and before hidden apps come back.
     * Kept as a SHA-256 hash. One right entry holds for a while, until Home is pressed.
     */
    var pinSet by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    /**
     * Minutes of no input on Home before Aerial's screensaver starts (issue #66). The firmware's own
     * screensaver still caps it: Aerial starts just ahead of that one, see MainActivity.
     */
    var screensaverMinutes by mutableStateOf(DEFAULT_SCREENSAVER_MINUTES); private set

    val screensaverChoices = listOf(2, 5, 10, 20)
    private const val DEFAULT_SCREENSAVER_MINUTES = 5

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        homeRows = prefs.getInt("home_rows", 2).coerceIn(1, 3)
        scrollingHome = prefs.getBoolean("scrolling_home", false)
        appNameSize = prefs.getInt("app_name_size", 14).takeIf { it in appNameSizes } ?: 14
        appNameMode = AppNameMode.of(prefs.getString("app_name_mode", null))
        appBarTransparency = prefs.getInt("app_bar_transparency", 0)
            .takeIf { it in appBarTransparencies } ?: 0
        clockRight = prefs.getBoolean("clock_right", false)
        uiSounds = prefs.getBoolean("ui_sounds", true)
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        screensaverMinutes = prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id) }
    }

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Sets the PIN, or turns it off with null. */
    fun choosePin(pin: String?) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        unlockedUntil = if (pin != null) SystemClock.elapsedRealtime() + UNLOCK_MS else 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseOneRowHome(on: Boolean) {
        chooseScrollingHome(false)
        if (on) chooseHomeRows(1) else if (homeRows == 1) chooseHomeRows(2)
    }

    fun chooseHomeRows(rows: Int) {
        homeRows = rows.coerceIn(1, 3)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("home_rows", homeRows)
            putBoolean("one_row_home", homeRows == 1)
        }
    }

    fun chooseScrollingHome(on: Boolean) {
        scrollingHome = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("scrolling_home", on) }
    }

    fun chooseAppNameSize(size: Int) {
        appNameSize = size.takeIf { it in appNameSizes } ?: 14
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("app_name_size", appNameSize) }
    }

    fun chooseAppNameMode(mode: AppNameMode) {
        appNameMode = mode
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("app_name_mode", mode.key) }
    }

    fun chooseAppBarTransparency(percent: Int) {
        appBarTransparency = percent.takeIf { it in appBarTransparencies } ?: 0
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("app_bar_transparency", appBarTransparency)
        }
    }

    fun chooseUiSounds(on: Boolean) {
        uiSounds = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("ui_sounds", on) }
    }

    fun chooseClockRight(on: Boolean) {
        clockRight = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_right", on) }
    }

    fun chooseBackStartsScreensaver(on: Boolean) {
        backStartsScreensaver = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("back_starts_screensaver", on) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        screensaverMinutes = minutes
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_minutes", minutes) }
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseFocusStyle(style: FocusStyle) {
        focusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }
}
