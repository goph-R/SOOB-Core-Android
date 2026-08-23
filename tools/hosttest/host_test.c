/* host_test.c — run bridge_jni.c on the desktop, against a real game bundle.
 *
 * The Kotlin host needs a device, but the C half of the port (the Lua state,
 * the sandbox, the package.loaders asset searcher, the assets.lua walk, the
 * binding argument marshalling and the hook dispatch) is plain C behind the
 * JNI function table. This harness supplies a stub table plus a C mirror of
 * info.dynart.soob.Host, then boots a real game bundle through the unmodified
 * bridge and reports what the Lua actually did.
 *
 *     ./build.sh && ./host_test.exe ../../../Find5
 *
 * Exit code is non-zero if the boot fails or a frame draws nothing, so this
 * doubles as a regression check when the bridge or the Lua changes.
 */

#include <jni.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------------------------------------------------------------------------
 * Fake JNI objects. Everything the bridge passes around is one of these.
 * ------------------------------------------------------------------------- */
typedef enum { O_CLASS, O_STRING, O_BYTES, O_BUFFER } ObjKind;

struct FakeObj {
    ObjKind kind;
    char *str;             /* O_STRING */
    unsigned char *bytes;  /* O_BYTES  */
    int len;               /* O_BYTES  */
    void *addr;            /* O_BUFFER */
};

static struct FakeObj g_class = { O_CLASS, 0, 0, 0, 0 };

static jobject newObj(ObjKind k) {
    struct FakeObj *o = (struct FakeObj *)calloc(1, sizeof *o);
    o->kind = k;
    return (jobject)o;
}

/* ---- the game bundle on disk ---- */
static const char *g_gameDir = "../../../Find5";

/* ---- what the Lua did, so main() can assert on it ---- */
static struct {
    int regSounds, regMusic, regTextures, regFonts, regRegions;
    int drawRegion, drawText, drawQuad, drawEllipse, drawBg, drawBlur;
    int soundPlay, musicPlay, musicStop, imeShow, imeHide, quit, messages;
    int assetReads, assetMisses;
    char lastRegion[128];
    char lastText[256];
    char *savedOpts;
} S;

/* ---- the region table, so regionSize()/drawRegion have real answers ---- */
#define MAX_REGIONS 512
static struct {
    char name[96];
    double x, y, w, h;
    int hasSlice;
    double s[4];
} g_regions[MAX_REGIONS];
static int g_regionCount;

static double *g_args;   /* the bridge's scratch, handed over by setArgs */

static int findRegion(const char *n) {
    for (int i = 0; i < g_regionCount; i++) {
        if (strcmp(g_regions[i].name, n) == 0) return i;
    }
    return -1;
}

/* ---------------------------------------------------------------------------
 * Host methods, keyed by name exactly as bridge_jni.c looks them up.
 * ------------------------------------------------------------------------- */
static const char *METHODS[] = {
    "setArgs", "readAsset", "drawRegion", "drawText", "drawQuad", "drawEllipse",
    "drawBg", "drawBlur", "viewW", "viewH", "regionW", "regionH", "regionSlice",
    "textWidth", "keyDown", "mouseX", "mouseY", "mouseBtn", "keyMods",
    "soundPlay", "musicPlay", "musicStop", "musicVolume", "showMessage",
    "requestQuit", "imeShow", "imeHide", "optSave", "optLoad",
    "regSound", "regMusic", "regTexture", "regFont", "regRegion", 0,
};

static const char *methodName(jmethodID id) { return (const char *)id; }

static int is(jmethodID id, const char *name) {
    return strcmp(methodName(id), name) == 0;
}

static const char *cstr(jobject o) {
    struct FakeObj *f = (struct FakeObj *)o;
    return (f && f->kind == O_STRING && f->str) ? f->str : "";
}

/* Read one bundle file from disk (the AssetManager stand-in). */
static unsigned char *readBundleFile(const char *rel, int *outLen) {
    char path[1024];
    snprintf(path, sizeof path, "%s/%s", g_gameDir, rel);
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *buf = (unsigned char *)malloc((size_t)n + 1);
    size_t got = fread(buf, 1, (size_t)n, f);
    fclose(f);
    buf[got] = 0;
    *outLen = (int)got;
    return buf;
}

