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

/**
 * Which of Psiphon's tunnel protocols it is allowed to dial.
 *
 * Psiphon's own client offers the same three shapes, and they matter more here
 * than the tunnel's own transport does: the protocols Psiphon reaches its
 * servers with decide whether the network lets it out at all.
 */
enum class PsiphonProtocol(val label: String, val protocols: List<String>) {
    /** Everything Psiphon can use through a socks5 upstream, its own choice. */
    AUTO(
        "خودکار (انتخاب سایفون)",
        listOf(
            "SSH", "OSSH", "TLS-OSSH",
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "SHADOWSOCKS-OSSH",
            "FRONTED-MEEK-OSSH", "FRONTED-MEEK-CDN-OSSH",
            "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH"
        )
    ),

    /** The direct, fast ones: OSSH variants that need no CDN in front. */
    PERFORMANCE(
        "سرعت",
        listOf(
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "SHADOWSOCKS-OSSH", "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH"
        )
    ),

    /** The widest net: plain SSH and the CDN-fronted shapes as well. */
    COMPATIBLE(
        "سازگاری",
        listOf(
            "SSH", "OSSH", "TLS-OSSH",
            "UNFRONTED-MEEK-HTTPS-OSSH",
            "FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH",
            "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH"
        )
    )
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
    /**
     * Puts Psiphon in the path, after the core.
     *
     * The core stays the outer hop -- it still dials WARP with [transport] --
     * and Psiphon is told to reach its own servers through the core's socks5
     * listener, so the address the far end sees is a Psiphon exit in
     * [psiphonRegion] rather than a Cloudflare one. That is the whole point:
     * the exit stops being wherever WARP puts you and becomes a country the
     * user picks.
     */
    val psiphon: Boolean = false,
    /** ISO 3166-1 alpha-2 egress country, e.g. "DE"; empty lets Psiphon choose. */
    val psiphonRegion: String = "",
    val psiphonProtocol: PsiphonProtocol = PsiphonProtocol.AUTO,
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
     * The port the core itself listens on.
     *
     * Without Psiphon that is [bindPort] and nothing changes. With it, Psiphon
     * takes [bindPort] -- that is the port the engine, the probes and the user
     * all talk to, so the rest of the app never has to know Psiphon is there --
     * and the core moves to the next port along so the two cannot collide.
     */
    val corePort: Int get() = if (!psiphon) safeBindPort else if (safeBindPort == PSIPHON_CORE_PORT) safeBindPort + 1 else PSIPHON_CORE_PORT

    /**
     * The same options, addressed at the core.
     *
     * Only the core's own listener moves; everything the user chose stays put.
     * Note that [corePort] is not the port Psiphon's upstream URL names -- that
     * one is read from this copy, so the two can never disagree.
     */
    fun forCore(): RixOptions = if (bindPort == corePort) this else copy(bindPort = corePort)

    /**
     * Builds the argv handed to `aether_core_start`.
     *
     * Order matters only for [extraArgs]: the core parses left to right into
     * environment variables, so a later token overrides an earlier one.
     */
    fun toArgs(configPath: String): List<String> = buildList {
        add("--bind"); add("127.0.0.1:$safeBindPort")
        add("--config"); add(configPath)
        add("--log-level"); add(logLevel.value)
        add("--perf"); add(perf.value)

        addAll(transport.args)

        validDns()?.let { add("--dns"); add(it) }
        add("--ip"); add(ipFamily.value)

        validExitLoc()?.let { add("--exit-loc"); add(it) }
        validPeer()?.let { add("--peer"); add(it) }
        validSni()?.let { add("--masque-sni"); add(it) }
        // --upstream, not --http-proxy: the latter only opens a second listener
        // of the core's own and dials out directly, so a user who typed an
        // upstream proxy still went straight to the internet.
        validUpstream()?.let { add("--upstream"); add(it) }

        add("--reconnect-secs"); add(reconnectSecs.coerceIn(MIN_RECONNECT, MAX_RECONNECT).toString())
        add(if (quickReconnect) "--quick-reconnect" else "--no-quick-reconnect")
        if (keepalive > 0) { add("--keepalive"); add(keepalive.coerceIn(0, MAX_KEEPALIVE).toString()) }

        if (fragment) {
            add("--fragment")
            validFragmentSize()?.let { add("--fragment-size"); add(it) }
            validFragmentDelay()?.let { add("--fragment-delay"); add(it) }
        }

        scanMode.value?.let { add("--scan"); add(it) }
        if (ironclad && scanMode != ScanMode.IRONCLAD) add("--ironclad")

        validEch()?.let { add("--ech"); add(it) }
        validRoutes(routeDirect)?.let { add("--route-direct"); add(it) }
        validRoutes(routeBlock)?.let { add("--route-block"); add(it) }

        addAll(sanitisedExtraArgs())
    }

