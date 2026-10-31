package com.rix.vpn.core

import android.content.Context
import android.util.Base64
import ca.psiphon.PsiphonTunnel
import com.rix.vpn.data.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Psiphon, as the last hop of the tunnel. BETA.
 *
 * The shape of the chain is `transport → Psiphon`: the core is started exactly
 * as it always is and serves its own socks5 listener, and Psiphon is told to
 * reach its servers *through* that listener (`UpstreamProxyURL`). Traffic then
 * goes
 *
 *     apps → TUN → hev → Psiphon (127.0.0.1:[RixOptions.bindPort])
 *                     → the core (127.0.0.1:[RixOptions.corePort])
 *                     → WARP → a Psiphon server → internet
 *
 * so every packet that leaves the phone is inside a WARP tunnel before it even
 * reaches Psiphon, and the address the far end sees is a Psiphon exit in the
 * country the user picked rather than a Cloudflare one. Putting Psiphon first
 * and WARP behind it would have been the easier wiring -- one flag, no second
 * proxy -- but then the exit would be Cloudflare's again and the country
 * selector would be a lie.
 *
 * Everything above this class talks to one port, [RixOptions.bindPort], and does
 * not need to know which of the two is serving it: hev's config, the traffic
 * probes and the UI all address Psiphon when it is on, and the core when it is
 * not.
 *
 * Psiphon is GPLv3 and RIX is AGPL-3.0; the two are compatible in this
 * direction, and the library is used unmodified as a Maven dependency.
 */
object PsiphonBridge {

    /**
     * Psiphon identifies an app to its servers with a propagation channel and a
     * sponsor. These are the values aether ships for its own Psiphon mode
     * (aether/src/psiphon.rs); the channel is what tells the Psiphon servers
     * which client is asking, and the pair is what makes a server list usable.
     */
    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "FFFFFFFFFFFFFFFF"

    private const val SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

    private const val SERVER_LIST_SIGNATURE_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    /**
     * How long the socks5 listener gets to answer a connection.
     *
     * The port is fixed in the config, so the listener is bound while the
     * controller starts rather than when a tunnel is established; this budget
     * therefore covers library start-up, not the handshake with a Psiphon
     * server (that is the caller's probe, which has its own minute).
     */
    private const val LISTEN_TIMEOUT_MS = 45_000L

    private val lock = Any()

    private var tunnel: PsiphonTunnel? = null

    @Volatile
    private var running = false

    /**
     * True once a start has failed for a reason that clearing the saved state
     * cannot fix -- the native library itself not loading. Kept so the retry
     * does not run pointlessly.
     */
    @Volatile
    private var broken = false

    /**
     * The port Psiphon is serving, 0 when it is not.
     *
     * Written from the library's callback thread, read from the services and
     * from the UI, so it is volatile rather than merely private.
     */
    @Volatile
    var socksPort: Int = 0
        private set

    /** Whether a Psiphon tunnel is up right now. */
    fun isRunning(): Boolean = running

    /**
     * Starts Psiphon against [upstreamPort] and waits until it serves [listenPort].
     *
     * Returns true when the local socks5 listener accepts connections, which is
     * as far as this class can honestly check: whether a Psiphon tunnel was
     * established is decided by the traffic probe the caller runs next.
     *
     * A start that fails before the listener exists is retried once against a
     * wiped data directory. The usual cause of that failure is state an earlier
     * attempt left behind -- a datastore written by a process that was killed
     * mid-write, or one from another version of the library -- and tunnel-core
     * refuses to start against it rather than repairing it. Clearing it is what
     * a reinstall would have done.
     */
    suspend fun start(
        context: Context,
        upstreamPort: Int,
        listenPort: Int,
        region: String,
        protocol: PsiphonProtocol
    ): Boolean = withContext(Dispatchers.IO) {
        // Recomputed per connect rather than remembered forever: a library that
        // failed to load once is retried once per connection and reported each
        // time, instead of a single early failure disabling Psiphon for the
        // rest of the process.
        broken = false
        if (startOnce(context, upstreamPort, listenPort, region, protocol)) {
            return@withContext true
        }
        if (broken) return@withContext false

        LogBus.i("psiphon", "start failed; clearing the saved Psiphon state and trying once more")
        stop()
        wipeState(context)
        startOnce(context, upstreamPort, listenPort, region, protocol)
    }