/* ---------------------------------------------------------------------------
 * The stub JNI function table.
 * ------------------------------------------------------------------------- */
static jclass st_FindClass(JNIEnv *env, const char *name) {
    (void)env;
    if (strcmp(name, "info/dynart/soob/Host") != 0) {
        fprintf(stderr, "FindClass: unexpected class %s\n", name);
        return 0;
    }
    return (jclass)&g_class;
}

static jmethodID st_GetStaticMethodID(JNIEnv *env, jclass c, const char *name, const char *sig) {
    (void)env; (void)c; (void)sig;
    for (int i = 0; METHODS[i]; i++) {
        if (strcmp(METHODS[i], name) == 0) return (jmethodID)METHODS[i];
    }
    fprintf(stderr, "GetStaticMethodID: no Host.%s%s\n", name, sig);
    return 0;
}

static jobject st_NewGlobalRef(JNIEnv *env, jobject o) { (void)env; return o; }

static jstring st_NewStringUTF(JNIEnv *env, const char *s) {
    (void)env;
    struct FakeObj *o = (struct FakeObj *)newObj(O_STRING);
    o->str = strdup(s ? s : "");
    return (jstring)o;
}

static const char *st_GetStringUTFChars(JNIEnv *env, jstring s, jboolean *copy) {
    (void)env;
    if (copy) *copy = JNI_FALSE;
    return cstr(s);
}

static void st_ReleaseStringUTFChars(JNIEnv *env, jstring s, const char *c) {
    (void)env; (void)s; (void)c;
}

static void st_DeleteLocalRef(JNIEnv *env, jobject o) {
    (void)env;
    struct FakeObj *f = (struct FakeObj *)o;
    if (!f || f->kind == O_CLASS) return;
    free(f->str);
    free(f->bytes);
    free(f);
}

static jsize st_GetArrayLength(JNIEnv *env, jarray a) {
    (void)env;
    return ((struct FakeObj *)a)->len;
}

static void st_GetByteArrayRegion(JNIEnv *env, jbyteArray a, jsize start, jsize len, jbyte *buf) {
    (void)env;
    memcpy(buf, ((struct FakeObj *)a)->bytes + start, (size_t)len);
}

static jobject st_NewDirectByteBuffer(JNIEnv *env, void *addr, jlong cap) {
    (void)env; (void)cap;
    struct FakeObj *o = (struct FakeObj *)newObj(O_BUFFER);
    o->addr = addr;
    return (jobject)o;
}

/* ---- the Host implementation itself ---- */

