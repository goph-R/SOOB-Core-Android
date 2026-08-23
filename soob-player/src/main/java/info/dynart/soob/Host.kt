package info.dynart.soob

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer

/**
 * The object `bridge_jni.c` calls — the port of SOOB-Core-Web's
 * `src/host/bindings.ts` (`globalThis.__SOOB`), method for method.
 *
 * Each function is the Android implementation of one SOOB binding.
 * [drawRegion] carries the geometry ported from `script.h`'s `scrDrawRegion`
 * (align / fill / src-rect / dst / flip → UVs + dest rect); the rest delegate
 * to [Renderer] / [Assets] / [Audio] / [Input] / [Ime].
 *
 * Numeric arguments arrive in [args], a DoubleBuffer over the bridge's own
 * scratch array — the twin of the web bridge's `HEAPF64.subarray` view. It is
 * valid only for the duration of the call that delivered it.
 *
 * Everything here runs on the GL thread.
 */
object Host {

    private const val TAG = "SOOB"

    private lateinit var args: DoubleBuffer

    /** Where `optSave`/`optLoad` persist — `<filesDir>/<gameId>.dat`. */
    var optFile: File? = null

    /** Set by [SoobActivity]; used by requestQuit and the IME bridge. */
    var activity: SoobActivity? = null

    private fun a(i: Int): Double = args.get(i)

    private fun f(i: Int): Float = args.get(i).toFloat()

    // ---- bridge plumbing ----

    @JvmStatic
    fun setArgs(buf: ByteBuffer) {
        args = buf.order(ByteOrder.nativeOrder()).asDoubleBuffer()
    }

    @JvmStatic
    fun readAsset(path: String): ByteArray? = Assets.bytes(path)

    // ---- rendering ----

    @JvmStatic
    fun drawRegion(name: String) {
        val rg = Assets.getRegion(name)
        if (rg == null) {
            Log.w(TAG, "drawRegion: unknown region $name")
            return
        }
        val te = Assets.getTexture(rg.tex)
        if (te == null || te.tex == 0 || te.w <= 0) return
        val tw = te.w.toFloat()
        val th = te.h.toFloat()

        val x = f(0)
        val y = f(1)
        val align = a(2).toInt()
        val flip = a(3).toInt()
        var fx = f(4)
        var fy = f(5)
        val sx = f(6)
        val sy = f(7)
        val rot = f(8)
        val cr = f(9)
        val cg = f(10)
        val cb = f(11)
        val ca = f(12)
        val hasSrcX = a(13) != 0.0
        val srcX = f(14)
        val hasSrcY = a(15) != 0.0
        val srcY = f(16)
        val hasSrcW = a(17) != 0.0
        val srcW = f(18)
        val hasSrcH = a(19) != 0.0
        val srcH = f(20)
        val hasDstW = a(21) != 0.0
        val dstW = f(22)
        val hasDstH = a(23) != 0.0
        val dstH = f(24)

        fx = fx.coerceIn(0f, 1f)
        fy = fy.coerceIn(0f, 1f)

        val effSx = rg.x + (if (hasSrcX) srcX else 0f)
        val effSy = rg.y + (if (hasSrcY) srcY else 0f)
        val effSw = if (hasSrcW) srcW else rg.w
        val effSh = if (hasSrcH) srcH else rg.h

        var alignH = align and 7
        var alignV = align and 56
        if (alignH == 0) alignH = 1   // LEFT
        if (alignV == 0) alignV = 8   // TOP

        val visSw = effSw * fx
        val visSh = effSh * fy

        val x0: Float
        val x1: Float
        when (alignH) {
            1 -> { x0 = effSx; x1 = effSx + visSw }
            4 -> { x0 = effSx + effSw - visSw; x1 = effSx + effSw }
            else -> { x0 = effSx + (effSw - visSw) / 2f; x1 = x0 + visSw }
        }

        val y0: Float
        val y1: Float
        when (alignV) {
            8 -> { y0 = effSy; y1 = effSy + visSh }
            32 -> { y0 = effSy + effSh - visSh; y1 = effSy + effSh }
            else -> { y0 = effSy + (effSh - visSh) / 2f; y1 = y0 + visSh }
        }

        val dw = if (hasDstW) dstW else visSw * sx
        val dh = if (hasDstH) dstH else visSh * sy

        val dx = when (alignH) {
            1 -> x
            4 -> x - dw
            else -> x - dw / 2f
        }
        val dy = when (alignV) {
            8 -> y
            32 -> y - dh
            else -> y - dh / 2f
        }

        var u0 = x0 / tw
        var u1 = x1 / tw
        var v0 = y0 / th
        var v1 = y1 / th
        if (flip and 1 != 0) { val t = u0; u0 = u1; u1 = t }
        if (flip and 2 != 0) { val t = v0; v0 = v1; v1 = t }

        Renderer.drawQuadTex(te.tex, dx, dy, dw, dh, u0, v0, u1, v1, cr, cg, cb, ca, rot)
    }

    @JvmStatic
    fun drawText(text: String, font: String) {
        val fe = Assets.getFont(font) ?: return
        val data = fe.data ?: return
        if (fe.tex == 0) return
        data.draw(fe.tex, text, f(0), f(1), f(2), a(3).toInt(), f(4), f(5), f(6), f(7))
    }

    @JvmStatic
    fun drawQuad() {
        Renderer.drawSolidQuad(f(0), f(1), f(2), f(3), f(4), f(5), f(6), f(7))
    }

    @JvmStatic
    fun drawEllipse() {
        Renderer.drawEllipseRibbon(
            f(0), f(1), f(2), f(3), f(4), f(5), a(6).toInt(), f(7),
            f(8), f(9), f(10), f(11),
        )
    }

