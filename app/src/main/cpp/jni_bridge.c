#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "rixjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ *
 * Function-pointer tables.
 *
 * Both libraries are resolved at runtime; nothing is linked. The two
 * signatures below are copied from the Aether C ABI (aether/src/ffi.rs,
 * `#[no_mangle] pub extern "C"`) and from hev-socks5-tunnel's public
 * header (src/hev-socks5-tunnel.h). Declaring them here keeps this
 * translation unit free of either project's build include paths.
 * ------------------------------------------------------------------ */

typedef char *(*fn_aether_version)(void);
typedef void (*fn_aether_string_free)(char *);
typedef char *(*fn_aether_core_start)(const char *);
typedef char *(*fn_aether_job_u64)(uint64_t);

typedef int (*fn_hev_main_from_file)(const char *, int);
typedef void (*fn_hev_stop)(void);
typedef void (*fn_hev_stats)(size_t *, size_t *, size_t *, size_t *);

static void *g_aether_handle = NULL;
static void *g_hev_handle = NULL;

static fn_aether_version g_aether_version;
static fn_aether_string_free g_aether_string_free;
static fn_aether_core_start g_aether_core_start;
static fn_aether_job_u64 g_aether_job_poll;
static fn_aether_job_u64 g_aether_job_cancel;
static fn_aether_job_u64 g_aether_job_free;

static fn_hev_main_from_file g_hev_main_from_file;
static fn_hev_stop g_hev_stop;
static fn_hev_stats g_hev_stats;

/* Last dlopen/dlsym failure, surfaced through nativeDiagnostics(). */
static char g_load_error[512];

static void set_error(const char *what, const char *detail) {
    snprintf(g_load_error, sizeof(g_load_error), "%s: %s", what,
             detail ? detail : "(no detail)");
}

static jstring new_string(JNIEnv *env, const char *text) {
    if (text == NULL) {
        return NULL;
    }
    /* JNI takes modified UTF-8; the libraries answer with plain UTF-8 JSON, so
     * a byte copy is correct for the ASCII/UTF-8 payloads they emit. */
    jstring out = (*env)->NewStringUTF(env, text);
    if (out == NULL) {
        /* OutOfMemory or invalid encoding: clear so the caller sees null
         * instead of a pending exception it never checks. */
        (*env)->ExceptionClear(env);
    }
    return out;
}

static int load_aether(void) {
    if (g_aether_handle != NULL) {
        return 0;
    }
    /* RTLD_GLOBAL so symbols needed by the library's own dependencies resolve. */
    g_aether_handle = dlopen("libaether.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_aether_handle == NULL) {
        g_aether_handle = dlopen("libaether", RTLD_NOW | RTLD_GLOBAL);
    }
    if (g_aether_handle == NULL) {
        set_error("dlopen libaether", dlerror());
        LOGE("dlopen libaether failed: %s", g_load_error);
        return -1;
    }

#define AETHER_SYM(field, name)                                              \
    do {                                                                     \
        field = (void *) dlsym(g_aether_handle, name);                        \
        if (field == NULL) {                                                  \
            set_error("dlsym " name, dlerror());                              \
            LOGE("dlsym %s failed", name);                                    \
            return -1;                                                       \
        }                                                                    \
    } while (0)

    AETHER_SYM(g_aether_version, "aether_version");
    AETHER_SYM(g_aether_string_free, "aether_string_free");
    AETHER_SYM(g_aether_core_start, "aether_core_start");
    AETHER_SYM(g_aether_job_poll, "aether_job_poll");
    AETHER_SYM(g_aether_job_cancel, "aether_job_cancel");
    AETHER_SYM(g_aether_job_free, "aether_job_free");
#undef AETHER_SYM

    return 0;
}

static int load_hev(void) {
    if (g_hev_handle != NULL) {
        return 0;
    }
    g_hev_handle = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_hev_handle == NULL) {
        g_hev_handle = dlopen("libhev-socks5-tunnel", RTLD_NOW | RTLD_GLOBAL);
    }
    if (g_hev_handle == NULL) {
        set_error("dlopen libhev-socks5-tunnel", dlerror());
        LOGE("dlopen hev failed: %s", g_load_error);
        return -1;
    }

#define HEV_SYM(field, name)                                                 \
    do {                                                                     \
        field = (void *) dlsym(g_hev_handle, name);                           \
        if (field == NULL) {                                                  \
            set_error("dlsym " name, dlerror());                              \
            LOGE("dlsym %s failed", name);                                    \
            return -1;                                                       \
        }                                                                    \
    } while (0)

    HEV_SYM(g_hev_main_from_file, "hev_socks5_tunnel_main_from_file");
    HEV_SYM(g_hev_stop, "hev_socks5_tunnel_stop");
    HEV_SYM(g_hev_stats, "hev_socks5_tunnel_stats");