static void hostVoid(jmethodID m, va_list ap) {
    if (is(m, "setArgs")) {
        jobject b = va_arg(ap, jobject);
        g_args = (double *)((struct FakeObj *)b)->addr;
    } else if (is(m, "drawRegion")) {
        jstring n = va_arg(ap, jstring);
        S.drawRegion++;
        snprintf(S.lastRegion, sizeof S.lastRegion, "%s", cstr(n));
        if (findRegion(cstr(n)) < 0) {
            fprintf(stderr, "  ! drawRegion of unregistered region '%s'\n", cstr(n));
        }
    } else if (is(m, "drawText")) {
        jstring t = va_arg(ap, jstring);
        S.drawText++;
        snprintf(S.lastText, sizeof S.lastText, "%s", cstr(t));
    } else if (is(m, "drawQuad")) {
        S.drawQuad++;
    } else if (is(m, "drawEllipse")) {
        S.drawEllipse++;
    } else if (is(m, "drawBg")) {
        S.drawBg++;
    } else if (is(m, "drawBlur")) {
        S.drawBlur++;
    } else if (is(m, "soundPlay")) {
        S.soundPlay++;
    } else if (is(m, "musicPlay")) {
        jstring n = va_arg(ap, jstring);
        S.musicPlay++;
        printf("  musicPlay(%s)\n", cstr(n));
    } else if (is(m, "musicStop")) {
        S.musicStop++;
    } else if (is(m, "musicVolume")) {
        /* nothing to record */
    } else if (is(m, "showMessage")) {
        jstring t = va_arg(ap, jstring);
        S.messages++;
        printf("  [message] %s\n", cstr(t));
    } else if (is(m, "requestQuit")) {
        S.quit++;
    } else if (is(m, "imeShow")) {
        S.imeShow++;
    } else if (is(m, "imeHide")) {
        S.imeHide++;
    } else if (is(m, "optSave")) {
        jstring t = va_arg(ap, jstring);
        free(S.savedOpts);
        S.savedOpts = strdup(cstr(t));
    } else if (is(m, "regSound")) {
        S.regSounds++;
    } else if (is(m, "regMusic")) {
        S.regMusic++;
    } else if (is(m, "regTexture")) {
        S.regTextures++;
    } else if (is(m, "regFont")) {
        S.regFonts++;
    } else if (is(m, "regRegion")) {
        jstring n = va_arg(ap, jstring);
        (void)va_arg(ap, jstring);   /* texture name */
        if (g_regionCount < MAX_REGIONS) {
            int i = g_regionCount++;
            snprintf(g_regions[i].name, sizeof g_regions[i].name, "%s", cstr(n));
            g_regions[i].x = g_args[0];
            g_regions[i].y = g_args[1];
            g_regions[i].w = g_args[2];
            g_regions[i].h = g_args[3];
            g_regions[i].hasSlice = g_args[4] != 0.0;
            for (int k = 0; k < 4; k++) g_regions[i].s[k] = g_args[5 + k];
        }
        S.regRegions++;
    } else {
        fprintf(stderr, "unhandled void Host.%s\n", methodName(m));
    }
}

static void st_CallStaticVoidMethod(JNIEnv *env, jclass c, jmethodID m, ...) {
    (void)env; (void)c;
    va_list ap;
    va_start(ap, m);
    hostVoid(m, ap);
    va_end(ap);
}

static jdouble st_CallStaticDoubleMethod(JNIEnv *env, jclass c, jmethodID m, ...) {
    (void)env; (void)c;
    va_list ap;
    va_start(ap, m);
    jdouble out = 0;
    if (is(m, "viewW")) {
        out = 640;
    } else if (is(m, "viewH")) {
        out = 480;
    } else if (is(m, "regionW") || is(m, "regionH")) {
        jstring n = va_arg(ap, jstring);
        int i = findRegion(cstr(n));
        out = (i < 0) ? -1 : (is(m, "regionW") ? g_regions[i].w : g_regions[i].h);
    } else if (is(m, "textWidth")) {
        jstring t = va_arg(ap, jstring);
        (void)va_arg(ap, jstring);              /* font */
        double scale = va_arg(ap, double);
        out = (double)strlen(cstr(t)) * 8.0 * scale;   /* stand-in metrics */
    } else if (is(m, "mouseX") || is(m, "mouseY")) {
        out = 0;
    } else {
        fprintf(stderr, "unhandled double Host.%s\n", methodName(m));
    }
    va_end(ap);
    return out;
}

static jboolean st_CallStaticBooleanMethod(JNIEnv *env, jclass c, jmethodID m, ...) {
    (void)env; (void)c;
    va_list ap;
    va_start(ap, m);
    jboolean out = JNI_FALSE;
    if (is(m, "regionSlice")) {
        jstring n = va_arg(ap, jstring);
        int i = findRegion(cstr(n));
        if (i >= 0 && g_regions[i].hasSlice) {
            for (int k = 0; k < 4; k++) g_args[k] = g_regions[i].s[k];
            g_args[4] = g_regions[i].w;
            g_args[5] = g_regions[i].h;
            out = JNI_TRUE;
        }
    } else if (is(m, "keyDown") || is(m, "mouseBtn")) {
        out = JNI_FALSE;
    } else {
        fprintf(stderr, "unhandled boolean Host.%s\n", methodName(m));
    }
    va_end(ap);
    return out;
}

