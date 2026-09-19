package net.dynart.soob

import android.graphics.Bitmap
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * GLES2 sprite batcher for the SOOB-Core virtual canvas — the port of
 * SOOB-Core-Web's `src/host/gl.ts`, shaders included (WebGL1 and GLES2 both
 * speak GLSL ES 1.0, so they are the same source).
 *
 * Virtual canvas: 480 units tall, center origin, Y growing DOWN, width scaling
 * with the surface aspect (matches `viewSize()` on the desktop build). One
 * shader draws textured quads with a per-vertex RGBA tint; flat quads use a
 * 1x1 white texture so everything goes through the same path. Quads are
 * batched by texture (consecutive same-texture draws coalesce into one
 * glDrawArrays).
 *
 * Everything here runs on the GL thread.
 */
object Renderer {

    const val VIRTUAL_H = 480f

    private const val FLOATS_PER_VERT = 8   // x,y,u,v,r,g,b,a
    private const val FLOATS_PER_QUAD = FLOATS_PER_VERT * 6

    private const val VS = """
attribute vec2 a_pos; attribute vec2 a_uv; attribute vec4 a_col;
uniform vec2 u_scale; varying vec2 v_uv; varying vec4 v_col;
void main() {
  gl_Position = vec4(a_pos.x * u_scale.x, a_pos.y * u_scale.y, 0.0, 1.0);
  v_uv = a_uv; v_col = a_col;
}"""

    private const val FS = """
precision mediump float;
varying vec2 v_uv; varying vec4 v_col; uniform sampler2D u_tex;
void main() { gl_FragColor = texture2D(u_tex, v_uv) * v_col; }"""

    private var prog = 0
    private var aPos = 0
    private var aUV = 0
    private var aCol = 0
    private var uScale = 0
    private var vbo = 0
    private var white = 0

    private var vw = 640f
    private var vh = VIRTUAL_H
    private var surfW = 1
    private var surfH = 1
    private var pxW = 1
    private var pxH = 1
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0

    private var verts = FloatArray(FLOATS_PER_QUAD * 512)
    private var used = 0
    private var vbuf: FloatBuffer = newFloatBuffer(verts.size)
    private var curTex = 0

    private val blurCache = HashMap<String, Int>()
    private var blurFbo = 0

    private fun newFloatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    /**
     * (Re)build every GL object. Called from `onSurfaceCreated`, which also
     * runs after a context loss — all previous handles are dead by then, so
     * caches are dropped and [Assets] re-uploads its textures.
     */
    fun initGL() {
        blurCache.clear()
        blurFbo = 0
        curTex = 0
        used = 0

        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(prog) }
        GLES20.glUseProgram(prog)

        aPos = GLES20.glGetAttribLocation(prog, "a_pos")
        aUV = GLES20.glGetAttribLocation(prog, "a_uv")
        aCol = GLES20.glGetAttribLocation(prog, "a_col")
        uScale = GLES20.glGetUniformLocation(prog, "u_scale")

