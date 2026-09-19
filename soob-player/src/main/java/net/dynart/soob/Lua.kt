package net.dynart.soob

/**
 * The Lua VM, as seen from Kotlin — the twin of SOOB-Core-Web's `src/host/lua.ts`.
 *
 * Every function here is a native entry point in `bridge_jni.c`. All of them
 * must be called from the GL thread: the bridge caches the calling JNIEnv and
 * the bindings call straight back into [Host], which touches GL state.
 */
object Lua {

    init {
        System.loadLibrary("soob")
    }

    /** Create (or recreate) the lua_State, register the bindings, load options. */
    external fun newState(): Boolean

    external fun destroy()

    /** Run a `.lua` file from the game bundle, e.g. `"scripts/main.lua"`. */
    external fun doAsset(path: String): Boolean

    external fun doString(src: String): Boolean

    /** Run the asset manifest and populate the host registries (scriptLoadAssets). */
    external fun loadAssets(path: String): Boolean

    external fun callHook0(name: String)

    external fun update(dt: Double)

    external fun render()

    external fun mouseDown(x: Double, y: Double, b: Int)

    external fun mouseUp(x: Double, y: Double, b: Int)

    external fun mouseMove(x: Double, y: Double, dx: Double, dy: Double)

    external fun keyDown(name: String)

    external fun keyUp(name: String)

    external fun textInput(ch: String)

    /**
     * Expose a read-only `platform` global so game scripts can branch
     * cosmetically. Android is `"android"`; the web host sets `"web"`.
     */
    fun setPlatform(p: String) {
        doString("platform=\"$p\"")
    }
}