    // ------------------------------------------------------------- validation

    /**
     * Everything that was wrong with this configuration, in the user's terms.
     *
     * Nothing here throws: a service that refuses to start because one field has
     * a typo is worse than one that starts with the field ignored and says so in
     * the log. The service writes this list to the log before every start, so a
     * setting that silently did nothing becomes visible.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()

        if (bindPort !in MIN_PORT..MAX_PORT) {
            out += "port: $bindPort is outside $MIN_PORT..$MAX_PORT; using $safeBindPort"
        }
        if (mtu !in MIN_MTU..MAX_MTU) {
            out += "mtu: $mtu is outside $MIN_MTU..$MAX_MTU; using $safeMtu"
        }
        if (reconnectSecs !in MIN_RECONNECT..MAX_RECONNECT) {
            out += "reconnect: $reconnectSecs s is outside $MIN_RECONNECT..$MAX_RECONNECT s"
        }

        val dnsParts = dnsTokens()
        if (dnsParts.isEmpty() && dns.isNotBlank()) {
            out += "dns: nothing usable in \"$dns\""
        } else if (dnsParts.count { !isIpLiteral(it) } > 0) {
            out += "dns: ${dnsParts.count { !isIpLiteral(it) }} entries are not addresses and were dropped"
        }

        if (exitLoc.isNotBlank() && validExitLoc() == null) out += "exit country: \"$exitLoc\" is not a two-letter code"
        if (peer.isNotBlank() && validPeer() == null) out += "peer: \"$peer\" is not host:port"
        if (masqueSni.isNotBlank() && validSni() == null) out += "sni: \"$masqueSni\" is not a host name"
        if (upstreamProxy.isNotBlank() && validUpstream() == null) {
            out += "upstream: \"$upstreamProxy\" is not socks5://host:port or http://host:port"
        }
        if (fragmentSize.isNotBlank() && validFragmentSize() == null) {
            out += "fragment size: \"$fragmentSize\" is not a number in $MIN_FRAGMENT..$MAX_FRAGMENT"
        }
        if (fragmentDelay.isNotBlank() && validFragmentDelay() == null) {
            out += "fragment delay: \"$fragmentDelay\" is not a number in 0..$MAX_FRAGMENT_DELAY"
        }
        if (ech.isNotBlank() && validEch() == null) out += "ech: \"$ech\" is not a base64 config"
        if (routeDirect.isNotBlank() && validRoutes(routeDirect) == null) {
            out += "routes (direct): contains characters a route list cannot have"
        }
        if (routeBlock.isNotBlank() && validRoutes(routeBlock) == null) {
            out += "routes (block): contains characters a route list cannot have"
        }

        if (extraArgs.isNotBlank()) {
            val split = splitExtraArgs(extraArgs)
            if (split.malformed) {
                out += "extra arguments: an unclosed quote; the whole field was ignored"
            }
            if (split.protectedFlags.isNotEmpty()) {
                out += "extra arguments: ${split.protectedFlags.joinToString(", ")} belong to the app and were removed"
            }
        }
        return out
    }

    /** The MTU actually written into the TUN and hev, kept in a workable range. */
    val safeMtu: Int get() = mtu.coerceIn(MIN_MTU, MAX_MTU)

    /** The port actually used for every listener, kept in the unprivileged range. */
    val safeBindPort: Int get() = bindPort.coerceIn(MIN_PORT, MAX_PORT)

    private fun dnsTokens(): List<String> =
        dns.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    private fun validDns(): String? =
        dnsTokens().filter { isIpLiteral(it) }.takeIf { it.isNotEmpty() }?.joinToString(",")

    private fun validExitLoc(): String? =
        exitLoc.trim().takeIf { LOCATION_CODE.matches(it) }?.uppercase()

    private fun validPeer(): String? = peer.trim().takeIf { HOST_PORT.matches(it) }

    private fun validSni(): String? = masqueSni.trim().takeIf { HOST_NAME.matches(it) }

    private fun validUpstream(): String? =
        upstreamProxy.trim().takeIf { PROXY_URL.matches(it) }

    private fun validFragmentSize(): String? =
        fragmentSize.trim().toIntOrNull()
            ?.takeIf { it in MIN_FRAGMENT..MAX_FRAGMENT }
            ?.toString()

