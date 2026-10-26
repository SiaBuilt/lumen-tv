package app.orchard.tv.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer Lumen is out. Every few hours Lumen reads the latest version number from its
 * repository (version.txt, through jsDelivr's CDN, which is reachable in China, else GitHub);
 * Control Center then names the new version, and Home says so once. Nothing is downloaded or
 * installed in the background: TCL takes installs only through 多屏互动, with an Agree on the TV.
 */
object Updates {
    private const val TAG = "LumenUpdates"
    // Not "updates": builds up to versionCode 41 kept Aerial's latest version (1.26) there, read
    // from Aerial's site, which then showed as a newer Lumen (TCL 65V8L, 2026-10-22).
    private const val PREFS = "lumen_updates"
    private val VERSION_URLS = listOf(
        // Cloudflare Pages on the owner's domain: reachable from China, unlike GitHub and jsDelivr
        // (both failed from the TCL 65V8L, 2026-10-22). Published by tools/publish-update.sh.
        "https://lumen.siabuilt.com/version.txt",
        "https://cdn.jsdelivr.net/gh/SiaBuilt/lumen-tv@main/version.txt",
        "https://raw.githubusercontent.com/SiaBuilt/lumen-tv/main/version.txt",
    )
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val SOON_MS = 60 * 1000L

    /** This build's version, e.g. "1.0" (the "-lumen" and "-guard" left off). */
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** The newer release's version, or null when this is the latest (or nothing is known yet). */
    var available by mutableStateOf<String?>(null); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        available = prefs.getString("latest", null)?.takeIf { newer(it, current) }
    }

    /**
     * [soon]: Control Center was opened, the owner may well be looking for an update; check if the
     * last check is a minute old. Ten minutes hid a release made just after Lumen started
     * (TCL 65V8L, 2026-10-22).
     */
    fun checkIfStale(soon: Boolean = false) {
        if (!started) return
        val now = System.currentTimeMillis()
        val gap = if (soon) SOON_MS else RETRY_MS
        if (now - lastAttempt < gap || now - prefs.getLong("checked_at", 0) < if (soon) SOON_MS else CHECK_EVERY_MS) return
        lastAttempt = now
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                VERSION_URLS.firstNotNullOfOrNull { url ->
                    runCatching { read(url).trim().removePrefix("v") }
                        .onFailure { Log.w(TAG, "no version from $url: $it") }
                        .getOrNull()
                }
                    .let { if (it == null) Result.failure(IllegalStateException("no version source answered")) else Result.success(it) }
                    .onFailure { Log.w(TAG, "could not check for a new version", it) }
                    .getOrNull()
                    ?.takeIf { it.matches(Regex("\\d+(\\.\\d+)*")) }
            } ?: return@launch
            prefs.edit { putString("latest", latest); putLong("checked_at", System.currentTimeMillis()) }
            available = latest.takeIf { newer(it, current) }
            // Said once on Home for each new version; Control Center keeps saying it.
            available?.takeIf { prefs.getString("announced", null) != it }?.let { v ->
                prefs.edit { putString("announced", v) }
                android.widget.Toast.makeText(app, app.getString(R.string.update_toast, v), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Whether version [a] comes after [b], comparing dotted numbers ("1.10" after "1.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * Until when Home Guard may press Update in the system installer for Lumen's own update: set as
     * the installer opens. TCL's installer shows no focus on its button (TCL 65V8L, 2026-10-22).
     */
    @Volatile var confirmSelfUntil = 0L

    /**
     * [confirmSelfUntil], kept on disk: installing the update restarts Lumen, and the new process
     * must still recognise the installer's "done" dialog as its own (TCL 65V8L, 1.2 → 1.3).
     */
    fun confirmUntil(context: Context): Long = maxOf(
        confirmSelfUntil,
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("confirm_until", 0L),
    )

    fun stopConfirming(context: Context) {
        confirmSelfUntil = 0L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove("confirm_until") }
    }

    /** Download progress 0..1 while a new version is being fetched; null otherwise. */
    var downloading by mutableStateOf<Float?>(null); private set

    /** The last download or install did not work; Control Center offers to try again. */
    var failed by mutableStateOf(false); private set

    /**
     * Downloads the new version from this repository's release and hands it to the system installer,
     * which asks on screen. TCL refuses installs from adb but accepts an app opening its installer
     * this way (as 当贝 does); install.sh grants Lumen REQUEST_INSTALL_PACKAGES up front.
     */
    /**
     * Installs the new version. First through TCL's 多屏互动, which installs in the background with
     * one Agree that Home Guard presses, and no installer screens at all; failing that (not a TCL,
     * or [quiet]), through the system installer, which asks on screen.
     */
    fun install(context: Context, quiet: Boolean = false) {
        val version = available ?: return
        if (downloading != null) return
        failed = false
        downloading = 0f
        scope.launch {
            val pushed = withContext(Dispatchers.IO) { runCatching { tcastInstall(context) }.getOrDefault(false) }
            if (pushed) { downloading = null; return@launch }
            if (quiet) { downloading = null; return@launch }
            val apk = withContext(Dispatchers.IO) {
                listOf(
                    "https://lumen.siabuilt.com/lumen-${BuildConfig.FLAVOR}.apk",
                    "https://github.com/SiaBuilt/lumen-tv/releases/download/v$version/lumen-${BuildConfig.FLAVOR}.apk",
                ).firstNotNullOfOrNull { url ->
                    runCatching { download(url, context) }
                        .onFailure { Log.w(TAG, "could not download $version from $url", it) }
                        .getOrNull()
                }
            }
            downloading = null
            if (apk == null || !isNewerLumen(context, apk)) { failed = true; return@launch }
            runCatching { openInstaller(context, apk) }
                .onFailure { Log.w(TAG, "could not open the installer", it); failed = true }
        }
    }

    /**
     * Updates when nobody is watching: called as the screensaver starts. At most every six hours,
     * and only where 多屏互动 can install without screens.
     */
    fun installWhenIdle(context: Context) {
        if (available == null || downloading != null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("auto_tried_at", 0) < 6 * 60 * 60_000L) return
        prefs.edit { putLong("auto_tried_at", System.currentTimeMillis()) }
        Log.i(TAG, "idle: installing $available in the background")
        install(context, quiet = true)
    }

    /**
     * Asks TCL's 多屏互动 (port 6553 on the TV's own Wi-Fi address; it does not listen on loopback)
     * to download the APK from lumen.siabuilt.com and install it, as tools/tcast-push.py does from
     * a computer. Keeps the session alive until the install starts, or the update replaces this
     * process. False when 多屏互动 is not there or says no.
     */
    private fun tcastInstall(context: Context): Boolean {
        val ip = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress } ?: return false
        val url = "https://lumen.siabuilt.com/lumen-${BuildConfig.FLAVOR}.apk"
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(ip, 6553), 3000)
            sock.soTimeout = 1000
            val out = java.io.DataOutputStream(sock.getOutputStream())
            val input = java.io.DataInputStream(sock.getInputStream())
            fun send(text: String) { val b = text.toByteArray(); out.writeInt(b.size); out.write(b); out.flush() }
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            setConfirming(context)
            send("159>>1>>0")
            send(listOf("223", "INSTALL", context.packageName, label, url, "2", "0", "20", "", "", "", md5).joinToString(">>"))
            var accepted = false
            var lastBeat = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                val msg = try {
                    val n = input.readInt(); ByteArray(n).also { input.readFully(it) }.decodeToString()
                } catch (e: java.net.SocketTimeoutException) { null }
                val parts = msg?.split(">>").orEmpty()
                if (parts.size >= 5 && parts[0] == "223" && parts[2] == context.packageName) {
                    when {
                        parts[1] == "INSTALL" && parts[4] == "denied" -> return false
                        parts[1] == "INSTALL" -> accepted = true
                        parts[1] == "PROGRESS" && parts[4] == "2" -> return false
                        parts[1] == "PROGRESS" && parts[4] == "5" -> return true
                    }
                    Log.i(TAG, "多屏互动: $msg")
                }
                if (System.currentTimeMillis() - lastBeat > 5000) { send("150>>"); lastBeat = System.currentTimeMillis() }
            }
            return accepted
        }
    }

    private fun setConfirming(context: Context) {
        confirmSelfUntil = System.currentTimeMillis() + 5 * 60_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("confirm_until", confirmSelfUntil) }
    }

    private fun download(url: String, context: Context): java.io.File {
        val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = java.io.File(dir, "lumen.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        try {
            val total = c.contentLengthLong.takeIf { it > 0 }
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total != null) scope.launch { downloading = (done.toFloat() / total).coerceIn(0f, 1f) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** Only a Lumen newer than this one is offered to the installer. */
    @Suppress("DEPRECATION")
    private fun isNewerLumen(context: Context, apk: java.io.File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return info.packageName == context.packageName && code > BuildConfig.VERSION_CODE
    }

    private fun openInstaller(context: Context, apk: java.io.File) {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            // Not granted by install.sh (an older install): the system page that allows it.
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        setConfirming(context)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
