package net.dynart.soob

import android.util.Log

/**
 * The game's identity, read from the bundle's `app.lua` — the port of
 * SOOB-Core's `app_info.h` and SOOB-Core-Web's `src/host/appinfo.ts`.
 *
 * A game names itself once and every host reads that same file, so nothing in
 * the player hardcodes "Find5": this is where the options filename and the
 * screen orientation come from.
 *
 * `app.lua` is a flat table of string fields by contract (see SOOB-Lua.md), so
 * a `key = "value"` scan is enough — no trip through the Lua VM, which matters
 * because this is needed before the VM exists (the bridge loads options while
 * creating the state).
 */
object AppInfo {

    private const val TAG = "SOOB"

    /** Generic on purpose: a bundle without app.lua still runs, it just isn't named. */
    var name = "SOOB"
        private set

    var id = "soob"
        private set

    var orientation = "landscape"
        private set

    var description = ""
        private set

    /** "#rrggbb" clear colour — the same value the desktop and web hosts use. */
    var background = "#14141f"
        private set

    // The same colour as GL-ready 0..1 components, parsed once at load(). The
    // renderer reads these every frame, so they must not allocate.
    var bgR = 0.08f
        private set
    var bgG = 0.08f
        private set
    var bgB = 0.12f
        private set

    private fun parseBackground() {
        val hex = background.removePrefix("#")
        try {
            bgR = hex.substring(0, 2).toInt(16) / 255f
            bgG = hex.substring(2, 4).toInt(16) / 255f
            bgB = hex.substring(4, 6).toInt(16) / 255f
        } catch (e: Exception) {
            // Malformed: keep the defaults rather than going black.
        }
    }

    private val FIELD = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun parse(src: String) {
        // Drop long comments first, then line comments, so a --[[ ]] block or a
        // commented-out field can't contribute a key.
        val body = src
            .replace(Regex("""--\[\[[\s\S]*?]]"""), "")
            .replace(Regex("""--[^\n]*"""), "")
        for (m in FIELD.findAll(body)) {
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            when (m.groupValues[1]) {
                "name" -> name = value
                "id" -> id = value
                "orientation" -> orientation = if (value == "portrait") "portrait" else "landscape"
                "description" -> description = value
                "background" -> if (value.matches(Regex("#[0-9a-fA-F]{6}"))) background = value
            }
        }
    }

    /** Read `app.lua` out of the bundle. Call before the Lua state is created. */
    fun load() {
        val text = Assets.bytes("app.lua")?.toString(Charsets.UTF_8)
        if (text == null) {
            Log.w(TAG, "app.lua missing — running unnamed")
            return
        }
        parse(text)
        parseBackground()
        Log.i(TAG, "app: $name (id=$id, $orientation, bg=$background)")
    }

    /** The save file's name — the same stem the desktop build writes. */
    fun optFileName(): String = "$id.dat"


}