#undef HEV_SYM

    return 0;
}

/* ------------------------------------------------------------------ *
 * Diagnostics
 * ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_rix_vpn_core_NativeBridge_nativeDiagnostics(JNIEnv *env, jclass clazz) {
    char buffer[768];
    const int aether_ok = load_aether() == 0;
    const int hev_ok = load_hev() == 0;
    const char *identity = aether_ok ? g_aether_version() : NULL;

    snprintf(buffer, sizeof(buffer),
             "aether=%s hev=%s version=%s%s%s",
             aether_ok ? "ok" : "FAILED",
             hev_ok ? "ok" : "FAILED",
             identity ? identity : "?",
             g_load_error[0] ? " error=" : "",
             g_load_error);

    if (identity != NULL) {
        g_aether_string_free((char *) identity);
    }
    return new_string(env, buffer);
}

/* ------------------------------------------------------------------ *
 * Aether core (WARP / MASQUE / WireGuard) — every call is JSON in, JSON out.
 * ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_rix_vpn_core_NativeAether_coreStart(JNIEnv *env, jclass clazz, jstring args_json) {
    if (load_aether() != 0) {
        return new_string(env, g_load_error);
    }
    const char *args = (args_json == NULL) ? "[]" : (*env)->GetStringUTFChars(env, args_json, NULL);
    if (args == NULL) {
        return new_string(env, "arguments: could not read the java string");
    }

    char *reply = g_aether_core_start(args);

    if (args_json != NULL) {
        (*env)->ReleaseStringUTFChars(env, args_json, args);
    }
    if (reply == NULL) {
        return new_string(env, "{\"error\":\"aether_core_start returned null\"}");
    }

    /* Copy into JNI memory, then release the library's buffer with its own
     * allocator — mixing free() with a Rust allocation would be a bug. */
    jstring out = new_string(env, reply);
    g_aether_string_free(reply);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_rix_vpn_core_NativeAether_jobPoll(JNIEnv *env, jclass clazz, jlong id) {
    if (load_aether() != 0) {
        return new_string(env, g_load_error);
    }
    char *reply = g_aether_job_poll((uint64_t) id);
    if (reply == NULL) {
        return NULL;
    }
    jstring out = new_string(env, reply);
    g_aether_string_free(reply);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_rix_vpn_core_NativeAether_jobCancel(JNIEnv *env, jclass clazz, jlong id) {
    if (load_aether() != 0) {
        return new_string(env, g_load_error);
    }
    char *reply = g_aether_job_cancel((uint64_t) id);
    if (reply == NULL) {
        return NULL;
    }
    jstring out = new_string(env, reply);
    g_aether_string_free(reply);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_rix_vpn_core_NativeAether_jobFree(JNIEnv *env, jclass clazz, jlong id) {
    if (load_aether() != 0) {
        return new_string(env, g_load_error);
    }
    char *reply = g_aether_job_free((uint64_t) id);
    if (reply == NULL) {
        return NULL;
    }
    jstring out = new_string(env, reply);
    g_aether_string_free(reply);
    return out;
}

/* ------------------------------------------------------------------ *
 * hev-socks5-tunnel (userspace tun2socks).
 * ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_com_rix_vpn_core_NativeHev_nativeRun(JNIEnv *env, jclass clazz, jstring config_path, jint tun_fd) {
    if (load_hev() != 0) {
        return -2;
    }
    const char *path = (*env)->GetStringUTFChars(env, config_path, NULL);
    if (path == NULL) {
        return -3;
    }

    LOGI("hev start: config=%s fd=%d", path, (int) tun_fd);
    /* Blocks until stop() is called or the tunnel dies. */
    int rc = g_hev_main_from_file(path, (int) tun_fd);
    LOGI("hev stopped: rc=%d", rc);

    (*env)->ReleaseStringUTFChars(env, config_path, path);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_rix_vpn_core_NativeHev_nativeStop(JNIEnv *env, jclass clazz) {
    if (load_hev() == 0) {
        g_hev_stop();
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_rix_vpn_core_NativeHev_nativeStats(JNIEnv *env, jclass clazz) {
    jlong values[4] = {0, 0, 0, 0};
    if (load_hev() == 0) {
        size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
        g_hev_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
        values[0] = (jlong) tx_packets;
        values[1] = (jlong) tx_bytes;
        values[2] = (jlong) rx_packets;
        values[3] = (jlong) rx_bytes;
    }
    jlongArray out = (*env)->NewLongArray(env, 4);
    if (out != NULL) {
        (*env)->SetLongArrayRegion(env, out, 0, 4, values);
    }
    return out;
}