package app.orchard.tv.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import app.orchard.tv.wallpaper.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One title the owner was watching, as the TV's own launcher recorded it. */
data class HistoryItem(
    val id: String,
    val packageName: String,
    val title: String,
    val coverUrl: String?,
    val positionMs: Int,
    val durationMs: Int,
    val receivedAt: Long,
    private val srcApp: String,
    private val extras: Map<String, String?>,
) {
    /** How far in, 0..1; null when the app did not say. */
    val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    /** Whole minutes left, at least 1; null when unknown. */
    val minutesLeft: Int? get() = if (durationMs > 0) ((durationMs - positionMs) / 60_000).coerceAtLeast(1) else null

    /**
     * The intent TCL's launcher (com.tcl.cyberui, HisFavClickAction.showVideo) sends to resume this
     * title: the source app's action, the history row copied into extras, aimed at the package.
     */
    fun resumeIntent(): Intent = Intent(srcApp)
        .setPackage(packageName)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .apply {
            this@HistoryItem.extras.forEach { (k, v) -> if (v != null) putExtra(k, v) }
            putExtra("currentPosition", positionMs)
            putExtra("duration", durationMs)
        }
}

/**
 * Watch history kept by TCL's China-market launcher (com.tcl.cyberui). Video apps report what is
 * played to it; its provider is exported with no read permission. Verified on TCL 65V8L
 * (tc8000_ay30a2, Android 11), where 哔哩哔哩 writes to it.
 */
object TclHistory {
    private const val TAG = "LumenHistory"
    val URI: Uri = Uri.parse("content://com.tcl.cyberui.historyVideofavourites/HistoryTableName")
    private val STRING_EXTRAS = listOf("startUpMode", "videoId", "episodeId", "cmdstring", "cmdInfo", "definition", "userKey")

    /** The latest titles, newest first, one per video, only for apps still installed. */
    suspend fun load(context: Context, limit: Int = 6): List<HistoryItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val rows = runCatching {
            context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                fun s(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                fun i(name: String) = s(name)?.toIntOrNull() ?: 0
                buildList {
                    while (c.moveToNext()) {
                        val pkg = s("packageName") ?: continue
                        val src = s("srcApp") ?: continue
                        val title = (s("videoName") ?: s("episodeName"))?.let(::cleanTitle)?.takeIf { it.isNotEmpty() } ?: continue
                        add(HistoryItem(
                            id = "$pkg/${s("videoId")}",
                            packageName = pkg,
                            title = title,
                            coverUrl = s("videoImgUrl")?.takeIf { it.startsWith("http") }?.let(::fullSize),
                            positionMs = i("currentPosition"),
                            durationMs = durationMs(i("currentPosition"), i("duration")),
                            receivedAt = s("receivedtime")?.toLongOrNull() ?: 0L,
                            srcApp = src,
                            extras = STRING_EXTRAS.associateWith(::s),
                        ))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "No TCL history: $it") }.getOrNull().orEmpty()
        rows.sortedByDescending { it.receivedAt }
            .distinctBy { it.id }
            // Watched to the end: nothing to resume (tvOS drops these from Up Next too).
            .filterNot { it.durationMs > 0 && it.positionMs >= it.durationMs * 0.97f }
            // A few seconds in is a misclick or a trailer, not something being watched.
            .filterNot { it.positionMs < MIN_WATCHED_MS }
            .filter { runCatching { pm.getPackageInfo(it.packageName, 0) }.isSuccess }
            .take(limit)
    }

    private const val MIN_WATCHED_MS = 10_000

    /**
     * iQIYI (com.tcl.qiyiguo) writes the position in milliseconds but the duration in seconds
     * ("currentPosition=211000, duration=421"). A duration shorter than the position, that fits
     * once read as seconds, is taken as seconds.
     */
    private fun durationMs(position: Int, duration: Int): Int =
        if (duration in 1 until position && duration * 1000L >= position) duration * 1000 else duration

    /**
     * Titles as the apps wrote them, made fit for a TV: emoji and pictographs go (the TV's fonts
     * draw many as tofu, and tvOS titles carry none), as do the "??" left where an app stored an
     * emoji its database could not hold ("Vlog??30+…" from Bilibili), stray format characters, and
     * doubled spaces.
     */
    internal fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\?{2,}"), " ")
        .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200B}-\\x{200F}\\x{2060}-\\x{206F}\\x{E0000}-\\x{E007F}\\x{FFFD}\\x{20E3}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('|', '-', '·', '丨')
        .trim()

