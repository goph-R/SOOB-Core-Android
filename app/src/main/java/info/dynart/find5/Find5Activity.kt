package info.dynart.find5

import info.dynart.soob.SoobActivity

/**
 * Find5 on Android.
 *
 * The whole app: the player library does the work, and the game names itself in
 * its own `app.lua` — window title, save-file stem, orientation — so there is
 * nothing to override here. The bundle (scripts + assets + assets.lua +
 * app.lua) is copied out of the sibling Find5 checkout at build time by the
 * syncGame Gradle task.
 */
class Find5Activity : SoobActivity()
