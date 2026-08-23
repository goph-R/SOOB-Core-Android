# bridge_jni.c resolves these by name (FindClass / GetStaticMethodID and the
# Java_info_dynart_soob_Lua_* symbols), so R8 must not rename or strip them.
-keep class info.dynart.soob.Host { *; }
-keep class info.dynart.soob.Lua { *; }
