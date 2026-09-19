package net.dynart.soob

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File

/**
 * The player activity — the port of SOOB-Core-Web's `src/host/mobile.ts` plus
 * the boot half of `src/game/main.ts`.
 *
 * A game module subclasses this and adds nothing — identity comes from the
 * bundle's `app.lua` (see [AppInfo]), the rest (GL surface, Lua VM, assets,
 * audio, IME) is the library's job:
 *
 * ```kotlin
 * class Find5Activity : SoobActivity()
 * ```
 */
open class SoobActivity : Activity() {

    lateinit var gameView: GameView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Use the whole panel, cutout included; the safe insets are applied to
        // the GL viewport below so nothing important lands under a camera hole.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        Assets.attach(assets)
        AppInfo.load()          // names the save file and picks the orientation
        applyOrientation()
        Host.activity = this
        // Saved options land in <filesDir>/<id>.dat, in the same serialized
        // format the desktop build writes — so a save file is portable.
        Host.optFile = File(filesDir, AppInfo.optFileName())
        Audio.init(this)

        val root = FrameLayout(this)
        gameView = GameView(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        Ime.attach(this, root)
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val cutout = insets.displayCutout
            val l = cutout?.safeInsetLeft ?: 0
            val t = cutout?.safeInsetTop ?: 0
            val r = cutout?.safeInsetRight ?: 0
            val b = cutout?.safeInsetBottom ?: 0
            gameView.queueGl { Renderer.setInsets(l, t, r, b) }
            v.onApplyWindowInsets(insets)
        }

        applyImmersive()
        gameView.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
        Audio.onResume()
        applyImmersive()
    }

    override fun onPause() {
        super.onPause()
        Audio.onPause()
        gameView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Audio.release()
        Host.activity = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /**
     * The manifest's `screenOrientation` is the default; the bundle gets the
     * final say, so one player binary serves a landscape and a portrait game.
     */
    private fun applyOrientation() {
        requestedOrientation = if (AppInfo.orientation == "portrait") {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Run [block] on the GL thread, where the Lua VM lives. */
    fun queueGl(block: () -> Unit) = gameView.queueGl(block)

    /** What `requestQuit()` does here — unlike on web, it is real. */
    fun quit() = runOnUiThread { finish() }

    /** Hide the status and navigation bars, and keep them hidden on swipe. */
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
