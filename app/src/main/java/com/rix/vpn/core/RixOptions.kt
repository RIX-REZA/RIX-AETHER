package com.rix.vpn.core

import org.json.JSONObject

/** How the app delivers the tunnel to the rest of the phone. */
enum class RixMode(val label: String, val short: String, val desc: String) {
    /** VpnService captures every packet; hev turns the TUN into SOCKS5. */
    TUNNEL(
        "تونل کامل",
        "همهٔ برنامه‌ها",
        "VpnService کل ترافیک گوشی را می‌گیرد و به هسته می‌دهد؛ هیچ برنامه‌ای نمی‌تواند دور بزند."
    ),

    /** No capture at all: the core just serves a local SOCKS5 proxy. */
    PROXY(
        "پروکسی (SOCKS5)",
        "فقط برنامه‌های انتخابی",
        "هیچ بسته‌ای گرفته نمی‌شود؛ فقط یک SOCKS5 محلی روی 127.0.0.1 باز می‌شود تا خودت به برنامه‌ها بدهی."
    )
}

/** The core's transport, mapped to the real CLI selectors (aether/src/cli.rs). */
enum class Transport(
    val label: String,
    val args: List<String>,
    val desc: String,
    val badge: String?
) {
    MASQUE_H3(
        "MASQUE / HTTP-3",
        listOf("--masque"),
        "HTTP/3 روی QUIC؛ کمترین تأخیر و بهترین سرعت روی موبایل.",
        "پیشنهادی"
    ),
    MASQUE_H2(
        "MASQUE / HTTP-2",
        listOf("--masque", "--h2"),
        "HTTP/2 روی TLS، روی TCP 443؛ در ایران پایدارترین گزینه و اولین جایگزین وقتی بقیه وصل نمیشوند.",
        "سازگار"
    ),
    WIREGUARD(
        "WireGuard",
        listOf("--wg"),
        "WireGuard خالص؛ سبکترین و کممصرفترین، اما در ایران معمولاً مسدود است.",
        "کم‌مصرف"
    ),
    GOOL(
        "Gool (WG داخل MASQUE)",
        listOf("--gool"),
        "WireGuard پیچیده در MASQUE؛ تعادل بین پایداری و استتار.",
        "متعادل"
    ),
    GOOL_CLASSIC(
        "Gool کلاسیک (WG در WG)",
        listOf("--gool-classic"),
        "تونل تودرتو با دو لایهٔ WireGuard؛ برای شبکه‌های بسیار سخت‌گیر.",
        null
    ),
    MIM(
        "MASQUE در MASQUE",
        listOf("--mim"),
        "دو لایه MASQUE؛ حداکثر مقاومت به DPI به قیمت کمی تأخیر بیشتر.",
        "حداکثر استتار"
    )
}

/** `--scan <mode>`: how hard the core looks for a reachable edge. */
enum class ScanMode(val label: String, val value: String?) {
    DEFAULT("پیش‌فرض هسته", null),
    TURBO("Turbo (سریع‌ترین)", "turbo"),
    BALANCED("Balanced", "balanced"),
    THOROUGH("Thorough (کامل)", "thorough"),
    VERIFIED("Verified", "verified"),
    IRONCLAD("Ironclad (تست واقعی)", "ironclad")
}

enum class IpFamily(val label: String, val value: String) {
    V4("IPv4", "v4"),
    V6("IPv6", "v6"),
    BOTH("هردو", "both")
}

enum class PerfProfile(val label: String, val value: String) {
    LOW("کم‌مصرف", "low"),
    MEDIUM("متعادل", "medium"),
    HIGH("پرقدرت", "high")
}

enum class LogLevel(val label: String, val value: String) {
    ERROR("Error", "error"),
    WARN("Warn", "warn"),
    INFO("Info", "info"),
    DEBUG("Debug", "debug"),
    TRACE("Trace", "trace")
}

/** Which apps the TUN captures when [RixMode.TUNNEL] is active. */
enum class SplitMode(val label: String) {
    ALL("همهٔ برنامه‌ها"),
    ONLY("فقط انتخاب‌شده‌ها"),
    EXCEPT("همه جز انتخاب‌شده‌ها")
}