    private fun validFragmentDelay(): String? =
        fragmentDelay.trim().toIntOrNull()
            ?.takeIf { it in 0..MAX_FRAGMENT_DELAY }
            ?.toString()

    /**
     * `--ech` takes a base64 ECH config, and the UI also offers the two words
     * the core understands by itself. Anything else is a typo that would make
     * the core reject its argv, so it is dropped instead.
     */
    private fun validEch(): String? {
        val value = ech.trim()
        if (value.equals("auto", ignoreCase = true) || value.equals("off", ignoreCase = true)) {
            return value.lowercase()
        }
        return value.takeIf { ECH_VALUE.matches(it) && it.length >= 8 }
    }

    private fun validRoutes(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return value.takeIf { ROUTE_LIST.matches(it) }
    }

    private fun isIpLiteral(value: String): Boolean {
        val text = value.trim().removeSurrounding("[", "]")
        return isIpv4Literal(text) || isIpv6Literal(text)
    }

    /**
     * True for four dotted octets, each 0..255.
     *
     * Parsed here rather than handed to [java.net.InetAddress]: a string that
     * merely looks numeric can make the resolver fall back to DNS, and this is
     * used to validate a field that is applied before the tunnel exists.
     */
    private fun isIpv4Literal(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toInt() in 0..255
        }
    }

    /**
     * True for an IPv6 literal, in any of its written forms.
     *
     * `::` may stand for at least one elided group, so it takes the count to 7
     * or fewer; without it there must be exactly eight. A dotted IPv4 tail is
     * allowed in the last position and counts as two groups. Zone ids are
     * refused: a DNS server address never carries one.
     */
    private fun isIpv6Literal(text: String): Boolean {
        if (!text.contains(':')) return false
        if (text.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) {
            return false
        }
        val halves = text.split("::")
        if (halves.size > 2) return false
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val tail = if (halves.size == 2) {
            if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
        } else {
            emptyList()
        }
        val groups = head + tail
        var count = 0
        for ((index, group) in groups.withIndex()) {
            if (group.isEmpty()) return false
            if (group.contains('.')) {
                // Only the final group may be an IPv4 address, and it fills the
                // last 32 bits, i.e. two 16-bit groups.
                if (index != groups.lastIndex || !isIpv4Literal(group)) return false
                count += 2
            } else {
                if (group.length > 4) return false
                count += 1
            }
        }
        return if (halves.size == 2) count <= 7 else count == 8
    }

    /**
     * The argv typed into [extraArgs], minus the flags this app owns.
     *
     * The field is the escape hatch for the core's long tail of options, not a
     * second way to configure the tunnel: letting `--bind` or `--config`
     * through would let a typo point the core somewhere the app does not expect,
     * and letting the transport selectors through would put the two halves of
     * the app into a state neither of them can describe. Each protected flag is
     * removed together with its value, because the core's parser would
     * otherwise take that value as a positional argument.
     */
    private fun sanitisedExtraArgs(): List<String> {
        if (extraArgs.isBlank()) return emptyList()
        val split = splitExtraArgs(extraArgs)
        if (split.malformed) return emptyList()
        return split.tokens
    }

    internal data class ExtraArgs(
        val tokens: List<String>,
        val protectedFlags: List<String>,
        val malformed: Boolean
    )

    /** Whitespace split that honours quoted values and reports an unclosed quote. */
    internal fun splitExtraArgs(raw: String): ExtraArgs {
        if (raw.isBlank()) return ExtraArgs(emptyList(), emptyList(), false)
        val kept = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var skipValue = false

        fun finish() {
            if (token.isEmpty()) return
            val text = token.toString()
            token.clear()
            if (skipValue) {
                // The value of a protected flag: removed with it.
                skipValue = false
                return
            }
            val name = text.trimStart('-')
            if (text.startsWith("-") && name.substringBefore('=').lowercase() in PROTECTED_FLAGS) {
                val flag = name.substringBefore('=').lowercase()
                blocked += "--$flag"
                // A value written as a separate token goes with the flag; one
                // written after '=' is already inside this token.
                if (!text.contains('=') && flag in PROTECTED_WITH_VALUE) skipValue = true
                return
            }
            kept += text
        }

        for (ch in raw) {
            when {
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> finish()
                else -> token.append(ch)
            }
        }
        finish()
        return ExtraArgs(kept, blocked, quote != null)
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
        put("psiphon", psiphon)
        put("psiphonRegion", psiphonRegion)
        put("psiphonProtocol", psiphonProtocol.name)
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

        /** Where the core listens when Psiphon has taken the user's port. */
        const val PSIPHON_CORE_PORT = 1820

        fun fromJson(raw: String?): RixOptions {
            if (raw.isNullOrBlank()) return RixOptions()
            return try {
                val o = JSONObject(raw)
                val base = RixOptions()
                RixOptions(
                    mode = o.optEnum("mode", RixMode.entries, base.mode),
                    transport = o.optEnum("transport", Transport.entries, base.transport),
                    autoFallback = o.optBoolean("autoFallback", base.autoFallback),
                    bindPort = o.optInt("bindPort", base.bindPort).coerceIn(MIN_PORT, MAX_PORT),
                    dns = o.optString("dns", base.dns),
                    ipFamily = o.optEnum("ipFamily", IpFamily.entries, base.ipFamily),
                    exitLoc = o.optString("exitLoc", base.exitLoc),
                    peer = o.optString("peer", base.peer),
                    masqueSni = o.optString("masqueSni", base.masqueSni),
                    upstreamProxy = o.optString("upstreamProxy", base.upstreamProxy),
                    psiphon = o.optBoolean("psiphon", base.psiphon),
                    psiphonRegion = o.optString("psiphonRegion", base.psiphonRegion)
                        .trim().uppercase().filter { it.isLetter() }.take(2),
                    psiphonProtocol = o.optEnum(
                        "psiphonProtocol",
                        PsiphonProtocol.entries,
                        base.psiphonProtocol
                    ),
                    reconnectSecs = o.optInt("reconnectSecs", base.reconnectSecs).coerceIn(MIN_RECONNECT, MAX_RECONNECT),
                    quickReconnect = o.optBoolean("quickReconnect", base.quickReconnect),
                    keepalive = o.optInt("keepalive", base.keepalive).coerceIn(0, MAX_KEEPALIVE),
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
                    mtu = o.optInt("mtu", base.mtu).coerceIn(MIN_MTU, MAX_MTU),
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

        // The bounds every normaliser in this file uses. Private, because they
        // are the app's own contract with the core, not something callers set.

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 9000
        private const val MIN_RECONNECT = 0
        private const val MAX_RECONNECT = 600
        private const val MAX_KEEPALIVE = 3600
        private const val MIN_FRAGMENT = 1
        private const val MAX_FRAGMENT = 65535
        private const val MAX_FRAGMENT_DELAY = 5000

        private val LOCATION_CODE = Regex("""^[A-Za-z]{2}$""")
        private val HOST_NAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$""")
        private val HOST_PORT = Regex("""^(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+):\d{1,5}$""")
        private val PROXY_URL = Regex("""^(socks5h?|https?)://(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+):\d{1,5}$""")
        private val ECH_VALUE = Regex("""^[A-Za-z0-9+/=_-]+$""")
        private val ROUTE_LIST = Regex("""^[A-Za-z0-9:.,/*!\- ]+$""")

        /**
         * Flags the app emits itself, or that belong to the other half of the
         * chain. Blocking them keeps the argv the core receives to one
         * description of the tunnel.
         */
        private val PROTECTED_FLAGS = setOf(
            "bind", "config", "log-level", "perf", "stats",
            "dns", "ip", "exit-loc", "peer", "masque-sni", "upstream",
            "reconnect-secs", "quick-reconnect", "no-quick-reconnect", "keepalive",
            "fragment", "fragment-size", "fragment-delay",
            "scan", "ironclad", "ech", "route-direct", "route-block",
            "masque", "h2", "wg", "gool", "gool-classic", "mim",
            "psiphon", "psiphon-bind", "psiphon-http", "psiphon-dir",
            "psiphon-region", "psiphon-upstream"
        )

        /**
         * The protected flags that take a value, either as the next token or
         * after `=`.
         *
         * Only these have their value removed with them. A switch such as
         * `--h2` is followed by whatever the user meant to keep, and eating the
         * next token would silently drop a legitimate option.
         */
        private val PROTECTED_WITH_VALUE = setOf(
            "bind", "config", "log-level", "perf",
            "dns", "ip", "exit-loc", "peer", "masque-sni", "upstream",
            "reconnect-secs", "keepalive", "fragment-size", "fragment-delay",
            "scan", "ech", "route-direct", "route-block",
            "psiphon-bind", "psiphon-http", "psiphon-dir",
            "psiphon-region", "psiphon-upstream"
        )
    }
}