/* bridge_jni.c — SOOB-Core-Android Lua/JNI glue.
 *
 * Compiled with the NDK together with the vendored lua-5.1.5 sources (see
 * CMakeLists.txt) into libsoob.so.
 *
 * It mirrors SOOB-Core/script.h: it creates a lua_State, sandboxes io/os,
 * registers the same 25-binding surface (SOOB-Lua.md) plus the ALIGN and FLIP
 * constants, and walks assets.lua exactly like scriptLoadAssets. It is the
 * sibling of SOOB-Core-Web's src/wasm/bridge.c — the arg-reading logic (option
 * tables, defaults) is the same port of the scr* wrappers in script.h, and the
 * only difference is the host import layer: JNI calls into the Kotlin
 * net.dynart.soob.Host object instead of EM_JS calls into globalThis.__SOOB.
 *
 * Geometry that needs texture sizes (drawRegion UV/dest math, ellipse
 * tessellation) is done host-side in Renderer/Host; here we only forward the
 * raw parameters. Numeric arg bundles travel through a shared g_args array the
 * host reads as a java.nio.DoubleBuffer over the same memory (the analog of
 * the web bridge's HEAPF64 view) — no per-call allocation.
 *
 * Threading: every entry point is called from the GL thread, and every host
 * call happens inside one of them, so caching the JNIEnv of the current call
 * in g_env is safe. Nothing here may be called from another thread.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define LOG_TAG "SOOB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define JNIFN(name) JNIEXPORT JNICALL Java_net_dynart_soob_Lua_##name

static lua_State *gL = 0;
static double g_args[64];   /* scratch bundle shared with the Kotlin host */

static JNIEnv *g_env = 0;   /* the current entry point's env (GL thread only) */
static jclass g_host = 0;   /* global ref to net.dynart.soob.Host */

/* ---------------------------------------------------------------------------
 * Host imports. Each is the JNI twin of one EM_JS stub in the web bridge.
 * Strings are passed as jstring and released immediately: a single onRender
 * can issue hundreds of draw calls inside one native frame, and leaked local
 * refs would overflow the local reference table long before it returns.
 * ------------------------------------------------------------------------- */
static struct {
    jmethodID setArgs, readAsset, log;
    jmethodID drawRegion, drawText, drawQuad, drawEllipse, drawBg, drawBlur;
    jmethodID viewW, viewH, regionW, regionH, regionSlice, textWidth;
    jmethodID keyDown, mouseX, mouseY, mouseBtn, keyMods;
    jmethodID soundPlay, musicPlay, musicStop, musicVolume;
    jmethodID showMessage, requestQuit, imeShow, imeHide;
    jmethodID optSave, optLoad;
    jmethodID regSound, regMusic, regTexture, regFont, regRegion;
} M;

#define HOST_V(m, ...)  (*g_env)->CallStaticVoidMethod(g_env, g_host, M.m, ##__VA_ARGS__)
#define HOST_D(m, ...)  (*g_env)->CallStaticDoubleMethod(g_env, g_host, M.m, ##__VA_ARGS__)
#define HOST_I(m, ...)  (*g_env)->CallStaticIntMethod(g_env, g_host, M.m, ##__VA_ARGS__)
#define HOST_Z(m, ...)  (*g_env)->CallStaticBooleanMethod(g_env, g_host, M.m, ##__VA_ARGS__)
#define HOST_O(m, ...)  (*g_env)->CallStaticObjectMethod(g_env, g_host, M.m, ##__VA_ARGS__)

static jstring jstr(const char *s) {
    return (*g_env)->NewStringUTF(g_env, s ? s : "");
}
static void jfree(jobject o) {
    if (o) (*g_env)->DeleteLocalRef(g_env, o);
}

static void h_log(const char *s) {
    LOGI("%s", s ? s : "(null)");
}

static void h_drawRegion(const char *name, double *a) {
    (void)a;                              /* the host reads g_args directly */
    jstring n = jstr(name);
    HOST_V(drawRegion, n);
    jfree(n);
}
static void h_drawText(const char *text, const char *font, double *a) {
    (void)a;
    jstring t = jstr(text), f = jstr(font);
    HOST_V(drawText, t, f);
    jfree(t); jfree(f);
}
static void h_drawQuad(double *a) { (void)a; HOST_V(drawQuad); }
static void h_drawEllipse(double *a) { (void)a; HOST_V(drawEllipse); }
static void h_drawBg(const char *n) {
    jstring s = jstr(n);
    HOST_V(drawBg, s);
    jfree(s);
}
static void h_drawBlur(const char *n, double w, double a) {
    jstring s = jstr(n);
    HOST_V(drawBlur, s, w, a);
    jfree(s);
}

static double h_viewW(void) { return HOST_D(viewW); }
static double h_viewH(void) { return HOST_D(viewH); }
static double h_regionW(const char *n) {
    jstring s = jstr(n);
    double v = HOST_D(regionW, s);
    jfree(s);
    return v;
}
static double h_regionH(const char *n) {
    jstring s = jstr(n);
    double v = HOST_D(regionH, s);
    jfree(s);
    return v;
}
static int h_regionSlice(const char *n, double *out) {
    (void)out;                            /* the host writes into g_args */
    jstring s = jstr(n);
    jboolean ok = HOST_Z(regionSlice, s);
    jfree(s);
    return ok ? 1 : 0;
}
static double h_textWidth(const char *t, const char *f, double scale) {
    jstring a = jstr(t), b = jstr(f);
    double v = HOST_D(textWidth, a, b, scale);
    jfree(a); jfree(b);
    return v;
}

