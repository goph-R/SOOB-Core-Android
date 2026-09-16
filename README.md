# SOOB-Core-Android

Android player for **2D SOOB-Core games** — [Find5](https://github.com/goph-R/Find5)
first. It reimplements SOOB-Core's narrow C binding surface (the 25 bindings +
lifecycle hooks documented in
[`SOOB-Lua.md`](https://github.com/goph-R/SOOB-Core/blob/main/SOOB-Lua.md)) in
Kotlin against Android APIs, while the game's Lua scripts, the
`engine.scene/widget/animation/transition` modules, and `assets.lua` run
**unchanged**. Lua 5.1 itself is the vendored `lua-5.1.5` compiled by the NDK,
for exact behavioural parity with the desktop and web builds.

It is the sibling of [`SOOB-Core-Web`](https://github.com/goph-R/SOOB-Core-Web):
same contract, same host structure, different platform. 3D (SOOB-Engine) is out
of scope — this is the 2D core only.

## Layout

```
soob-player/     the reusable library — everything that isn't game identity
  cpp/           bridge_jni.c   SOOB-Core-Web's bridge.c with JNI host imports
                 CMakeLists.txt lua-5.1.5 (from ../SOOB-Core) -> libsoob.so
  Renderer.kt    GLES2 sprite batcher (the WebGL1 shaders, verbatim)
  BmFont.kt      AngelCode .fnt parsing, measuring, drawing
  Assets.kt      registries + AssetManager decode/upload, sliced per frame
  Audio.kt       SoundPool one-shots + dual-MediaPlayer music crossfade
  Input.kt       held keys / pointer state, Android key codes -> SDL names
  Ime.kt         hidden-EditText soft-keyboard bridge (imeShow / imeHide)
  AppInfo.kt     the game's identity, read from the bundle's app.lua
  GameView.kt    GLSurfaceView, the frame loop, touch/key marshalling
  SoobActivity.kt fullscreen/lifecycle/back-button host activity
  Host.kt        the object bridge_jni.c calls (the __SOOB twin)
  Lua.kt         the native entry points

app/             the thin per-game module: applicationId, versionCode, icon.
                 One line of identity; no Kotlin at all.
gradle/
  soobApp.gradle   everything an app module needs that isn't game identity
  syncGame.gradle  copies a game bundle in, and generates the label/colour
                   resources from its app.lua
```

`app/src/main/assets/game/` is generated, not committed.

## Build

Needs [`SOOB-Core`](https://github.com/goph-R/SOOB-Core) (for the vendored Lua
sources) and [`Find5`](https://github.com/goph-R/Find5) (the game bundle) as
siblings of this repo, plus the Android SDK 36 and NDK 29.

### Which game

`gradle.properties`'s `soobGame` names the game folder (`../Find5` by default).
Build a different one without editing anything:

```sh
./gradlew :app:assembleDebug -PsoobGame=../MyGame
```

The game supplies its identity in `app.lua` — `name` becomes the launcher
label, `background` the window and adaptive-icon colour (both generated into
`build/generated/soob/res` at build time), `id` the options filename, and
`orientation` the screen orientation. Only `applicationId` and the launcher
icon art stay in `app/`.

```sh
./gradlew :app:assembleDebug     # syncGame runs first, automatically
./gradlew :app:installDebug      # to a connected device
adb logcat -s SOOB               # print(), engine messages, load errors
```

For a signed release, put an untracked `keystore.properties` at the repo root:

```properties
storeFile=C:/keys/dynart.jks
storePassword=...
keyAlias=find5
keyPassword=...
```

```sh
./gradlew :app:bundleRelease     # AAB for Play; assembleRelease for an APK
```

Without that file the release build still assembles, unsigned.

## Testing without a device

The Kotlin half needs a phone, but the C half — the Lua state, the sandbox, the
asset searcher, the `assets.lua` walk, the argument marshalling, the hook
dispatch — is plain C behind the JNI function table, so it runs on the desktop:

```sh
cd tools/hosttest
./build.sh && ./host_test.exe ../../../Find5
```

`host_test.c` supplies a stub JNI table and a C mirror of `Host`, then boots the
real game bundle through the **unmodified** `bridge_jni.c` and asserts on what
the Lua did (assets registered, `require` resolved, the first scene drew, the
options chunk round-tripped). It builds with the portable MinGW that SOOB-Core
vendors for the Win10 build, or any `cc`.

```
ok   newState
I/SOOB: assets: 6 sound(s), 1 music, 8 texture(s), 2 font(s), 34 region(s)
ok   run main.lua
ok   require() resolved modules through the asset searcher
ok   the first scene draws
draws:   region=22 text=4 quad=0 ellipse=0 bg=4 blur=0
```

There are also JVM unit tests for BMFont: `./gradlew :soob-player:test`.

## Adding another game

Nothing in `soob-player` knows what Find5 is — the game contract is the Lua
bundle. To build one, just point `soobGame` at it (above). To *ship* one — its own
applicationId and icon — add a module beside `app/`:

```groovy
// app-mygame/build.gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

def appId = 'info.dynart.mygame'

android {
    namespace appId
    defaultConfig { applicationId appId; versionCode 1; versionName '0.1.0' }
}

apply from: rootProject.file('gradle/soobApp.gradle')
```

plus a manifest, `proguard-rules.pro`, `themes.xml` and the launcher icon —
and set `soobGame` for it. There is **no Kotlin**: `SoobActivity` is concrete,
so the manifest names `info.dynart.soob.SoobActivity` directly.

The game names itself in its own `app.lua` (`name` / `id` / `orientation` /
`background`), so the save file, orientation, title and window colour all
follow the bundle — see [`SOOB-Lua.md`](https://github.com/goph-R/SOOB-Core/blob/main/SOOB-Lua.md).

…or the same module living in the game's own repo, pulling the player in with
`includeBuild '../SOOB-Core-Android'` — the way CoolFox consumes LisaEngine.
Either way the library is consumed, never forked.

## Notes for the port

- **Threading.** The Lua VM and every binding run on the GLSurfaceView render
  thread. Touch/key/IME events arrive on the UI thread and are marshalled with
  `queueEvent`, so they land between frames — the native poll-then-update order.
- **Scripts are not files.** Android assets have no `fopen`, so instead of
  `luaL_loadfile` + `package.path` the bridge installs a `package.loaders`
  searcher that reads through `AssetManager` (`require "engine.scene"` →
  `scripts/engine/scene.lua`).
- **Premultiplied alpha.** Textures are decoded with `inPremultiplied = false`
  and uploaded from the raw buffer; the batcher blends `SRC_ALPHA,
  ONE_MINUS_SRC_ALPHA`. Uploading a premultiplied bitmap gives every sprite
  dark edges.
- **`noCompress`.** `wav`/`ogg`/`m4a` are stored uncompressed so `SoundPool`
  and `MediaPlayer` can seek an `AssetFileDescriptor`.
- **Saves are the desktop format.** `optSave` writes the same `return { … }`
  chunk to `<filesDir>/<id>.dat` — `id` from the bundle's `app.lua` — so a save
  moves between desktop and phone.
- **No FORTIFY for the vendored Lua.** A `TString` keeps its characters after
  the struct, so `__builtin_object_size(svalue(s))` is 0 and bionic's
  `__strchr_chk` aborts on `lgc.c`'s weak-table `strchr` during the first
  `luaL_openlibs`. The CMake file turns FORTIFY off for Lua only.
- **`requestQuit` is real here** (unlike on web) and BACK is the desktop
  Escape: it reaches Lua as `onKeyDown("escape")`, and a second press within
  two seconds finishes the activity.

## Status

M0–M4 written and building: debug APK, R8 release APK, AAB, arm64 libs
16 KB-aligned.

Verified: the C bridge boots Find5's real bundle in the host harness (assets,
`require`, hooks, draw calls, options round-trip) and passes 42 value-by-value
checks on the binding argument marshalling; the 14 JNI entry points in
`libsoob.so` match `Lua.kt` and all 34 host descriptors match the compiled
`Host` class; BMFont unit tests pass against Find5's real `.fnt`.

Runs on hardware — verified end to end on a Redmi (Android 13, armeabi-v7a):
the title screen renders, touch reaches the Lua hooks, a level plays, and audio
and the soft-keyboard bridge behave. Identity comes from the bundle: the player
logs `app: Find5 (id=find5, landscape)` at boot and an option toggle writes
`<filesDir>/find5.dat` as `return {["sound_on"]=false,}` — the desktop format,
under the name app.lua gave it. M0–M5 done; what remains is the real
launcher icon, the store listing, and whatever a second game asks for. See the
plan in
[`SOOB-Core/SOOB-Core-Android.md`](https://github.com/goph-R/SOOB-Core/blob/main/SOOB-Core-Android.md).
