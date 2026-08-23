#!/usr/bin/env bash
#
# Build the desktop harness for bridge_jni.c (see host_test.c). Needs a host C
# compiler, the vendored Lua sources from the sibling SOOB-Core checkout, and a
# jni.h — the NDK's or any JDK's will do.
#
#   ./build.sh && ./host_test.exe ../../../Find5
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
LUA_SRC="${LUA_SRC:-$ROOT/../SOOB-Core/vendor/lua-5.1.5/src}"

# jni.h: the NDK ships one that compiles as plain C; override with JNI_INCLUDE.
if [ -z "${JNI_INCLUDE:-}" ]; then
  JNI_INCLUDE=$(ls -d "$LOCALAPPDATA/Android/Sdk/ndk"/*/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | head -1 || true)
fi
[ -n "${JNI_INCLUDE:-}" ] || { echo "error: set JNI_INCLUDE to a directory containing jni.h"; exit 1; }

# Copy jni.h out on its own: putting the NDK sysroot on the include path would
# shadow the host's libc headers with bionic's.
JNI_DIR="$HERE/.jni"
mkdir -p "$JNI_DIR"
cp "$JNI_INCLUDE/jni.h" "$JNI_DIR/jni.h"

# A host compiler: the portable MinGW that SOOB-Core vendors for the Win10
# build, or whatever cc is on PATH.
CC="${CC:-}"
if [ -z "$CC" ]; then
  if [ -x "$ROOT/../SOOB-Core/vendor_win10/mingw32/bin/gcc.exe" ]; then
    CC="$ROOT/../SOOB-Core/vendor_win10/mingw32/bin/gcc.exe"
  else
    CC=cc
  fi
fi

LUA_C=$(ls "$LUA_SRC"/*.c | grep -v -E '/(lua|luac|print|lua_all)\.c$')

# shellcheck disable=SC2086
"$CC" -O1 -g -std=gnu99 -o "$HERE/host_test.exe" \
  -I "$LUA_SRC" -I "$JNI_DIR" -I "$HERE" \
  $LUA_C "$ROOT/soob-player/src/main/cpp/bridge_jni.c" "$HERE/host_test.c" \
  -w

echo "built $HERE/host_test.exe"