static int h_keyDown(const char *n) {
    jstring s = jstr(n);
    jboolean v = HOST_Z(keyDown, s);
    jfree(s);
    return v ? 1 : 0;
}
static double h_mouseX(void) { return HOST_D(mouseX); }
static double h_mouseY(void) { return HOST_D(mouseY); }
static int h_mouseBtn(int b) { return HOST_Z(mouseBtn, (jint)b) ? 1 : 0; }
static int h_keyMods(void) { return HOST_I(keyMods); }

static void h_soundPlay(const char *n) {
    jstring s = jstr(n);
    HOST_V(soundPlay, s);
    jfree(s);
}
static void h_musicPlay(const char *n, double f, int loop) {
    jstring s = jstr(n);
    HOST_V(musicPlay, s, f, (jboolean)(loop ? JNI_TRUE : JNI_FALSE));
    jfree(s);
}
static void h_musicStop(double f) { HOST_V(musicStop, f); }
static void h_musicVolume(double g) { HOST_V(musicVolume, g); }
static void h_showMessage(const char *t, double s) {
    jstring j = jstr(t);
    HOST_V(showMessage, j, s);
    jfree(j);
}
static void h_requestQuit(void) { HOST_V(requestQuit); }
static void h_imeShow(double x, double y, double w, double h) { HOST_V(imeShow, x, y, w, h); }
static void h_imeHide(void) { HOST_V(imeHide); }

static void h_optSave(const char *s) {
    jstring j = jstr(s);
    HOST_V(optSave, j);
    jfree(j);
}
/* Returns a malloc'd copy the caller frees, or NULL when nothing is saved. */
static char *h_optLoad(void) {
    jstring j = (jstring)HOST_O(optLoad);
    if (!j) return 0;
    const char *c = (*g_env)->GetStringUTFChars(g_env, j, 0);
    char *out = c ? strdup(c) : 0;
    if (c) (*g_env)->ReleaseStringUTFChars(g_env, j, c);
    jfree(j);
    return out;
}

static void h_regSound(const char *n, const char *p) {
    jstring a = jstr(n), b = jstr(p);
    HOST_V(regSound, a, b);
    jfree(a); jfree(b);
}
static void h_regMusic(const char *n, const char *p) {
    jstring a = jstr(n), b = jstr(p);
    HOST_V(regMusic, a, b);
    jfree(a); jfree(b);
}
static void h_regTexture(const char *n, const char *p) {
    jstring a = jstr(n), b = jstr(p);
    HOST_V(regTexture, a, b);
    jfree(a); jfree(b);
}
static void h_regFont(const char *n, const char *p) {
    jstring a = jstr(n), b = jstr(p);
    HOST_V(regFont, a, b);
    jfree(a); jfree(b);
}
static void h_regRegion(const char *n, const char *tex, double *a) {
    (void)a;
    jstring x = jstr(n), y = jstr(tex);
    HOST_V(regRegion, x, y);
    jfree(x); jfree(y);
}

/* Read one file from the game bundle (path relative to the bundle root, e.g.
   "scripts/main.lua"). Returns malloc'd bytes + length, or NULL if absent. */
static char *h_readAsset(const char *path, size_t *outLen) {
    jstring p = jstr(path);
    jbyteArray arr = (jbyteArray)HOST_O(readAsset, p);
    jfree(p);
    if (!arr) return 0;
    jsize n = (*g_env)->GetArrayLength(g_env, arr);
    char *buf = (char *)malloc((size_t)n + 1);
    if (buf) {
        (*g_env)->GetByteArrayRegion(g_env, arr, 0, n, (jbyte *)buf);
        buf[n] = '\0';
        *outLen = (size_t)n;
    }
    jfree(arr);
    return buf;
}

/* ---- option-table helpers (ported from script.h scrOptfield*) ---- */
static double optNum(lua_State *L, int idx, const char *k, double def) {
    lua_getfield(L, idx, k);
    double v = lua_isnil(L, -1) ? def : lua_tonumber(L, -1);
    lua_pop(L, 1);
    return v;
}
static int optInt(lua_State *L, int idx, const char *k, int def) {
    lua_getfield(L, idx, k);
    int v = lua_isnil(L, -1) ? def : (int)lua_tointeger(L, -1);
    lua_pop(L, 1);
    return v;
}
static const char *optStr(lua_State *L, int idx, const char *k, const char *def) {
    lua_getfield(L, idx, k);
    const char *v = lua_isstring(L, -1) ? lua_tostring(L, -1) : def;
    lua_pop(L, 1);
    return v;
}
static int optOvr(lua_State *L, int idx, const char *k, double *out) {
    lua_getfield(L, idx, k);
    int has = 0;
    if (!lua_isnil(L, -1)) { *out = lua_tonumber(L, -1); has = 1; }
    lua_pop(L, 1);
    return has;
}
static void optColor(lua_State *L, int idx, double *r, double *g, double *b, double *a) {
    lua_getfield(L, idx, "color");
    if (lua_istable(L, -1)) {
        lua_rawgeti(L, -1, 1); if (!lua_isnil(L, -1)) *r = lua_tonumber(L, -1); lua_pop(L, 1);
        lua_rawgeti(L, -1, 2); if (!lua_isnil(L, -1)) *g = lua_tonumber(L, -1); lua_pop(L, 1);
        lua_rawgeti(L, -1, 3); if (!lua_isnil(L, -1)) *b = lua_tonumber(L, -1); lua_pop(L, 1);
        lua_rawgeti(L, -1, 4); if (!lua_isnil(L, -1)) *a = lua_tonumber(L, -1); lua_pop(L, 1);
    }
    lua_pop(L, 1);
    lua_getfield(L, idx, "alpha");
    if (!lua_isnil(L, -1)) *a = lua_tonumber(L, -1);
    lua_pop(L, 1);
}

