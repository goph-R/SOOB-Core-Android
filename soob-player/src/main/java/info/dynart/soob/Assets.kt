package info.dynart.soob

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Asset registries + loaders — the port of SOOB-Core-Web's `src/host/assets.ts`.
 *
 * `register*` are called synchronously by the JNI bridge while it walks
 * `assets.lua` (just metadata: names, paths, region rects). [loadGraphics]
 * then does the actual decode + GL upload. Unlike the web host there is no
 * sync→async gate to engineer: `AssetManager` reads are synchronous, so this
 * mirrors the desktop `scriptLoadAssets` directly — but it must run on the GL
 * thread, since it uploads textures.
 *
 * The bundle lives under `assets/game/` in the APK (put there by the syncGame
 * Gradle task); paths inside the registries are relative to that root.
 */
object Assets {

    private const val TAG = "SOOB"
    private const val BASE = "game/"

    class TexEntry(val path: String) {
        var tex = 0
        var w = 0
        var h = 0
    }

    class RegionEntry(
        val tex: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val slice: FloatArray?,   // x1, x2, y1, y2 or null
    )

    class FontEntry(val path: String) {
        var data: BmFont? = null
        var tex = 0
    }

    private lateinit var am: AssetManager

    private val textures = LinkedHashMap<String, TexEntry>()
    private val regions = HashMap<String, RegionEntry>()
    private val fonts = LinkedHashMap<String, FontEntry>()
    private val sounds = LinkedHashMap<String, MutableList<String>>()
    private val music = HashMap<String, String>()
    private var defaultFont: String? = null

    fun attach(manager: AssetManager) {
        am = manager
    }

    /** Raw bytes of one bundle file, or null when it isn't there. */
    fun bytes(rel: String): ByteArray? = try {
        am.open(BASE + rel).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun assetPath(rel: String): String = BASE + rel

    fun manager(): AssetManager = am

    // ---- registration (called from the bridge while walking assets.lua) ----

    fun registerTexture(name: String, path: String) {
        textures[name] = TexEntry(path)
    }

    fun registerFont(name: String, path: String) {
        fonts[name] = FontEntry(path)
        if (defaultFont == null || name == "default") defaultFont = name
    }

    fun registerSound(name: String, path: String) {
        sounds.getOrPut(name) { ArrayList() }.add(path)
    }

    fun registerMusic(name: String, path: String) {
        music[name] = path
    }

    fun registerRegion(name: String, tex: String, a: java.nio.DoubleBuffer) {
        val slice = if (a.get(4) != 0.0) {
            floatArrayOf(a.get(5).toFloat(), a.get(6).toFloat(), a.get(7).toFloat(), a.get(8).toFloat())
        } else {
            null
        }
        regions[name] = RegionEntry(
            tex,
            a.get(0).toFloat(), a.get(1).toFloat(), a.get(2).toFloat(), a.get(3).toFloat(),
            slice,
        )
    }

    // ---- loading ----

    /**
     * Decode a PNG straight (non-premultiplied) so the batcher's SRC_ALPHA
     * blend is correct — see [Renderer.makeTexture].
     */
    private fun decode(rel: String): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            am.open(BASE + rel).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "asset missing: $rel ($e)")
            null
        }
    }

    private fun dir(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    // ---- graphics loading, one job per step ----
    //
    // Decoding + uploading runs on the GL thread, so it is sliced into jobs and
    // pumped one per frame while [GameView] draws the loading screen. The
    // whole-batch form is used for the context-loss reload, where there is
    // nothing to show anyway.

    private var jobs: List<() -> Unit> = emptyList()
    private var jobIndex = 0

    fun beginLoad() {
        val list = ArrayList<() -> Unit>(textures.size + fonts.size)
        for (e in textures.values) list.add { loadTexture(e) }
        for (f in fonts.values) list.add { loadFont(f) }
        jobs = list
        jobIndex = 0
    }

    /** Run one job; returns true while work remains. */
    fun loadStep(): Boolean {
        if (jobIndex >= jobs.size) return false
        jobs[jobIndex++].invoke()
        return jobIndex < jobs.size
    }

    fun loadDone(): Int = jobIndex

    fun loadTotal(): Int = jobs.size

    /**
     * Decode + upload every texture and font page in one go. Used for the
     * EGL context-loss reload path: all previous handles die with the context.
     */
    fun loadGraphics() {
        beginLoad()
        @Suppress("ControlFlowWithEmptyBody")
        while (loadStep());
    }

    private fun loadTexture(e: TexEntry) {
        val bmp = decode(e.path)
        if (bmp != null) {
            e.tex = Renderer.makeTexture(bmp)
            e.w = bmp.width
            e.h = bmp.height
            bmp.recycle()
        } else {
            e.tex = 0
            e.w = 0
            e.h = 0
        }
    }

    private fun loadFont(f: FontEntry) {
        val text = bytes(f.path)?.toString(Charsets.UTF_8)
        if (text == null) {
            Log.w(TAG, "font skipped: ${f.path}")
            return
        }
        val data = BmFont.parse(text)
        f.data = data
        val page = dir(f.path) + data.pageFile
        val bmp = decode(page)
        if (bmp != null) {
            f.tex = Renderer.makeTexture(bmp)
            bmp.recycle()
        } else {
            f.tex = 0
        }
    }

    /** Hand the sound/music tables to the audio backend (safe off the GL thread). */
    fun loadAudio() {
        Audio.setMusicResolver { name -> music[name] }
        for ((name, paths) in sounds) {
            for (p in paths) Audio.addSound(name, BASE + p)
        }
    }

    // ---- queries (for the bindings / renderer) ----

    fun getRegion(name: String): RegionEntry? = regions[name]

    fun getTexture(name: String): TexEntry? = textures[name]

    fun getFont(name: String?): FontEntry? {
        if (!name.isNullOrEmpty()) fonts[name]?.let { return it }
        return defaultFont?.let { fonts[it] }
    }

    fun regionW(name: String): Double = regions[name]?.w?.toDouble() ?: -1.0

    fun regionH(name: String): Double = regions[name]?.h?.toDouble() ?: -1.0

    /** Drops registry state so a fresh boot starts clean (not used per-frame). */
    fun clear() {
        textures.clear()
        regions.clear()
        fonts.clear()
        sounds.clear()
        music.clear()
        defaultFont = null
    }
}