    private suspend fun startOnce(
        context: Context,
        upstreamPort: Int,
        listenPort: Int,
        region: String,
        protocol: PsiphonProtocol
    ): Boolean {
        stop()

        val app = context.applicationContext
        val dataDir = File(app.filesDir, "psiphon")
        if (!dataDir.isDirectory && !dataDir.mkdirs()) {
            // Commit(true) refuses a data root that does not exist, and that
            // refusal is what surfaces as "failed to start Psiphon library"
            // with the reason hidden.
            LogBus.e("psiphon", "cannot create ${dataDir.absolutePath}")
            return false
        }
        val config = buildConfig(app, dataDir, upstreamPort, listenPort, region, protocol)
        LogBus.i(
            "psiphon",
            "starting: upstream socks5://127.0.0.1:$upstreamPort, " +
                "listening on $listenPort, region ${region.ifEmpty { "auto" }}, ${protocol.name}"
        )

        val host = Host(app, config)
        val instance = try {
            PsiphonTunnel.newPsiphonTunnel(host)
        } catch (t: Throwable) {
            // The constructor loads libgojni. A LinkageError here is the one
            // failure a retry cannot help, and it is logged with its whole
            // cause chain because "failed to start Psiphon library" without it
            // is what left this broken for so long.
            broken = t is LinkageError
            LogBus.e("psiphon", "the library would not start: ${describe(t)}")
            return false
        }

        // Not in VPN mode: RIX owns the only VpnService in the process, and a
        // second one would be refused by the system anyway.
        instance.setVpnMode(false)

        synchronized(lock) {
            tunnel = instance
            running = true
            socksPort = 0
        }

        val worker = Thread({
            try {
                // Asynchronous: it returns once the library is running, and the
                // tunnel comes up behind it.
                instance.startTunneling("")
                LogBus.i("psiphon", "library is running")
            } catch (t: Throwable) {
                // "failed to start Psiphon library" is the library's own wrapper
                // around the real reason (a config commit error, a datastore it
                // cannot open, a controller that refused); the cause chain is
                // the only thing that says which.
                LogBus.e("psiphon", "start failed: ${describe(t)}")
                host.failed = true
                if (t is LinkageError) broken = true
            }
        }, "rix-psiphon")
        worker.isDaemon = true
        worker.start()

        val up = try {
            awaitListener(host, listenPort)
        } catch (t: Throwable) {
            // Cancelled -- the session was stopped while Psiphon was still
            // coming up. Stopping here too, because a library left running has
            // no reference left to stop it and holds the port the next connect
            // wants.
            stop()
            throw t
        }

        if (!up) {
            LogBus.e("psiphon", "no socks5 listener on 127.0.0.1:$listenPort in time")
            stop()
        }
        return up
    }