/* ---- rendering bindings ---- */
static int scrDrawRegion(lua_State *L) {
    const char *name = luaL_checkstring(L, 1);
    double x = luaL_checknumber(L, 2), y = luaL_checknumber(L, 3);
    double align = 0, flip = 0, fillX = 1, fillY = 1, sx = 1, sy = 1, rot = 0;
    double r = 1, g = 1, b = 1, a = 1;
    int hsx = 0, hsy = 0, hsw = 0, hsh = 0, hdw = 0, hdh = 0;
    double srcX = 0, srcY = 0, srcW = 0, srcH = 0, dstW = 0, dstH = 0;

    if (lua_istable(L, 4)) {
        align = optInt(L, 4, "align", 0);
        flip  = optInt(L, 4, "flip", 0);
        fillX = optNum(L, 4, "fillX", 1);
        fillY = optNum(L, 4, "fillY", 1);
        double uni = optNum(L, 4, "scale", 1);
        sx = optNum(L, 4, "scaleX", uni);
        sy = optNum(L, 4, "scaleY", uni);
        rot = optNum(L, 4, "rotation", 0);
        optColor(L, 4, &r, &g, &b, &a);
        hsx = optOvr(L, 4, "srcX", &srcX);
        hsy = optOvr(L, 4, "srcY", &srcY);
        hsw = optOvr(L, 4, "srcW", &srcW);
        hsh = optOvr(L, 4, "srcH", &srcH);
        hdw = optOvr(L, 4, "dstW", &dstW);
        hdh = optOvr(L, 4, "dstH", &dstH);
    } else {
        align = luaL_optinteger(L, 4, 0);
        flip  = luaL_optinteger(L, 5, 0);
        fillX = luaL_optnumber(L, 6, 1);
        fillY = luaL_optnumber(L, 7, 1);
    }

    double *A = g_args;
    A[0] = x; A[1] = y; A[2] = align; A[3] = flip; A[4] = fillX; A[5] = fillY;
    A[6] = sx; A[7] = sy; A[8] = rot; A[9] = r; A[10] = g; A[11] = b; A[12] = a;
    A[13] = hsx; A[14] = srcX; A[15] = hsy; A[16] = srcY;
    A[17] = hsw; A[18] = srcW; A[19] = hsh; A[20] = srcH;
    A[21] = hdw; A[22] = dstW; A[23] = hdh; A[24] = dstH;
    h_drawRegion(name, g_args);
    return 0;
}

static int scrDrawText(lua_State *L) {
    const char *text = luaL_checkstring(L, 1);
    double x = luaL_checknumber(L, 2), y = luaL_checknumber(L, 3);
    double scale = 1, align = 0, r = 1, g = 1, b = 1, a = 1;
    const char *font = "";
    if (lua_istable(L, 4)) {
        scale = optNum(L, 4, "scale", 1);
        font  = optStr(L, 4, "font", "");
        align = optInt(L, 4, "align", 0);
        optColor(L, 4, &r, &g, &b, &a);
    } else {
        scale = luaL_optnumber(L, 4, 1);
        font  = lua_isstring(L, 5) ? lua_tostring(L, 5) : "";
    }
    double *A = g_args;
    A[0] = x; A[1] = y; A[2] = scale; A[3] = align; A[4] = r; A[5] = g; A[6] = b; A[7] = a;
    h_drawText(text, font, g_args);
    return 0;
}

static int scrDrawQuad(lua_State *L) {
    double x = luaL_checknumber(L, 1), y = luaL_checknumber(L, 2);
    double w = luaL_checknumber(L, 3), h = luaL_checknumber(L, 4);
    double r = 1, g = 1, b = 1, a = 1;
    if (lua_istable(L, 5)) optColor(L, 5, &r, &g, &b, &a);
    double *A = g_args;
    A[0] = x; A[1] = y; A[2] = w; A[3] = h; A[4] = r; A[5] = g; A[6] = b; A[7] = a;
    h_drawQuad(g_args);
    return 0;
}

static int scrDrawEllipse(lua_State *L) {
    double cx = luaL_checknumber(L, 1), cy = luaL_checknumber(L, 2);
    double rx = luaL_checknumber(L, 3), ry = luaL_checknumber(L, 4);
    double start = 0, finish = 1, seg = 64, th = 2, r = 1, g = 1, b = 1, a = 1;
    if (lua_istable(L, 5)) {
        start  = optNum(L, 5, "start", 0);
        finish = optNum(L, 5, "finish", 1);
        seg    = optInt(L, 5, "segments", 64);
        th     = optNum(L, 5, "thickness", 2);
        optColor(L, 5, &r, &g, &b, &a);
    }
    double *A = g_args;
    A[0] = cx; A[1] = cy; A[2] = rx; A[3] = ry; A[4] = start; A[5] = finish;
    A[6] = seg; A[7] = th; A[8] = r; A[9] = g; A[10] = b; A[11] = a;
    h_drawEllipse(g_args);
    return 0;
}

