package info.dynart.soob

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
        Log.i(TAG, "app: $name (id=$id, $orientation)")
    }

    /** The save file's name — the same stem the desktop build writes. */
    fun optFileName(): String = "$id.dat"
}
