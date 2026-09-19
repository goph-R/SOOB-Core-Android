# The player's JNI entry points and host callbacks are resolved by name from
# native code — R8 cannot see those references. (Also kept by the library's
# consumer rules; repeated here so a standalone build is safe.)
-keep class net.dynart.soob.Host { *; }
-keep class net.dynart.soob.Lua { *; }