static jint st_CallStaticIntMethod(JNIEnv *env, jclass c, jmethodID m, ...) {
    (void)env; (void)c;
    if (is(m, "keyMods")) return 0;
    fprintf(stderr, "unhandled int Host.%s\n", methodName(m));
    return 0;
}

static jobject st_CallStaticObjectMethod(JNIEnv *env, jclass c, jmethodID m, ...) {
    (void)env; (void)c;
    va_list ap;
    va_start(ap, m);
    jobject out = 0;
    if (is(m, "readAsset")) {
        jstring p = va_arg(ap, jstring);
        int len = 0;
        unsigned char *data = readBundleFile(cstr(p), &len);
        if (data) {
            struct FakeObj *o = (struct FakeObj *)newObj(O_BYTES);
            o->bytes = data;
            o->len = len;
            out = (jobject)o;
            S.assetReads++;
        } else {
            S.assetMisses++;
        }
    } else if (is(m, "optLoad")) {
        if (S.savedOpts) out = (jobject)st_NewStringUTF(0, S.savedOpts);
    } else {
        fprintf(stderr, "unhandled object Host.%s\n", methodName(m));
    }
    va_end(ap);
    return out;
}

static struct JNINativeInterface g_tbl;
static JNIEnv g_env = &g_tbl;

static void initJni(void) {
    memset(&g_tbl, 0, sizeof g_tbl);
    g_tbl.FindClass = st_FindClass;
    g_tbl.GetStaticMethodID = st_GetStaticMethodID;
    g_tbl.NewGlobalRef = st_NewGlobalRef;
    g_tbl.NewStringUTF = st_NewStringUTF;
    g_tbl.GetStringUTFChars = st_GetStringUTFChars;
    g_tbl.ReleaseStringUTFChars = st_ReleaseStringUTFChars;
    g_tbl.DeleteLocalRef = st_DeleteLocalRef;
    g_tbl.GetArrayLength = st_GetArrayLength;
    g_tbl.GetByteArrayRegion = st_GetByteArrayRegion;
    g_tbl.NewDirectByteBuffer = st_NewDirectByteBuffer;
    g_tbl.CallStaticVoidMethod = st_CallStaticVoidMethod;
    g_tbl.CallStaticDoubleMethod = st_CallStaticDoubleMethod;
    g_tbl.CallStaticBooleanMethod = st_CallStaticBooleanMethod;
    g_tbl.CallStaticIntMethod = st_CallStaticIntMethod;
    g_tbl.CallStaticObjectMethod = st_CallStaticObjectMethod;
}

/* ---- the bridge's entry points (same symbols the JVM would bind) ---- */
extern jboolean Java_info_dynart_soob_Lua_newState(JNIEnv *, jobject);
extern jboolean Java_info_dynart_soob_Lua_doAsset(JNIEnv *, jobject, jstring);
extern jboolean Java_info_dynart_soob_Lua_doString(JNIEnv *, jobject, jstring);
extern jboolean Java_info_dynart_soob_Lua_loadAssets(JNIEnv *, jobject, jstring);
extern void Java_info_dynart_soob_Lua_callHook0(JNIEnv *, jobject, jstring);
extern void Java_info_dynart_soob_Lua_update(JNIEnv *, jobject, jdouble);
extern void Java_info_dynart_soob_Lua_render(JNIEnv *, jobject);
extern void Java_info_dynart_soob_Lua_mouseDown(JNIEnv *, jobject, jdouble, jdouble, jint);
extern void Java_info_dynart_soob_Lua_mouseUp(JNIEnv *, jobject, jdouble, jdouble, jint);
extern void Java_info_dynart_soob_Lua_mouseMove(JNIEnv *, jobject, jdouble, jdouble, jdouble, jdouble);
extern void Java_info_dynart_soob_Lua_keyDown(JNIEnv *, jobject, jstring);
extern void Java_info_dynart_soob_Lua_keyUp(JNIEnv *, jobject, jstring);
extern void Java_info_dynart_soob_Lua_destroy(JNIEnv *, jobject);

