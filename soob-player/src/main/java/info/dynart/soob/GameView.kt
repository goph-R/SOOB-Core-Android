package info.dynart.soob

import android.opengl.GLSurfaceView
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
// Inside a GLSurfaceView subclass the bare name `Renderer` resolves to the
// inherited GLSurfaceView.Renderer interface, so the batcher gets an alias.
import info.dynart.soob.Renderer as Gfx
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL surface and the frame loop — the port of SOOB-Core-Web's
 * `src/host/loop.ts` plus the DOM-event half of `src/host/input.ts`.
 *
 * Threading model: the Lua VM and every binding run on this view's render
 * thread. Touch and key events arrive on the UI thread and are marshalled with
 * [queueEvent], so they land between frames — the same order as the native
 * host's poll-events-then-update loop.
 */
class GameView(private val activity: SoobActivity) : GLSurfaceView(activity) {

    private companion object {
        const val TAG = "SOOB"
        const val MAX_DT = 0.1                     // same clamp as the web loop
        const val OFFSCREEN = -1e5f                // parks the virtual cursor
        const val BACK_QUIT_NANOS = 2_000_000_000L // double-press window to quit
        const val LOAD_BUDGET_NANOS = 8_000_000L   // decode work per loading frame
    }

    /** Boot is sliced across frames so the loading screen can animate. */
    private enum class Phase { START, LOADING, FINISH, RUNNING, FAILED }

    private val renderer = SoobRenderer()

    /** True once assets are loaded and `onStart` has fired. */
    @Volatile
    var booted = false
        private set

    private var phase = Phase.START
    private var lastNanos = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastBackNanos = 0L

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun queueGl(block: () -> Unit) = queueEvent(block)

    // ---- frame loop ----

    private inner class SoobRenderer : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Also runs after an EGL context loss: every GL handle from the
            // previous context is dead, so the batcher is rebuilt and the
            // textures are re-uploaded.
            Gfx.initGL()
            if (booted) {
                Log.i(TAG, "GL context recreated — reloading textures")
                Assets.loadGraphics()
            }
            lastNanos = 0L
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Gfx.resize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (phase != Phase.RUNNING) {
                bootStep()
                drawBootScreen()
                return
            }
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.0 else minOf((now - lastNanos) / 1e9, MAX_DT)
            lastNanos = now