    /**
     * Covers at the size a TV needs. Tencent records its poster at thumbnail size (".../vcover_vt_pic/0/<id>/260", 260×364);
     * the same path ending in /0 is the original (1280×1796). Checked 2026-10-22.
     */
    private fun fullSize(raw: String): String {
        // Youku (com.cibn.tv) records covers over plain http, which Android refuses; the same host
        // serves them over https (m.cp12.ott.cibntv.net, checked 2026-10-26).
        val url = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
        return sized(url)
    }

    private fun sized(url: String): String = when {
        "qpic.cn/vcover_" in url -> url.replace(Regex("/\\d+$"), "/0")
        // iQIYI's image server cuts the title's own key art to the size asked for in the name
        // ("..._m1_352_488.jpg" is the poster; "_1280_720" is landscape art). Checked 2026-10-22.
        "ptqy.gitv.tv/image/" in url -> url.replace(Regex("_\\d+_\\d+\\.(jpg|png|webp)$"), "_1280_720.$1")
        else -> url
    }

    private val covers = HashMap<String, Bitmap>()

    /** A cover already fetched, or null. Cheap: for drawing without waiting. */
    fun cached(url: String): Bitmap? = synchronized(covers) { covers[url] }

    /** Drops covers of titles no longer in the history, so the cache stays the history's size. */
    fun keepOnly(urls: Set<String>) = synchronized(covers) { covers.keys.retainAll(urls) }

    /** The cover, cut to fill a [w]×[h] screen. Kept for the session; null when it cannot be had. */
    suspend fun cover(url: String, w: Int, h: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(covers) { covers[url] }?.let { return@withContext it }
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (raw.height > raw.width) posterScene(raw, w, h).also { raw.recycle() }
            else Images.cover(raw, w, h).also { if (it !== raw) raw.recycle() }
        }.onFailure { Log.w(TAG, "Cover failed: $it") }.getOrNull()
            ?.also { synchronized(covers) { covers[url] = it } }
    }

    /**
     * A portrait poster (Tencent) cropped to fill a wide screen would lose most of it. Instead the
     * poster, blurred and dimmed, fills the screen, and the poster itself takes the right half
     * from top to bottom, fading into that ground on its left and toward the dock, like key art
     * on a poster wall. The title on the left and the centred page dots stay on the ground.
     */
    private fun posterScene(poster: Bitmap, w: Int, h: Int): Bitmap {
        val frosted = Images.frost(Images.cover(poster, 320, 180))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(frosted, null, android.graphics.Rect(0, 0, w, h), paint)
        canvas.drawColor(android.graphics.Color.argb(90, 0, 0, 0))
        frosted.recycle()

        // The poster on a layer of its own, its edges faded by masking that layer.
        val pw = w / 2
        val ph = pw * poster.height / poster.width
        val layer = Bitmap.createBitmap(pw, h, Bitmap.Config.ARGB_8888)
        val lc = android.graphics.Canvas(layer)
        val top = -(h * 0.055f)
        lc.drawBitmap(poster, null, android.graphics.RectF(0f, top, pw.toFloat(), top + ph), paint)
        val mask = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        val clear = android.graphics.Color.TRANSPARENT
        val solid = android.graphics.Color.BLACK
        mask.shader = android.graphics.LinearGradient(0f, 0f, pw * 0.28f, 0f, clear, solid, android.graphics.Shader.TileMode.CLAMP)
        lc.drawRect(0f, 0f, pw.toFloat(), h.toFloat(), mask)
        mask.shader = android.graphics.LinearGradient(0f, h * 0.62f, 0f, h * 0.86f, solid, clear, android.graphics.Shader.TileMode.CLAMP)
        lc.drawRect(0f, 0f, pw.toFloat(), h.toFloat(), mask)
        canvas.drawBitmap(layer, (w - pw).toFloat(), 0f, null)
        layer.recycle()
        return out
    }
}
