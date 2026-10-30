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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** How long Psiphon gets to announce its socks5 listener. */
    private const val LISTEN_TIMEOUT_MS = 25_000L

    /** How long that announced listener then gets to accept a connection. */
    private const val ACCEPT_TIMEOUT_MS = 8_000L

    private val lock = Any()

    private var tunnel: PsiphonTunnel? = null

    @Volatile
    private var running = false

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
     */
    suspend fun start(
        context: Context,
        upstreamPort: Int,
        listenPort: Int,
        region: String,
        protocol: PsiphonProtocol
    ): Boolean = withContext(Dispatchers.IO) {
        stop()

        val app = context.applicationContext
        val config = buildConfig(app, upstreamPort, listenPort, region, protocol)
        LogBus.i(
            "psiphon",
            "starting: upstream socks5://127.0.0.1:$upstreamPort, " +
                "listening on $listenPort, region ${region.ifEmpty { "auto" }}, ${protocol.name}"
        )

        val listening = CountDownLatch(1)
        val host = Host(app, config, listening)

        val instance = try {
            PsiphonTunnel.newPsiphonTunnel(host)
        } catch (t: Throwable) {
            LogBus.e("psiphon", "library refused to start: ${t.message}")
            return@withContext false
        }

        // Not in VPN mode: RIX owns the only VpnService in the process, and a
        // second one would be refused by the system anyway.
        instance.setVpnMode(false)

        val worker = Thread({
            try {
                // Asynchronous: it returns once the library is running, and the
                // tunnel comes up behind it. Anything it throws comes back as a
                // diagnostic message, but a hard failure surfaces here.
                instance.startTunneling("")
            } catch (t: Throwable) {
                LogBus.e("psiphon", "start failed: ${t.message}")
                listening.countDown()
            }
        }, "rix-psiphon")

        synchronized(lock) {
            tunnel = instance
            running = true
        }
        worker.isDaemon = true
        worker.start()

        val up = try {
            awaitListener(listenPort, listening)
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
        up
    }

    /**
     * Waits for the socks5 listener to accept.
     *
     * The library announces the port on a notice before it accepts on it, so
     * the announcement is only a hint about when to start trying; the
     * connection itself is the proof.
     */
    private suspend fun awaitListener(port: Int, announced: CountDownLatch): Boolean {
        announced.await(LISTEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!running) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 700) }
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
            .onFailure { LogBus.e("psiphon", "stop: ${it.message}") }
    }

    /** The same teardown, on a thread of its own, for service destruction. */
    fun stopAsync() {
        if (tunnel == null) return
        Thread({ stop() }, "rix-psiphon-stop").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ config

    /**
     * The tunnel-core config, built the way aether builds its own.
     *
     * The keys that matter here are [UpstreamProxyURL], which is what makes the
     * chain rather than two tunnels side by side, and `EgressRegion`, which is
     * the country the user asked for. `LimitTunnelProtocols` drops the
     * QUIC-based shapes: they need UDP of their own, and nothing goes through a
     * socks5 upstream except TCP.
     */
    private fun buildConfig(
        context: Context,
        upstreamPort: Int,
        listenPort: Int,
        region: String,
        protocol: PsiphonProtocol
    ): String {
        val dataDir = File(context.filesDir, "psiphon").apply { mkdirs() }

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
        private val config: String,
        private val listening: CountDownLatch
    ) : PsiphonTunnel.HostService {

        override fun getContext(): Context = context

        override fun getPsiphonConfig(): String = config

        override fun onDiagnosticMessage(message: String) {
            LogBus.i("psiphon", message)
        }

        override fun onListeningSocksProxyPort(port: Int) {
            // The port was fixed in the config, so this is only the signal that
            // the listener is about to be there.
            listening.countDown()
        }

        override fun onSocksProxyPortInUse(port: Int) {
            LogBus.e("psiphon", "port $port is already in use")
            listening.countDown()
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
            listening.countDown()
        }
    }
}
