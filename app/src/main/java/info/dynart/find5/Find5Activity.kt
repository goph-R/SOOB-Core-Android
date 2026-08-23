package info.dynart.find5

import info.dynart.soob.SoobActivity

/**
 * Find5 on Android.
 *
 * The whole app: the player library does the work, this names the game. The
 * bundle itself (scripts + assets + assets.lua) is copied out of the sibling
 * Find5 checkout at build time by the syncGame Gradle task.
 */
class Find5Activity : SoobActivity() {

    /** Saved options land in `<filesDir>/find5.dat` — the desktop file format. */
    override val gameId = "find5"
}