#define STR(s) st_NewStringUTF(0, s)

static int fails;

static void check(int cond, const char *what) {
    printf("%s %s\n", cond ? "ok  " : "FAIL", what);
    if (!cond) fails++;
}

int main(int argc, char **argv) {
    if (argc > 1) g_gameDir = argv[1];
    printf("game bundle: %s\n\n", g_gameDir);
    initJni();

    check(Java_info_dynart_soob_Lua_newState(&g_env, 0), "newState");
    Java_info_dynart_soob_Lua_doString(&g_env, 0, STR("platform=\"hosttest\""));

    check(Java_info_dynart_soob_Lua_loadAssets(&g_env, 0, STR("assets.lua")), "loadAssets");
    check(S.regTextures > 0, "textures registered");
    check(S.regRegions > 0, "regions registered");
    check(S.regFonts > 0, "fonts registered");
    check(S.regSounds > 0, "sounds registered");

    check(Java_info_dynart_soob_Lua_doAsset(&g_env, 0, STR("scripts/main.lua")), "run main.lua");
    check(S.assetReads > 2, "require() resolved modules through the asset searcher");

    Java_info_dynart_soob_Lua_callHook0(&g_env, 0, STR("onStart"));

    for (int i = 0; i < 3; i++) {
        Java_info_dynart_soob_Lua_update(&g_env, 0, 1.0 / 60.0);
        Java_info_dynart_soob_Lua_render(&g_env, 0);
    }
    int drewSomething = S.drawRegion + S.drawText + S.drawQuad + S.drawBg + S.drawEllipse + S.drawBlur;
    check(drewSomething > 0, "the first scene draws");

    /* A click in the middle of the view, then a key, then a frame. */
    Java_info_dynart_soob_Lua_mouseMove(&g_env, 0, 0, 0, 0, 0);
    Java_info_dynart_soob_Lua_mouseDown(&g_env, 0, 0, 0, 1);
    Java_info_dynart_soob_Lua_mouseUp(&g_env, 0, 0, 0, 1);
    Java_info_dynart_soob_Lua_keyDown(&g_env, 0, STR("escape"));
    Java_info_dynart_soob_Lua_keyUp(&g_env, 0, STR("escape"));
    Java_info_dynart_soob_Lua_update(&g_env, 0, 1.0 / 60.0);
    Java_info_dynart_soob_Lua_render(&g_env, 0);

    /* Options round-trip: set, save (the desktop file format), reload. */
    Java_info_dynart_soob_Lua_doString(&g_env, 0, STR(
        "optSet('host_test', {level=3, name='abc', on=true}) optSave()"));
    check(S.savedOpts && strstr(S.savedOpts, "host_test") != 0, "optSave wrote the opts chunk");
    Java_info_dynart_soob_Lua_doString(&g_env, 0, STR(
        "optLoad() local t = optGet('host_test') "
        "assert(t and t.level == 3 and t.name == 'abc' and t.on == true, 'opts round-trip')"));
    check(1, "optLoad round-tripped the table");

    printf("\nassets:  %d textures, %d regions, %d fonts, %d sounds, %d music\n",
           S.regTextures, S.regRegions, S.regFonts, S.regSounds, S.regMusic);
    printf("draws:   region=%d text=%d quad=%d ellipse=%d bg=%d blur=%d\n",
           S.drawRegion, S.drawText, S.drawQuad, S.drawEllipse, S.drawBg, S.drawBlur);
    printf("audio:   soundPlay=%d musicPlay=%d musicStop=%d\n",
           S.soundPlay, S.musicPlay, S.musicStop);
    printf("assets read from the bundle: %d (misses: %d)\n", S.assetReads, S.assetMisses);
    printf("last region drawn: %s | last text: %s\n", S.lastRegion, S.lastText);

    Java_info_dynart_soob_Lua_destroy(&g_env, 0);
    printf("\n%s\n", fails ? "FAILURES" : "all checks passed");
    return fails ? 1 : 0;
}