static int scrDrawBg(lua_State *L) { h_drawBg(luaL_checkstring(L, 1)); return 0; }
static int scrDrawBlur(lua_State *L) {
    const char *n = luaL_checkstring(L, 1);
    double w = 16, a = 0.6;
    if (lua_istable(L, 2)) { w = optInt(L, 2, "width", 16); a = optNum(L, 2, "alpha", 0.6); }
    h_drawBlur(n, w, a);
    return 0;
}

/* ---- queries ---- */
static int scrViewSize(lua_State *L) {
    lua_pushnumber(L, h_viewW());
    lua_pushnumber(L, h_viewH());
    return 2;
}
static int scrRegionSize(lua_State *L) {
    const char *n = luaL_checkstring(L, 1);
    double w = h_regionW(n);
    if (w < 0) return 0;
    lua_pushinteger(L, (int)w);
    lua_pushinteger(L, (int)h_regionH(n));
    return 2;
}
static int scrRegionSlice(lua_State *L) {
    const char *n = luaL_checkstring(L, 1);
    if (!h_regionSlice(n, g_args)) return 0;
    for (int i = 0; i < 6; i++) lua_pushinteger(L, (int)g_args[i]);
    return 6;
}
static int scrTextWidth(lua_State *L) {
    const char *t = luaL_checkstring(L, 1);
    double scale = luaL_optnumber(L, 2, 1);
    const char *f = lua_isstring(L, 3) ? lua_tostring(L, 3) : "";
    lua_pushnumber(L, h_textWidth(t, f, scale));
    return 1;
}

/* ---- input polling ---- */
static int scrKeyDown(lua_State *L) { lua_pushboolean(L, h_keyDown(luaL_checkstring(L, 1))); return 1; }
static int scrMousePos(lua_State *L) { lua_pushnumber(L, h_mouseX()); lua_pushnumber(L, h_mouseY()); return 2; }
static int scrMouseDown(lua_State *L) { lua_pushboolean(L, h_mouseBtn((int)luaL_checkinteger(L, 1))); return 1; }
static int scrKeyModifiers(lua_State *L) {
    int m = h_keyMods();
    lua_pushboolean(L, m & 1);
    lua_pushboolean(L, m & 2);
    lua_pushboolean(L, m & 4);
    return 3;
}

/* ---- audio / misc ---- */
static int scrSoundPlay(lua_State *L) { h_soundPlay(luaL_checkstring(L, 1)); return 0; }
static int scrMusicPlay(lua_State *L) {
    const char *n = luaL_checkstring(L, 1);
    double fade = luaL_optnumber(L, 2, 0.5);
    int loop = lua_isnoneornil(L, 3) ? 1 : lua_toboolean(L, 3);
    h_musicPlay(n, fade, loop);
    return 0;
}
static int scrMusicStop(lua_State *L) { h_musicStop(luaL_optnumber(L, 1, 0.5)); return 0; }
static int scrMusicVolume(lua_State *L) { h_musicVolume(luaL_checknumber(L, 1)); return 0; }
static int scrUiShowMessage(lua_State *L) {
    const char *t = luaL_checkstring(L, 1);
    double s = luaL_optnumber(L, 2, 3.0);
    h_showMessage(t, s);
    return 0;
}
static int scrRequestQuit(lua_State *L) { (void)L; h_requestQuit(); return 0; }
static int scrImeShow(lua_State *L) {
    h_imeShow(luaL_checknumber(L, 1), luaL_checknumber(L, 2),
              luaL_checknumber(L, 3), luaL_checknumber(L, 4));
    return 0;
}
static int scrImeHide(lua_State *L) { (void)L; h_imeHide(); return 0; }

/* ---- options ----
 * Kept as a Lua table in the registry so values (incl. nested tables) round-
 * trip with zero marshalling; optSave serializes it to the same `return {...}`
 * chunk the desktop build writes, and the host puts that in filesDir. */