        val bufs = IntArray(1)
        GLES20.glGenBuffers(1, bufs, 0)
        vbo = bufs[0]

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        white = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, white)
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(byteArrayOf(-1, -1, -1, -1)) // 0xFF,0xFF,0xFF,0xFF
        px.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * Upload a decoded bitmap as a GL texture.
     *
     * The bitmap must be ARGB_8888 decoded with `inPremultiplied = false`:
     * its memory layout is then straight (non-premultiplied) RGBA bytes, which
     * is what the batcher's SRC_ALPHA blend expects. Uploading a premultiplied
     * bitmap here is the classic Android sprite bug — dark edges everywhere.
     */
    fun makeTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)

        val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bmp.width, bmp.height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        curTex = 0
        return t
    }

    fun resize(w: Int, h: Int) {
        surfW = max(1, w)
        surfH = max(1, h)
        applyViewport()
    }

    /**
     * Display-cutout safe insets, in surface px. The viewport is shrunk to the
     * safe area so no UI drawn at the edge of the virtual canvas ends up under
     * a camera hole; the letterbox that leaves is the clear colour.
     */
    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        insetL = l
        insetT = t
        insetR = r
        insetB = b
        applyViewport()
    }

    private fun applyViewport() {
        pxW = max(1, surfW - insetL - insetR)
        pxH = max(1, surfH - insetT - insetB)
        vh = VIRTUAL_H
        vw = VIRTUAL_H * pxW / pxH
        // GL's viewport origin is bottom-left, so the y offset is the bottom inset.
        GLES20.glViewport(insetL, insetB, pxW, pxH)
    }

    fun viewW(): Float = vw

    fun viewH(): Float = vh

    /** The drawable rect inside the surface, in top-left view coordinates. */
    fun viewportLeft(): Int = insetL

    fun viewportTop(): Int = insetT

    fun viewportW(): Int = pxW

    fun viewportH(): Int = pxH

    /** Clear + reset the batch — the `uiBegin` equivalent. */
    fun beginFrame() {
        GLES20.glUseProgram(prog)
        GLES20.glUniform2f(uScale, 2f / vw, -2f / vh)   // center origin, Y-down
        // Clear colour comes from the bundle's app.lua, so the GL clear and the
        // pre-boot window background (a generated resource) agree. Parsed once
        // at AppInfo.load(); these are plain floats, so no per-frame allocation.
        GLES20.glClearColor(AppInfo.bgR, AppInfo.bgG, AppInfo.bgB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        used = 0
        curTex = 0
    }

    /** Submit the pending batch — the `uiEnd` equivalent. */
    fun flush() {
        if (used == 0) return
        if (vbuf.capacity() < used) vbuf = newFloatBuffer(verts.size)
        vbuf.position(0)
        vbuf.put(verts, 0, used)
        vbuf.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, used * 4, vbuf, GLES20.GL_STREAM_DRAW)
        val stride = FLOATS_PER_VERT * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, 8)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 16)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (curTex != 0) curTex else white)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, used / FLOATS_PER_VERT)
        used = 0
    }

    private fun ensure(floats: Int) {
        if (used + floats <= verts.size) return
        var size = verts.size
        while (size < used + floats) size *= 2
        verts = verts.copyOf(size)
        vbuf = newFloatBuffer(size)
    }

    private fun vertex(x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        verts[used++] = x; verts[used++] = y
        verts[used++] = u; verts[used++] = v
        verts[used++] = r; verts[used++] = g; verts[used++] = b; verts[used++] = a
    }

    /**
     * Lowest-level quad push: four explicit corners (TL, TR, BR, BL order)
     * with their UVs and a shared color. Batches by texture.
     */
    private fun pushQuadPts(tex: Int, px: FloatArray, py: FloatArray, uv: FloatArray, vv: FloatArray,
                            r: Float, g: Float, b: Float, a: Float) {
        if (tex != curTex) {
            flush()
            curTex = tex
        }
        ensure(FLOATS_PER_QUAD)
        val idx = intArrayOf(0, 1, 2, 0, 2, 3)
        for (i in idx) vertex(px[i], py[i], uv[i], vv[i], r, g, b, a)
    }

    private val qx = FloatArray(4)
    private val qy = FloatArray(4)
    private val qu = FloatArray(4)
    private val qv = FloatArray(4)

    /** Axis-aligned (optionally rotated) textured quad in virtual coords. */
    fun drawQuadTex(
        tex: Int, dx: Float, dy: Float, dw: Float, dh: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        r: Float, g: Float, b: Float, a: Float, rot: Float,
    ) {
        qx[0] = dx; qy[0] = dy
        qx[1] = dx + dw; qy[1] = dy
        qx[2] = dx + dw; qy[2] = dy + dh
        qx[3] = dx; qy[3] = dy + dh
        if (rot != 0f) {
            val cx = dx + dw / 2f
            val cy = dy + dh / 2f
            val s = sin(rot)
            val co = cos(rot)
            for (i in 0..3) {
                val ox = qx[i] - cx
                val oy = qy[i] - cy
                qx[i] = cx + ox * co - oy * s
                qy[i] = cy + ox * s + oy * co
            }
        }
        qu[0] = u0; qv[0] = v0
        qu[1] = u1; qv[1] = v0
        qu[2] = u1; qv[2] = v1
        qu[3] = u0; qv[3] = v1
        pushQuadPts(tex, qx, qy, qu, qv, r, g, b, a)
    }

    /** Flat-color quad (white texture). (x,y) is the top-left anchor. */
    fun drawSolidQuad(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        drawQuadTex(white, x, y, w, h, 0f, 0f, 1f, 1f, r, g, b, a, 0f)
    }

    /**
     * Triangle-strip-style ribbon arc, emitted as quads (one per segment) so it
     * rides the same batcher. Matches the native `uiEllipse` ribbon: thickness
     * in virtual units, centered on the perimeter. A line strip would not do —
     * GLES clamps `glLineWidth` to 1 just like WebGL.
     */
    fun drawEllipseRibbon(
        cx: Float, cy: Float, rx: Float, ry: Float,
        startPct: Float, endPct: Float, segments: Int, thickness: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        if (endPct <= startPct || rx < 1e-4f || ry < 1e-4f) return
        val s0 = max(0f, startPct)
        val s1 = min(1f, endPct)
        val tau = (Math.PI * 2).toFloat()
        val a0 = s0 * tau
        val range = (s1 - s0) * tau
        var n = floor(segments * (s1 - s0)).toInt() + 1
        if (n < 2) n = 2
        val half = thickness * 0.5f

        for (i in 0 until n) {
            val ai = a0 + range * (i.toFloat() / n)
            val aj = a0 + range * ((i + 1).toFloat() / n)
            point(cx, cy, rx, ry, ai, half, 0)
            point(cx, cy, rx, ry, aj, half, 1)
            point(cx, cy, rx, ry, aj, -half, 2)
            point(cx, cy, rx, ry, ai, -half, 3)
            qu[0] = 0f; qu[1] = 0f; qu[2] = 0f; qu[3] = 0f
            qv[0] = 0f; qv[1] = 0f; qv[2] = 0f; qv[3] = 0f
            pushQuadPts(white, qx, qy, qu, qv, r, g, b, a)
        }
    }

    private fun point(cx: Float, cy: Float, rx: Float, ry: Float, ang: Float, off: Float, slot: Int) {
        val co = cos(ang)
        val si = sin(ang)
        var nx = co / rx
        var ny = si / ry
        val nl = hypot(nx, ny)
        if (nl > 1e-4f) {
            nx /= nl
            ny /= nl
        }
        qx[slot] = cx + rx * co + nx * off
        qy[slot] = cy + ry * si + ny * off
    }

    // ---- blur (downsample into a tiny FBO, then upscale to fill the view) ----
    //
    // Matches the desktop drawBlur "color summary": render the region into a
    // small off-screen texture (default 16 px wide), then stretch it over the
    // whole view — the GPU's bilinear upscale does the blurring. The small
    // texture is cached per (name, width) since the source art is static.

    private fun buildBlur(
        key: String, srcTex: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
        pxSrcW: Float, pxSrcH: Float, targetW: Float,
    ): Int {
        blurCache[key]?.let { return it }

        val tw = max(2, min(64, Math.round(targetW)))
        val th = max(2, Math.round(tw * pxSrcH / pxSrcW))

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        flush() // emit pending main-surface draws before switching target
        if (blurFbo == 0) {
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            blurFbo = f[0]
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0,
        )
        GLES20.glViewport(0, 0, tw, th)
        GLES20.glUniform2f(uScale, 1f, 1f)   // a_pos is given directly in NDC below

        // Fullscreen NDC quad. Pair NDC-bottom with source-TOP so the FBO
        // texture ends up upright under our top-left v convention.
        val q = floatArrayOf(
            -1f, -1f, u0, v0, 1f, 1f, 1f, 1f,
            1f, -1f, u1, v0, 1f, 1f, 1f, 1f,
            1f, 1f, u1, v1, 1f, 1f, 1f, 1f,
            -1f, -1f, u0, v0, 1f, 1f, 1f, 1f,
            1f, 1f, u1, v1, 1f, 1f, 1f, 1f,
            -1f, 1f, u0, v1, 1f, 1f, 1f, 1f,
        )
        val qbuf = newFloatBuffer(q.size)
        qbuf.put(q)
        qbuf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, q.size * 4, qbuf, GLES20.GL_STREAM_DRAW)
        val stride = FLOATS_PER_VERT * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, 8)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 16)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        // restore the main render target + projection
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(insetL, insetB, pxW, pxH)
        GLES20.glUniform2f(uScale, 2f / vw, -2f / vh)
        curTex = 0

        blurCache[key] = tex
        return tex
    }

    fun drawBlur(
        key: String, srcTex: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
        pxSrcW: Float, pxSrcH: Float, targetW: Float, alpha: Float,
    ) {
        val tex = buildBlur(key, srcTex, u0, v0, u1, v1, pxSrcW, pxSrcH, targetW)
        // Cover-fit (preserve the source aspect, crop the overflow) rather than
        // stretch to the view — avoids a smeared backdrop on tall phones.
        val ra = pxSrcW / pxSrcH
        val va = vw / vh
        var uw = 1f
        var uh = 1f
        if (ra > va) uw = va / ra else uh = ra / va
        val ux = (1f - uw) / 2f
        val uy = (1f - uh) / 2f
        drawQuadTex(tex, -vw / 2f, -vh / 2f, vw, vh, ux, uy, ux + uw, uy + uh, 1f, 1f, 1f, alpha, 0f)
    }
}