            Lua.update(dt)          // onUpdate(dt)
            Gfx.beginFrame()   // clear + reset batch   (uiBegin equivalent)
            Lua.render()            // onRender() — all draw* calls
            Gfx.flush()        // submit the last batch (uiEnd + swap)
        }
    }

    /**
     * Boot, one slice per frame, mirroring the desktop host (and the web
     * host's `src/game/main.ts`): stand up the VM, run `assets.lua` to fill
     * the registries, decode every texture and font, then run `main.lua` and
     * fire `onStart`. Slicing keeps the loading screen responsive; the desktop
     * build does the same work in one synchronous block.
     */
    private fun bootStep() {
        when (phase) {
            Phase.START -> {
                if (!Lua.newState()) {
                    Log.e(TAG, "failed to create the Lua state")
                    phase = Phase.FAILED
                    return
                }
                Lua.setPlatform("android")
                if (!Lua.loadAssets("assets.lua")) {
                    Log.e(TAG, "assets.lua failed — is the game bundle synced into assets/game/?")
                    phase = Phase.FAILED
                    return
                }
                Assets.beginLoad()
                Assets.loadAudio()
                phase = Phase.LOADING
            }

            Phase.LOADING -> {
                val deadline = System.nanoTime() + LOAD_BUDGET_NANOS
                var more = true
                while (more && System.nanoTime() < deadline) more = Assets.loadStep()
                if (!more) phase = Phase.FINISH
            }

            Phase.FINISH -> {
                if (!Lua.doAsset("scripts/main.lua")) {
                    Log.e(TAG, "scripts/main.lua failed")
                    phase = Phase.FAILED
                    return
                }
                Lua.callHook0("onStart")
                booted = true
                phase = Phase.RUNNING
                lastNanos = 0L
            }

            else -> Unit
        }
    }

    /**
     * The loading screen: a progress bar drawn straight through the batcher,
     * before any game asset exists. Red on failure — the reason is in Logcat.
     */
    private fun drawBootScreen() {
        Gfx.beginFrame()
        val vw = Gfx.viewW()
        val w = vw * 0.4f
        val h = 6f
        val x = -w / 2f
        val y = 60f
        val total = Assets.loadTotal()
        val p = if (phase == Phase.FINISH) 1f
        else if (total > 0) Assets.loadDone().toFloat() / total
        else 0f

        if (phase == Phase.FAILED) {
            Gfx.drawSolidQuad(x, y, w, h, 0.8f, 0.2f, 0.2f, 1f)
        } else {
            Gfx.drawSolidQuad(x, y, w, h, 1f, 1f, 1f, 0.15f)
            if (p > 0f) Gfx.drawSolidQuad(x, y, w * p, h, 0.95f, 0.91f, 0.86f, 0.9f)
        }
        Gfx.flush()
    }

    // ---- input (UI thread → GL thread) ----

    // Pointer px -> virtual canvas, against the drawable rect (which excludes
    // the display-cutout insets), so hit-testing matches what is on screen.
    private fun toVirtualX(ex: Float): Float =
        ((ex - Gfx.viewportLeft()) / Gfx.viewportW() - 0.5f) * Gfx.viewW()

    private fun toVirtualY(ey: Float): Float =
        ((ey - Gfx.viewportTop()) / Gfx.viewportH() - 0.5f) * Gfx.viewH()

    /**
     * Which SOOB button a press maps to: 1 = left, 2 = middle, 3 = right.
     * Touch is always 1; a real mouse (Chromebook, DeX, a tablet with a
     * trackpad) reports its own button state.
     */
    private fun buttonOf(event: MotionEvent): Int {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return 1
        return when {
            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> 2
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 3
            else -> 1
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return true
        val x = toVirtualX(event.x)
        val y = toVirtualY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                val b = buttonOf(event)
                queueEvent {
                    Input.setMouse(x, y)
                    Input.pressButton(b)
                    Lua.mouseDown(x.toDouble(), y.toDouble(), b)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                queueEvent {
                    Input.setMouse(x, y)
                    Lua.mouseMove(x.toDouble(), y.toDouble(), dx.toDouble(), dy.toDouble())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = buttonOf(event)
                queueEvent {
                    Input.setMouse(x, y)
                    Input.releaseButton(b)
                    Lua.mouseUp(x.toDouble(), y.toDouble(), b)
                    // Touch has no pointer-leave: park the cursor off-canvas so
                    // a tapped widget doesn't stay lit (same as the web host).
                    Input.setMouse(OFFSCREEN, OFFSCREEN)
                    Lua.mouseMove(OFFSCREEN.toDouble(), OFFSCREEN.toDouble(), 0.0, 0.0)
                }
            }
        }
        return true
    }

    /** Mouse wheel — buttons 4 (up) and 5 (down), same as the desktop host. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && width != 0 && height != 0) {
            val x = toVirtualX(event.x)
            val y = toVirtualY(event.y)
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                val b = if (v > 0f) 4 else 5
                queueEvent { Lua.mouseDown(x.toDouble(), y.toDouble(), b) }
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE && width != 0 && height != 0) {
            val x = toVirtualX(event.x)
            val y = toVirtualY(event.y)
            val dx = x - lastX
            val dy = y - lastY
            lastX = x
            lastY = y
            queueEvent {
                Input.setMouse(x, y)
                Lua.mouseMove(x.toDouble(), y.toDouble(), dx.toDouble(), dy.toDouble())
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        // BACK is the desktop Escape: the Lua scene stack handles it. Nothing
        // reports back whether it did, so a second press inside the window
        // quits — the usual Android affordance, and requestQuit() is real here
        // so a game can also offer its own exit.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.nanoTime()
            if (now - lastBackNanos < BACK_QUIT_NANOS) {
                activity.quit()
                return true
            }
            lastBackNanos = now
        }
        val name = Input.keyName(event)
        val ch = event.unicodeChar
        queueEvent {
            Input.setModifiers(event)
            Input.pressKey(name)
            Lua.keyDown(name)
            // Physical keyboards only: the soft keyboard routes text through
            // the IME bridge, which would otherwise double every character.
            if (ch != 0 && !Ime.isActive() && !event.isCtrlPressed && !event.isAltPressed) {
                val c = ch.toChar()
                if (!c.isISOControl()) Lua.textInput(c.toString())
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyUp(keyCode, event)
        }
        val name = Input.keyName(event)
        queueEvent {
            Input.setModifiers(event)
            Input.releaseKey(name)
            Lua.keyUp(name)
        }
        return true
    }
}