    /** Cover-fit a region to the whole view, cropping the longer axis via UV. */
    @JvmStatic
    fun drawBg(name: String) {
        val rg = Assets.getRegion(name) ?: return
        val te = Assets.getTexture(rg.tex) ?: return
        if (te.tex == 0 || te.w <= 0) return
        val vw = Renderer.viewW()
        val vh = Renderer.viewH()
        val ra = rg.w / rg.h
        val va = vw / vh
        var sw = rg.w
        var sh = rg.h
        if (ra > va) sw = rg.h * va else sh = rg.w / va
        val sx = rg.x + (rg.w - sw) / 2f
        val sy = rg.y + (rg.h - sh) / 2f
        Renderer.drawQuadTex(
            te.tex, -vw / 2f, -vh / 2f, vw, vh,
            sx / te.w, sy / te.h, (sx + sw) / te.w, (sy + sh) / te.h,
            1f, 1f, 1f, 1f, 0f,
        )
    }

    @JvmStatic
    fun drawBlur(name: String, width: Double, alpha: Double) {
        val rg = Assets.getRegion(name) ?: return
        val te = Assets.getTexture(rg.tex) ?: return
        if (te.tex == 0 || te.w <= 0) return
        Renderer.drawBlur(
            "$name:$width", te.tex,
            rg.x / te.w, rg.y / te.h, (rg.x + rg.w) / te.w, (rg.y + rg.h) / te.h,
            rg.w, rg.h, width.toFloat(), alpha.toFloat(),
        )
    }

    // ---- queries ----

    @JvmStatic
    fun viewW(): Double = Renderer.viewW().toDouble()

    @JvmStatic
    fun viewH(): Double = Renderer.viewH().toDouble()

    @JvmStatic
    fun regionW(name: String): Double = Assets.regionW(name)

    @JvmStatic
    fun regionH(name: String): Double = Assets.regionH(name)

    /** Writes x1, x2, y1, y2, w, h into the shared bundle; false if unsliced. */
    @JvmStatic
    fun regionSlice(name: String): Boolean {
        val rg = Assets.getRegion(name) ?: return false
        val s = rg.slice ?: return false
        args.put(0, s[0].toDouble())
        args.put(1, s[1].toDouble())
        args.put(2, s[2].toDouble())
        args.put(3, s[3].toDouble())
        args.put(4, rg.w.toDouble())
        args.put(5, rg.h.toDouble())
        return true
    }

    @JvmStatic
    fun textWidth(text: String, font: String, scale: Double): Double {
        val data = Assets.getFont(font)?.data ?: return 0.0
        return data.measure(text, scale.toFloat()).toDouble()
    }

    // ---- input polling ----

    @JvmStatic
    fun keyDown(name: String): Boolean = Input.isKeyDown(name)

    @JvmStatic
    fun mouseX(): Double = Input.mouseX.toDouble()

    @JvmStatic
    fun mouseY(): Double = Input.mouseY.toDouble()

    @JvmStatic
    fun mouseBtn(b: Int): Boolean = Input.isButtonDown(b)

    @JvmStatic
    fun keyMods(): Int = Input.modifiers()

    // ---- audio ----

    @JvmStatic
    fun soundPlay(name: String) = Audio.soundPlay(name)

    @JvmStatic
    fun musicPlay(name: String, fade: Double, loop: Boolean) = Audio.musicPlay(name, fade, loop)

    @JvmStatic
    fun musicStop(fade: Double) = Audio.musicStop(fade)

    @JvmStatic
    fun musicVolume(gain: Double) = Audio.musicVolume(gain)

    // ---- misc ----

    /**
     * The engine's transient HUD overlay. Its real use lives in SOOB-Engine
     * (native, not ported), so — exactly like the web host — it degrades to a
     * log line rather than a rendered overlay.
     */
    @JvmStatic
    fun showMessage(text: String, seconds: Double) {
        Log.i(TAG, "[message] $text")
    }

    /** Unlike the web host, this is real on Android: it finishes the activity. */
    @JvmStatic
    fun requestQuit() {
        activity?.quit()
    }

    @JvmStatic
    fun imeShow(x: Double, y: Double, w: Double, h: Double) = Ime.show(x, y, w, h)

    @JvmStatic
    fun imeHide() = Ime.hide()

    // ---- persistence ----
    //
    // The bridge serializes the opts table to a `return {...}` chunk in exactly
    // the desktop format, so a save file is portable between the desktop build
    // and the phone. Written atomically (tmp + rename), like script.h.

    @JvmStatic
    fun optSave(text: String) {
        val f = optFile ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) Log.w(TAG, "optSave: rename failed")
        } catch (e: Exception) {
            Log.w(TAG, "optSave failed: $e")
        }
    }

    @JvmStatic
    fun optLoad(): String? {
        val f = optFile ?: return null
        return try {
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            Log.w(TAG, "optLoad failed: $e")
            null
        }
    }

    // ---- asset registration (called while the bridge walks assets.lua) ----

    @JvmStatic
    fun regSound(name: String, path: String) = Assets.registerSound(name, path)

    @JvmStatic
    fun regMusic(name: String, path: String) = Assets.registerMusic(name, path)

    @JvmStatic
    fun regTexture(name: String, path: String) = Assets.registerTexture(name, path)

    @JvmStatic
    fun regFont(name: String, path: String) = Assets.registerFont(name, path)

    @JvmStatic
    fun regRegion(name: String, tex: String) = Assets.registerRegion(name, tex, args)
}
