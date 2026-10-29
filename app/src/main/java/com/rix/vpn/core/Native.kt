package com.rix.vpn.core

/**
 * Plain JNI surface for the prebuilt libraries.
 *
 * Nothing here links at load time: [jni_bridge.c] resolves `libaether.so` and
 * `libhev-socks5-tunnel.so` with dlopen, so a missing or ABI-mismatched library
 * an error string instead of a hard process abort.
 */
object NativeBridge {

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /** Loads librixjni.so once and reports the result. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("rixjni")
            loaded = true
            loadError = null
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            loaded = false
            false
        }
    }

    /** Human-readable state of both prebuilt libraries, for the diagnostics card. */
    fun diagnostics(): String {
        if (!ensureLoaded()) return "librixjni: ${loadError ?: "unknown"}"
        return try {
            nativeDiagnostics()
        } catch (t: Throwable) {
            "native call failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    @JvmStatic
    private external fun nativeDiagnostics(): String
}

/** Aether core — every call is JSON in, JSON out. */
object NativeAether {

    @JvmStatic
    private external fun coreStart(argsJson: String): String

    @JvmStatic
    private external fun jobPoll(id: Long): String?

    @JvmStatic
    private external fun jobCancel(id: Long): String?

    @JvmStatic
    private external fun jobFree(id: Long): String?

    fun startCore(arguments: List<String>): String =
        coreStart(arguments.joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) })

    fun poll(id: Long): String? = jobPoll(id)

    fun cancel(id: Long): String? = jobCancel(id)

    fun free(id: Long): String? = jobFree(id)

    /** Minimal JSON string escaping: the argument list is built from user input. */
    private fun quote(raw: String): String {
        val out = StringBuilder(raw.length + 2)
        out.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append(String.format("\\u%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}

/** hev-socks5-tunnel — the userspace tun2socks that turns the TUN into SOCKS5. */
object NativeHev {

    @JvmStatic
    private external fun nativeRun(configPath: String, tunFd: Int): Int

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeStats(): LongArray?

    /** Blocking: run on a worker thread. Returns when the tunnel stops. */
    fun run(configPath: String, tunFd: Int): Int = nativeRun(configPath, tunFd)

    fun stop() = nativeStop()

    /** [txPackets, txBytes, rxPackets, rxBytes] — zeros when not running. */
    fun stats(): LongArray = nativeStats() ?: longArrayOf(0, 0, 0, 0)
}