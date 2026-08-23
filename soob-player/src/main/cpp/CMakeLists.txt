# libsoob.so — the vendored Lua 5.1.5 (from the sibling SOOB-Core checkout)
# plus bridge_jni.c, the SOOB-Lua binding surface marshalled to the Kotlin host.
#
# Same source set and exclusions as the desktop build and SOOB-Core-Web's
# build-lua.sh: every .c except the standalone interpreter (lua.c), the bytecode
# compiler (luac.c) + its print helper (print.c), and the amalgamation.

cmake_minimum_required(VERSION 3.22.1)
project(soob C)

set(LUA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../SOOB-Core/vendor/lua-5.1.5/src)

if(NOT EXISTS ${LUA_DIR}/lua.h)
    message(FATAL_ERROR
        "Lua sources not found at ${LUA_DIR}.\n"
        "SOOB-Core must be checked out as a sibling of this repo.")
endif()

file(GLOB LUA_SOURCES ${LUA_DIR}/*.c)
list(FILTER LUA_SOURCES EXCLUDE REGEX "/(lua|luac|print|lua_all)\.c$")

add_library(soob SHARED ${LUA_SOURCES} bridge_jni.c)

target_include_directories(soob PRIVATE ${LUA_DIR})
target_compile_definitions(soob PRIVATE LUA_USE_POSIX LUA_ANSI=0 NDEBUG)
target_compile_options(soob PRIVATE -O2 -fvisibility=hidden -Wall)

# The vendored Lua is upstream C89 and trips -Wall on its own idioms; only our
# bridge is held to the warning level.
set_source_files_properties(${LUA_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
target_link_libraries(soob log)