    /**
     * Waits until the socks5 listener accepts.
     *
     * The connection is the proof, and nothing else is: the library announces
     * its port on a notice that only arrives once a tunnel is established, so
     * waiting for it meant declaring a working listener dead. A port that is
     * already taken by something else, on the other hand, is a failure even
     * though it accepts -- a connection to a stranger's listener would make the
     * rest of the chain talk to the wrong proxy.
     */
    private suspend fun awaitListener(host: Host, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!running) return false
            if (host.portInUse) {
                LogBus.e("psiphon", "port $port is serving something else")
                return false
            }
            if (host.failed) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 700) }
                if (!running) return false
                socksPort = port
                LogBus.i("psiphon", "socks5 listener is up on 127.0.0.1:$port")
                return true
            } catch (ignored: Exception) {
                delay(300)
            }
        }
        return false
    }

    /**
     * Tears the library down.
     *
     * `stop()` blocks for a few seconds while tunnel-core shuts its controller
     * down, and it deadlocks if called from one of its own callbacks, so it is
     * never called on the main thread -- and never from [Host].
     */
    fun stop() {
        val instance = synchronized(lock) {
            val held = tunnel
            tunnel = null
            running = false
            socksPort = 0
            held
        } ?: return

        runCatching { instance.stop() }
            .onFailure { LogBus.e("psiphon", "stop: ${describe(it)}") }
    }

    /** The same teardown, on a thread of its own, for service destruction. */
    fun stopAsync() {
        if (tunnel == null) return
        Thread({ stop() }, "rix-psiphon-stop").apply { isDaemon = true }.start()
    }

    /**
     * Removes the data root, which is where tunnel-core keeps its datastore and
     * its downloaded server list. Only ever called while stopped.
     */
    private fun wipeState(context: Context) {
        val dir = File(context.filesDir, "psiphon")
        val cleared = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (cleared) {
            LogBus.i("psiphon", "saved state cleared")
        } else {
            LogBus.e("psiphon", "could not clear ${dir.absolutePath}")
        }
        runCatching { dir.mkdirs() }
    }

    /**
     * A throwable and everything that caused it, on one line.
     *
     * The library wraps every start failure in an Exception whose message says
     * only "failed to start Psiphon library"; the sentence that actually names
     * the problem is always one or two causes down.
     */
    private fun describe(t: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            if (depth > 0) out.append(" <- ")
            out.append(current.javaClass.simpleName)
            current.message?.takeIf { it.isNotBlank() }?.let { out.append(": ").append(it) }
            val cause = current.cause
            current = if (cause === current) null else cause
            depth += 1
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ config

    /**
     * The tunnel-core config, built the way aether builds its own.
     *
     * The keys that matter here are `UpstreamProxyURL`, which is what makes the
     * chain rather than two tunnels side by side, and `EgressRegion`, which is
     * the country the user asked for. `LimitTunnelProtocols` drops the
     * QUIC-based shapes: they need UDP of their own, and nothing goes through a
     * socks5 upstream except TCP.
     */
    private fun buildConfig(
        context: Context,
        dataDir: File,
        upstreamPort: Int,
        listenPort: Int,
        region: String,
        protocol: PsiphonProtocol
    ): String {
        return JSONObject().apply {
            put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            put("SponsorId", SPONSOR_ID)
            put("ClientVersion", "1")
            put(
                "RemoteServerListURLs",
                JSONArray().put(
                    JSONObject().put(
                        "URL",
                        Base64.encodeToString(
                            SERVER_LIST_URL.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )
                    )
                )
            )
            put("RemoteServerListSignaturePublicKey", SERVER_LIST_SIGNATURE_KEY)

            // Nothing here runs Psiphon's in-proxy or relay modes.
            put("InproxyTunnelProtocolPreferProbability", 0.0)
            put("InproxyTunnelProtocolSelectionProbability", 0.0)

            put("LimitTunnelProtocols", JSONArray(protocol.protocols))
            // Tactics can hand back a protocol the upstream cannot carry, which
            // would fail the connection after it looked like it was starting.
            put("DisableTactics", true)

            if (region.length == 2) put("EgressRegion", region)

            put("UpstreamProxyURL", "socks5://127.0.0.1:$upstreamPort")

            // Android will not let the library use a temporary directory, so the
            // app's own storage is named explicitly.
            put("DataRootDirectory", dataDir.absolutePath)
            put("LocalSocksProxyPort", listenPort)
            put("DisableLocalHTTPProxy", true)

            put("EmitDiagnosticNotices", true)
            put("EmitBytesTransferred", false)
        }.toString()
    }

    // -------------------------------------------------------------------- host

    /**
     * The callbacks the library needs from its host app.
     *
     * Only [getPsiphonConfig] and [getContext] are required; the rest are the
     * library's own defaults. The notices are forwarded to the in-app log
     * because Psiphon says exactly why it cannot connect -- "no server", "port
     * in use", "upstream proxy error" -- and without them a failure here would
     * look like a tunnel that simply never comes up.
     */
    private class Host(
        private val context: Context,
        private val config: String
    ) : PsiphonTunnel.HostService {

        /** Set by the start thread when startTunneling threw. */
        @Volatile
        var failed = false

        /** Set by the library when it could not bind the port we asked for. */
        @Volatile
        var portInUse = false

        override fun getContext(): Context = context

        override fun getPsiphonConfig(): String = config

        /**
         * Loading the native library is the one step that happens before any
         * notice exists, so a failure here used to be silent: the constructor
         * threw, the caller logged the wrapper's message and Psiphon was simply
         * never there. It is caught here to get the loader's own reason -- the
         * dlopen sentence names the missing dependency -- and to retry from the
         * extraction directory, which the namespace lookup can miss on some
         * devices.
         */
        override fun loadLibrary(library: String) {
            try {
                System.loadLibrary(library)
                return
            } catch (first: UnsatisfiedLinkError) {
                val dir = context.applicationInfo.nativeLibraryDir
                val candidate = if (dir.isNullOrEmpty()) {
                    null
                } else {
                    File(dir, System.mapLibraryName(library))
                }
                if (candidate != null && candidate.exists()) {
                    try {
                        System.load(candidate.absolutePath)
                        LogBus.i("psiphon", "loaded $library from $dir")
                        return
                    } catch (second: Throwable) {
                        LogBus.e("psiphon", "loading ${candidate.absolutePath}: ${describe(second)}")
                    }
                }
                LogBus.e("psiphon", "native library $library did not load: ${describe(first)}")
                throw first
            }
        }

        override fun onDiagnosticMessage(message: String) {
            LogBus.i("psiphon", message)
        }

        override fun onListeningSocksProxyPort(port: Int) {
            // Only a notice: the listener may have been serving for a while,
            // which is why the caller checks the port itself.
            LogBus.i("psiphon", "library announces its socks5 port $port")
        }

        override fun onSocksProxyPortInUse(port: Int) {
            LogBus.e("psiphon", "port $port is already in use")
            portInUse = true
        }

        override fun onUpstreamProxyError(message: String) {
            // The one failure that is specific to this chain: the core's socks5
            // listener refused or could not carry Psiphon's own connections.
            LogBus.e("psiphon", "upstream proxy: $message")
        }

        override fun onConnected() {
            LogBus.i("psiphon", "tunnel established")
        }

        override fun onConnecting() {
            LogBus.i("psiphon", "connecting")
        }

        override fun onAvailableEgressRegions(regions: List<String>) {
            LogBus.i("psiphon", "egress regions: ${regions.joinToString(",")}")
        }

        override fun onClientRegion(region: String) {
            LogBus.i("psiphon", "client region: $region")
        }

        override fun onExiting() {
            LogBus.i("psiphon", "exiting")
        }
    }
}