/**
 * Every knob the UI exposes.
 *
 * [extraArgs] is the escape hatch: the core has ~136 flags and this model only
 * promotes the ones a phone user actually changes, so anything else is typed
 * here verbatim and appended last (last wins in the core's parser).
 */
data class RixOptions(
    val mode: RixMode = RixMode.TUNNEL,
    val transport: Transport = Transport.MASQUE_H3,
    /**
     * When the chosen transport cannot prove a tunnel, try the others in a
     * resilience order instead of failing. On networks that block UDP (Iran,
     * corporate Wi-Fi) WireGuard and MASQUE/HTTP-3 simply never handshake, so a
     * fixed choice means «وصل نمیشود» rather than a slower connection.
     */
    val autoFallback: Boolean = true,
    val bindPort: Int = 1819,
    val dns: String = "1.1.1.1,1.0.0.1",
    val ipFamily: IpFamily = IpFamily.V4,
    val exitLoc: String = "",
    val peer: String = "",
    val masqueSni: String = "",
    val upstreamProxy: String = "",
    val reconnectSecs: Int = 2,
    val quickReconnect: Boolean = true,
    val keepalive: Int = 5,
    val fragment: Boolean = false,
    val fragmentSize: String = "",
    val fragmentDelay: String = "",
    val scanMode: ScanMode = ScanMode.DEFAULT,
    val ironclad: Boolean = false,
    val perf: PerfProfile = PerfProfile.MEDIUM,
    val logLevel: LogLevel = LogLevel.INFO,
    val ech: String = "",
    val routeDirect: String = "",
    val routeBlock: String = "",
    val extraArgs: String = "",
    val mtu: Int = 1500,
    val excludeSelf: Boolean = true,
    val splitMode: SplitMode = SplitMode.ALL,
    val splitApps: List<String> = emptyList()
) {

    /** The tunnel's own address inside the TUN; hev needs it to match. */
    val tunnelIpv4: String get() = "10.87.0.2"

    val tunnelIpv6: String get() = "fd00:87::2"

    /**
     * Builds the argv handed to `aether_core_start`.
     *
     * Order matters only for [extraArgs]: the core parses left to right into
     * environment variables, so a later token overrides an earlier one.
     */
    fun toArgs(configPath: String): List<String> = buildList {
        add("--bind"); add("127.0.0.1:$bindPort")
        add("--config"); add(configPath)
        add("--log-level"); add(logLevel.value)
        add("--perf"); add(perf.value)

        addAll(transport.args)

        if (dns.isNotBlank()) { add("--dns"); add(dns.trim()) }
        add("--ip"); add(ipFamily.value)

        if (exitLoc.isNotBlank()) { add("--exit-loc"); add(exitLoc.trim()) }
        if (peer.isNotBlank()) {
            add("--peer"); add(peer.trim())
        }
        if (masqueSni.isNotBlank()) { add("--masque-sni"); add(masqueSni.trim()) }
        if (upstreamProxy.isNotBlank()) { add("--http-proxy"); add(upstreamProxy.trim()) }

        add("--reconnect-secs"); add(reconnectSecs.coerceIn(0, 600).toString())
        add(if (quickReconnect) "--quick-reconnect" else "--no-quick-reconnect")
        if (keepalive > 0) { add("--keepalive"); add(keepalive.toString()) }

        if (fragment) {
            add("--fragment")
            if (fragmentSize.isNotBlank()) { add("--fragment-size"); add(fragmentSize.trim()) }
            if (fragmentDelay.isNotBlank()) { add("--fragment-delay"); add(fragmentDelay.trim()) }
        }

        scanMode.value?.let { add("--scan"); add(it) }
        if (ironclad && scanMode != ScanMode.IRONCLAD) add("--ironclad")

        if (ech.isNotBlank()) { add("--ech"); add(ech.trim()) }
        if (routeDirect.isNotBlank()) { add("--route-direct"); add(routeDirect.trim()) }
        if (routeBlock.isNotBlank()) { add("--route-block"); add(routeBlock.trim()) }

        addAll(splitExtraArgs(extraArgs))
    }

    /** Whitespace split that still honours "quoted values". */
    private fun splitExtraArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        for (ch in raw) {
            when {
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> {
                    if (token.isNotEmpty()) { out.add(token.toString()); token.clear() }
                }
                else -> token.append(ch)
            }
        }
        if (token.isNotEmpty()) out.add(token.toString())
        return out
    }

    fun toJson(): String = JSONObject().apply {
        put("mode", mode.name)
        put("transport", transport.name)
        put("autoFallback", autoFallback)
        put("bindPort", bindPort)
        put("dns", dns)
        put("ipFamily", ipFamily.name)
        put("exitLoc", exitLoc)
        put("peer", peer)
        put("masqueSni", masqueSni)
        put("upstreamProxy", upstreamProxy)
        put("reconnectSecs", reconnectSecs)
        put("quickReconnect", quickReconnect)
        put("keepalive", keepalive)
        put("fragment", fragment)
        put("fragmentSize", fragmentSize)
        put("fragmentDelay", fragmentDelay)
        put("scanMode", scanMode.name)
        put("ironclad", ironclad)
        put("perf", perf.name)
        put("logLevel", logLevel.name)
        put("ech", ech)
        put("routeDirect", routeDirect)
        put("routeBlock", routeBlock)
        put("extraArgs", extraArgs)
        put("mtu", mtu)
        put("excludeSelf", excludeSelf)
        put("splitMode", splitMode.name)
        put("splitApps", splitApps.joinToString("|"))
    }.toString()

    companion object {

        fun fromJson(raw: String?): RixOptions {
            if (raw.isNullOrBlank()) return RixOptions()
            return try {
                val o = JSONObject(raw)
                val base = RixOptions()
                RixOptions(
                    mode = o.optEnum("mode", RixMode.entries, base.mode),
                    transport = o.optEnum("transport", Transport.entries, base.transport),
                    autoFallback = o.optBoolean("autoFallback", base.autoFallback),
                    bindPort = o.optInt("bindPort", base.bindPort).coerceIn(1024, 65535),
                    dns = o.optString("dns", base.dns),
                    ipFamily = o.optEnum("ipFamily", IpFamily.entries, base.ipFamily),
                    exitLoc = o.optString("exitLoc", base.exitLoc),
                    peer = o.optString("peer", base.peer),
                    masqueSni = o.optString("masqueSni", base.masqueSni),
                    upstreamProxy = o.optString("upstreamProxy", base.upstreamProxy),
                    reconnectSecs = o.optInt("reconnectSecs", base.reconnectSecs).coerceIn(0, 600),
                    quickReconnect = o.optBoolean("quickReconnect", base.quickReconnect),
                    keepalive = o.optInt("keepalive", base.keepalive).coerceIn(0, 3600),
                    fragment = o.optBoolean("fragment", base.fragment),
                    fragmentSize = o.optString("fragmentSize", base.fragmentSize),
                    fragmentDelay = o.optString("fragmentDelay", base.fragmentDelay),
                    scanMode = o.optEnum("scanMode", ScanMode.entries, base.scanMode),
                    ironclad = o.optBoolean("ironclad", base.ironclad),
                    perf = o.optEnum("perf", PerfProfile.entries, base.perf),
                    logLevel = o.optEnum("logLevel", LogLevel.entries, base.logLevel),
                    ech = o.optString("ech", base.ech),
                    routeDirect = o.optString("routeDirect", base.routeDirect),
                    routeBlock = o.optString("routeBlock", base.routeBlock),
                    extraArgs = o.optString("extraArgs", base.extraArgs),
                    mtu = o.optInt("mtu", base.mtu).coerceIn(1280, 9000),
                    excludeSelf = o.optBoolean("excludeSelf", base.excludeSelf),
                    splitMode = o.optEnum("splitMode", SplitMode.entries, base.splitMode),
                    splitApps = o.optString("splitApps", "")
                        .split("|").filter { it.isNotBlank() }
                )
            } catch (t: Throwable) {
                RixOptions()
            }
        }

        private fun <T : Enum<T>> JSONObject.optEnum(key: String, values: List<T>, fallback: T): T {
            val name = optString(key, "")
            return values.firstOrNull { it.name == name } ?: fallback
        }
    }
}