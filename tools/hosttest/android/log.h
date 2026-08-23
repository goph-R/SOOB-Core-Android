/* Desktop stand-in for <android/log.h> so bridge_jni.c compiles unmodified in
   the host harness. Everything goes to stdout. */
#ifndef SOOB_HOSTTEST_ANDROID_LOG_H
#define SOOB_HOSTTEST_ANDROID_LOG_H

#include <stdarg.h>
#include <stdio.h>

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5

static int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap;
    printf("%s/%s: ", (prio >= ANDROID_LOG_WARN) ? "W" : "I", tag);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    return 0;
}

#endif