static void pushOpts(lua_State *L) {
    lua_getfield(L, LUA_REGISTRYINDEX, "__opts");
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        lua_newtable(L);
        lua_pushvalue(L, -1);
        lua_setfield(L, LUA_REGISTRYINDEX, "__opts");
    }
}
static int scrOptSet(lua_State *L) {
    const char *k = luaL_checkstring(L, 1);
    pushOpts(L);
    lua_pushvalue(L, 2);
    lua_setfield(L, -2, k);
    lua_pop(L, 1);
    return 0;
}
static int scrOptGet(lua_State *L) {
    const char *k = luaL_checkstring(L, 1);
    pushOpts(L);
    lua_getfield(L, -1, k);
    if (lua_isnil(L, -1)) {
        lua_pop(L, 1);
        if (lua_isnoneornil(L, 2)) lua_pushnil(L); else lua_pushvalue(L, 2);
    }
    return 1;   /* top is the value; the opts table beneath is ignored */
}
static int scrOptSave(lua_State *L) {
    lua_getglobal(L, "__soobSerialize");
    if (!lua_isfunction(L, -1)) { lua_pop(L, 1); lua_pushboolean(L, 0); return 1; }
    pushOpts(L);
    if (lua_pcall(L, 1, 1, 0)) { h_log(lua_tostring(L, -1)); lua_pop(L, 1); lua_pushboolean(L, 0); return 1; }
    const char *str = lua_tostring(L, -1);
    h_optSave(str ? str : "return {}");
    lua_pop(L, 1);
    lua_pushboolean(L, 1);
    return 1;
}
static void loadOpts(lua_State *L) {
    char *str = h_optLoad();
    if (!str) return;
    if (luaL_loadstring(L, str) == 0 && lua_pcall(L, 0, 1, 0) == 0 && lua_istable(L, -1)) {
        lua_setfield(L, LUA_REGISTRYINDEX, "__opts");
    } else {
        lua_pop(L, 1);
    }
    free(str);
}
static int scrOptLoad(lua_State *L) { loadOpts(L); lua_pushboolean(L, 1); return 1; }

/* ---- print -> Logcat (tab-joined, like stock print) ---- */
static int scrPrint(lua_State *L) {
    int n = lua_gettop(L), pos = 0;
    char buf[512];
    lua_getglobal(L, "tostring");
    for (int i = 1; i <= n; i++) {
        lua_pushvalue(L, -1);
        lua_pushvalue(L, i);
        lua_call(L, 1, 1);
        const char *s = lua_tostring(L, -1);
        if (!s) s = "(nil)";
        if (pos > 0 && pos < (int)sizeof(buf) - 1) buf[pos++] = '\t';
        while (*s && pos < (int)sizeof(buf) - 1) buf[pos++] = *s++;
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    buf[pos] = '\0';
    h_log(buf);
    return 0;
}

/* ---- error traceback (ported from script.h) ---- */
static int scrTraceback(lua_State *L) {
    if (!lua_isstring(L, 1)) return 1;
    lua_getfield(L, LUA_GLOBALSINDEX, "debug");
    if (!lua_istable(L, -1)) { lua_pop(L, 1); return 1; }
    lua_getfield(L, -1, "traceback");
    if (!lua_isfunction(L, -1)) { lua_pop(L, 2); return 1; }
    lua_pushvalue(L, 1);
    lua_pushinteger(L, 2);
    lua_call(L, 2, 1);
    return 1;
}

/* ---------------------------------------------------------------------------
 * Script loading. Android assets are not a filesystem (no fopen), so instead
 * of the desktop/web luaL_loadfile + package.path we read bytes through the
 * host's AssetManager and install a package.loaders searcher that does the
 * same for require(). Paths are relative to the game bundle root, so
 * require "engine.scene" -> scripts/engine/scene.lua.
 * ------------------------------------------------------------------------- */
static int loadAssetChunk(lua_State *L, const char *path) {
    size_t len = 0;
    char *buf = h_readAsset(path, &len);
    if (!buf) return -1;                        /* not found */
    char chunk[256];
    snprintf(chunk, sizeof chunk, "@%s", path);
    int rc = luaL_loadbuffer(L, buf, len, chunk);
    free(buf);
    return rc;                                  /* 0 = ok, else error on stack */
}

static int assetSearcher(lua_State *L) {
    const char *mod = luaL_checkstring(L, 1);
    char rel[256], path[288];
    size_t i = 0;
    for (; mod[i] && i < sizeof(rel) - 1; i++) rel[i] = (mod[i] == '.') ? '/' : mod[i];
    rel[i] = '\0';

    snprintf(path, sizeof path, "scripts/%s.lua", rel);
    int rc = loadAssetChunk(L, path);
    if (rc == -1) {
        snprintf(path, sizeof path, "scripts/%s/init.lua", rel);
        rc = loadAssetChunk(L, path);
    }
    if (rc == -1) {
        lua_pushfstring(L, "\n\tno asset scripts/%s.lua", rel);
        return 1;
    }
    if (rc != 0) return lua_error(L);           /* syntax error in the module */
    return 1;                                   /* the loaded chunk */
}

/* ---- sandbox + registration ---- */
static void scriptSandbox(lua_State *L) {
    /* The asset searcher must be installed before load/loadstring are nil'd —
       table.insert and the C-level luaL_dostring below are unaffected either
       way, but the searcher itself is a C function, so nothing here re-enables
       arbitrary file loading. package.cpath is emptied: require never reaches
       a native lib. */
    lua_register(L, "__soobAssetSearcher", assetSearcher);
    luaL_dostring(L,
        "package.path=''\n"
        "package.cpath=''\n"
        "table.insert(package.loaders, 2, __soobAssetSearcher)\n"
        "__soobAssetSearcher=nil\n");

    const char *banned[] = { "os", "io", "dofile", "loadfile", "load", "loadstring", "module", 0 };
    for (int i = 0; banned[i]; i++) { lua_pushnil(L); lua_setglobal(L, banned[i]); }
}

static void setconst(lua_State *L, const char *n, int v) { lua_pushinteger(L, v); lua_setglobal(L, n); }

static void registerAll(lua_State *L) {
    lua_register(L, "uiShowMessage", scrUiShowMessage);
    lua_register(L, "soundPlay", scrSoundPlay);
    lua_register(L, "musicPlay", scrMusicPlay);
    lua_register(L, "musicStop", scrMusicStop);
    lua_register(L, "musicVolume", scrMusicVolume);
    lua_register(L, "keyDown", scrKeyDown);
    lua_register(L, "mousePos", scrMousePos);
    lua_register(L, "mouseDown", scrMouseDown);
    lua_register(L, "keyModifiers", scrKeyModifiers);
    lua_register(L, "drawRegion", scrDrawRegion);
    lua_register(L, "drawText", scrDrawText);
    lua_register(L, "textWidth", scrTextWidth);
    lua_register(L, "drawEllipse", scrDrawEllipse);
    lua_register(L, "drawQuad", scrDrawQuad);
    lua_register(L, "drawBg", scrDrawBg);
    lua_register(L, "drawBlur", scrDrawBlur);
    lua_register(L, "viewSize", scrViewSize);
    lua_register(L, "regionSlice", scrRegionSlice);
    lua_register(L, "regionSize", scrRegionSize);
    lua_register(L, "optSet", scrOptSet);
    lua_register(L, "optGet", scrOptGet);
    lua_register(L, "optSave", scrOptSave);
    lua_register(L, "optLoad", scrOptLoad);
    lua_register(L, "requestQuit", scrRequestQuit);
    lua_register(L, "imeShow", scrImeShow);
    lua_register(L, "imeHide", scrImeHide);
    lua_register(L, "print", scrPrint);

    setconst(L, "ALIGN_LEFT", 1);   setconst(L, "ALIGN_CENTER", 2);  setconst(L, "ALIGN_RIGHT", 4);
    setconst(L, "ALIGN_TOP", 8);    setconst(L, "ALIGN_MIDDLE", 16); setconst(L, "ALIGN_BOTTOM", 32);
    setconst(L, "FLIP_H", 1);       setconst(L, "FLIP_V", 2);

    /* Options serializer used by optSave — produces a `return { ... }` chunk in
       the same format as the desktop find5.dat (array part + bracketed keys,
       %q-escaped strings, depth-capped). string/math/table are not sandboxed. */
    luaL_dostring(L,
        "function __soobSerialize(opts)\n"
        "  local function s(v, d)\n"
        "    local t = type(v)\n"
        "    if t == 'number' then return tostring(v)\n"
        "    elseif t == 'boolean' then return v and 'true' or 'false'\n"
        "    elseif t == 'string' then return string.format('%q', v)\n"
        "    elseif t == 'table' and d < 16 then\n"
        "      local o = {'{'}\n"
        "      local n = #v\n"
        "      for i = 1, n do o[#o+1] = s(v[i], d+1) .. ',' end\n"
        "      for k, val in pairs(v) do\n"
        "        local isArr = (type(k) == 'number' and k == math.floor(k) and k >= 1 and k <= n)\n"
        "        if not isArr then\n"
        "          local ks\n"
        "          if type(k) == 'string' then ks = '[' .. string.format('%q', k) .. ']'\n"
        "          elseif type(k) == 'number' then ks = '[' .. tostring(k) .. ']' end\n"
        "          if ks then o[#o+1] = ks .. '=' .. s(val, d+1) .. ',' end\n"
        "        end\n"
        "      end\n"
        "      o[#o+1] = '}'\n"
        "      return table.concat(o)\n"
        "    else return 'nil' end\n"
        "  end\n"
        "  return 'return ' .. s(opts or {}, 0)\n"
        "end\n");
}

/* ---- asset manifest walk (ported from scriptLoadAssets) ---- */
static int walkStrings(lua_State *L, int t, const char *field,
                       void (*reg)(const char *, const char *)) {
    int count = 0;
    lua_getfield(L, t, field);
    if (lua_istable(L, -1)) {
        int st = lua_gettop(L);
        lua_pushnil(L);
        while (lua_next(L, st)) {
            const char *k = lua_tostring(L, -2);
            if (k && lua_isstring(L, -1)) { reg(k, lua_tostring(L, -1)); count++; }
            else if (k && lua_istable(L, -1)) {           /* random-pick group */
                int vt = lua_gettop(L), n = (int)lua_objlen(L, vt);
                for (int i = 1; i <= n; i++) {
                    lua_rawgeti(L, vt, i);
                    if (lua_isstring(L, -1)) { reg(k, lua_tostring(L, -1)); count++; }
                    lua_pop(L, 1);
                }
            }
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);
    return count;
}

/* ---------------------------------------------------------------------------
 * JNI plumbing.
 * ------------------------------------------------------------------------- */
static int cacheHost(JNIEnv *env) {
    if (g_host) return 1;
    jclass cls = (*env)->FindClass(env, "net/dynart/soob/Host");
    if (!cls) { LOGW("Host class not found"); return 0; }
    g_host = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);

#define MID(name, sig) \
    M.name = (*env)->GetStaticMethodID(env, g_host, #name, sig); \
    if (!M.name) { LOGW("missing Host.%s%s", #name, sig); return 0; }

    MID(setArgs,   "(Ljava/nio/ByteBuffer;)V")
    MID(readAsset, "(Ljava/lang/String;)[B")
    MID(drawRegion, "(Ljava/lang/String;)V")
    MID(drawText,  "(Ljava/lang/String;Ljava/lang/String;)V")
    MID(drawQuad,  "()V")
    MID(drawEllipse, "()V")
    MID(drawBg,    "(Ljava/lang/String;)V")
    MID(drawBlur,  "(Ljava/lang/String;DD)V")
    MID(viewW,     "()D")
    MID(viewH,     "()D")
    MID(regionW,   "(Ljava/lang/String;)D")
    MID(regionH,   "(Ljava/lang/String;)D")
    MID(regionSlice, "(Ljava/lang/String;)Z")
    MID(textWidth, "(Ljava/lang/String;Ljava/lang/String;D)D")
    MID(keyDown,   "(Ljava/lang/String;)Z")
    MID(mouseX,    "()D")
    MID(mouseY,    "()D")
    MID(mouseBtn,  "(I)Z")
    MID(keyMods,   "()I")
    MID(soundPlay, "(Ljava/lang/String;)V")
    MID(musicPlay, "(Ljava/lang/String;DZ)V")
    MID(musicStop, "(D)V")
    MID(musicVolume, "(D)V")
    MID(showMessage, "(Ljava/lang/String;D)V")
    MID(requestQuit, "()V")
    MID(imeShow,   "(DDDD)V")
    MID(imeHide,   "()V")
    MID(optSave,   "(Ljava/lang/String;)V")
    MID(optLoad,   "()Ljava/lang/String;")
    MID(regSound,  "(Ljava/lang/String;Ljava/lang/String;)V")
    MID(regMusic,  "(Ljava/lang/String;Ljava/lang/String;)V")
    MID(regTexture, "(Ljava/lang/String;Ljava/lang/String;)V")
    MID(regFont,   "(Ljava/lang/String;Ljava/lang/String;)V")
    MID(regRegion, "(Ljava/lang/String;Ljava/lang/String;)V")
#undef MID

    /* Hand the host a zero-copy view of the arg scratch (the HEAPF64 twin). */
    jobject buf = (*env)->NewDirectByteBuffer(env, g_args, (jlong)sizeof g_args);
    (*env)->CallStaticVoidMethod(env, g_host, M.setArgs, buf);
    (*env)->DeleteLocalRef(env, buf);
    return 1;
}

JNIEXPORT jboolean JNICALL Java_net_dynart_soob_Lua_newState(JNIEnv *env, jobject self) {
    (void)self;
    g_env = env;
    if (!cacheHost(env)) return JNI_FALSE;
    if (gL) lua_close(gL);
    gL = luaL_newstate();
    luaL_openlibs(gL);
    scriptSandbox(gL);
    registerAll(gL);
    loadOpts(gL);   /* auto-load persisted options (mirrors desktop scriptInit) */
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_destroy(JNIEnv *env, jobject self) {
    (void)self;
    g_env = env;
    if (gL) { lua_close(gL); gL = 0; }
}

JNIEXPORT jboolean JNICALL Java_net_dynart_soob_Lua_doAsset(JNIEnv *env, jobject self, jstring jpath) {
    (void)self;
    g_env = env;
    lua_State *L = gL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    lua_pushcfunction(L, scrTraceback);
    int tb = lua_gettop(L);
    int rc = loadAssetChunk(L, path);
    if (rc == -1) { LOGW("doAsset: %s not found in the bundle", path); }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc == -1) { lua_pop(L, 1); return JNI_FALSE; }
    if (rc != 0) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    if (lua_pcall(L, 0, 0, tb)) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    lua_pop(L, 1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_net_dynart_soob_Lua_doString(JNIEnv *env, jobject self, jstring jsrc) {
    (void)self;
    g_env = env;
    lua_State *L = gL;
    const char *src = (*env)->GetStringUTFChars(env, jsrc, 0);
    lua_pushcfunction(L, scrTraceback);
    int tb = lua_gettop(L);
    int bad = luaL_loadstring(L, src);
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    if (bad) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    if (lua_pcall(L, 0, 0, tb)) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    lua_pop(L, 1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_net_dynart_soob_Lua_loadAssets(JNIEnv *env, jobject self, jstring jpath) {
    (void)self;
    g_env = env;
    lua_State *L = gL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    lua_pushcfunction(L, scrTraceback);
    int tb = lua_gettop(L);
    int rc = loadAssetChunk(L, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc == -1) { LOGW("loadAssets: manifest not found"); lua_pop(L, 1); return JNI_FALSE; }
    if (rc != 0) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    if (lua_pcall(L, 0, 1, tb)) { h_log(lua_tostring(L, -1)); lua_pop(L, 2); return JNI_FALSE; }
    if (!lua_istable(L, -1)) { h_log("assets.lua must return a table"); lua_pop(L, 2); return JNI_FALSE; }

    int t = lua_gettop(L);
    int ns = walkStrings(L, t, "sounds", h_regSound);
    int nm = walkStrings(L, t, "music", h_regMusic);
    int nt = walkStrings(L, t, "textures", h_regTexture);
    int nf = walkStrings(L, t, "fonts", h_regFont);
    int nr = 0;

    lua_getfield(L, t, "regions");
    if (lua_istable(L, -1)) {
        int rt = lua_gettop(L);
        lua_pushnil(L);
        while (lua_next(L, rt)) {
            const char *k = lua_tostring(L, -2);
            if (k && lua_istable(L, -1)) {
                int vt = lua_gettop(L);
                lua_getfield(L, vt, "tex");
                const char *tex = lua_isstring(L, -1) ? lua_tostring(L, -1) : "";
                double *A = g_args;
                A[0] = optNum(L, vt, "x", 0);
                A[1] = optNum(L, vt, "y", 0);
                A[2] = optNum(L, vt, "w", 0);
                A[3] = optNum(L, vt, "h", 0);
                A[4] = 0; A[5] = A[6] = A[7] = A[8] = 0;
                lua_getfield(L, vt, "slice");
                if (lua_istable(L, -1)) {
                    int sl = lua_gettop(L);
                    A[4] = 1;
                    A[5] = optNum(L, sl, "x1", 0);
                    A[6] = optNum(L, sl, "x2", 0);
                    A[7] = optNum(L, sl, "y1", 0);
                    A[8] = optNum(L, sl, "y2", 0);
                }
                lua_pop(L, 1);            /* slice */
                h_regRegion(k, tex, g_args);
                nr++;
                lua_pop(L, 1);            /* tex */
            }
            lua_pop(L, 1);                /* value */
        }
    }
    lua_pop(L, 1);                        /* regions */

    lua_pop(L, 2);                        /* assets table + traceback */
    char buf[192];
    snprintf(buf, sizeof buf,
             "assets: %d sound(s), %d music, %d texture(s), %d font(s), %d region(s)",
             ns, nm, nt, nf, nr);
    h_log(buf);
    return JNI_TRUE;
}

/* ---- hook dispatch ---- */
static int beginHook(const char *name) {           /* returns traceback index, or 0 */
    lua_pushcfunction(gL, scrTraceback);
    int tb = lua_gettop(gL);
    lua_getglobal(gL, name);
    if (!lua_isfunction(gL, -1)) { lua_pop(gL, 2); return 0; }
    return tb;
}
static void endHook(int tb, int nargs) {
    if (lua_pcall(gL, nargs, 0, tb)) { h_log(lua_tostring(gL, -1)); lua_pop(gL, 1); }
    lua_remove(gL, tb);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_callHook0(JNIEnv *env, jobject self, jstring jname) {
    (void)self;
    g_env = env;
    if (!gL) return;
    const char *name = (*env)->GetStringUTFChars(env, jname, 0);
    int tb = beginHook(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (tb) endHook(tb, 0);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_update(JNIEnv *env, jobject self, jdouble dt) {
    (void)self;
    g_env = env;
    if (!gL) return;
    int tb = beginHook("onUpdate");
    if (!tb) return;
    lua_pushnumber(gL, dt);
    endHook(tb, 1);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_render(JNIEnv *env, jobject self) {
    (void)self;
    g_env = env;
    if (!gL) return;
    int tb = beginHook("onRender");
    if (!tb) return;
    endHook(tb, 0);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_mouseDown(JNIEnv *env, jobject self,
                                                           jdouble x, jdouble y, jint b) {
    (void)self;
    g_env = env;
    if (!gL) return;
    int tb = beginHook("onMouseDown");
    if (!tb) return;
    lua_pushnumber(gL, x); lua_pushnumber(gL, y); lua_pushinteger(gL, b);
    endHook(tb, 3);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_mouseUp(JNIEnv *env, jobject self,
                                                         jdouble x, jdouble y, jint b) {
    (void)self;
    g_env = env;
    if (!gL) return;
    int tb = beginHook("onMouseUp");
    if (!tb) return;
    lua_pushnumber(gL, x); lua_pushnumber(gL, y); lua_pushinteger(gL, b);
    endHook(tb, 3);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_mouseMove(JNIEnv *env, jobject self,
                                                           jdouble x, jdouble y,
                                                           jdouble dx, jdouble dy) {
    (void)self;
    g_env = env;
    if (!gL) return;
    int tb = beginHook("onMouseMove");
    if (!tb) return;
    lua_pushnumber(gL, x); lua_pushnumber(gL, y);
    lua_pushnumber(gL, dx); lua_pushnumber(gL, dy);
    endHook(tb, 4);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_keyDown(JNIEnv *env, jobject self, jstring jname) {
    (void)self;
    g_env = env;
    if (!gL) return;
    const char *name = (*env)->GetStringUTFChars(env, jname, 0);
    int tb = beginHook("onKeyDown");
    if (tb) lua_pushstring(gL, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (tb) endHook(tb, 1);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_keyUp(JNIEnv *env, jobject self, jstring jname) {
    (void)self;
    g_env = env;
    if (!gL) return;
    const char *name = (*env)->GetStringUTFChars(env, jname, 0);
    int tb = beginHook("onKeyUp");
    if (tb) lua_pushstring(gL, name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (tb) endHook(tb, 1);
}

JNIEXPORT void JNICALL Java_net_dynart_soob_Lua_textInput(JNIEnv *env, jobject self, jstring jch) {
    (void)self;
    g_env = env;
    if (!gL) return;
    const char *ch = (*env)->GetStringUTFChars(env, jch, 0);
    int tb = beginHook("onTextInput");
    if (tb) lua_pushstring(gL, ch);
    (*env)->ReleaseStringUTFChars(env, jch, ch);
    if (tb) endHook(tb, 1);
}
