package com.rix.vpn.core

import android.content.Context
import com.rix.vpn.data.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One process-wide owner for the native core and the tun2socks engine.
 *
 * Both the VPN service and the proxy service drive this object, so the UI has a
 * single source of truth no matter which mode is active, and a double start can
 * never leave two cores running.
 */
object RixRuntime {

    /**
     * [CONNECTING] is the state the UI was missing: the core is listening and
     * the engine is alive, but no packet has crossed the tunnel yet. Only
     * [RUNNING] means traffic actually flows.
     */
    enum class Phase { IDLE, STARTING, CONNECTING, RUNNING, STOPPING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: RixMode? = null,
        val message: String = "",
        val error: String? = null,
        val startedAt: Long = 0L,
        val socksPort: Int = 0,
        val tun2socks: Boolean = false,
        /** Public address the far end sees; empty until the first probe succeeds. */
        val egressIp: String = "",
        /** Cloudflare edge that answered, e.g. "FRA". */
        val egressColo: String = "",
        /** Two-letter country the edge placed us in, e.g. "DE". */
        val egressLoc: String = "",
        /** Human label of the transport that actually carried the tunnel. */
        val activeTransport: String = "",
        val txBytes: Long = 0L,
        val rxBytes: Long = 0L,
        val txPackets: Long = 0L,
        val rxPackets: Long = 0L
    )

    /** Thrown for a refused/failed start; carries the core's own message. */
    class CoreException(message: String) : Exception(message)

    /**
     * How many extra times a start is retried when hev returns immediately.
     * One retry is enough to burn a latched SYNC_STOP; two is belt and braces.
     */
    private const val HEV_START_ATTEMPTS = 2

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var jobId: Long = 0L

    @Volatile
    private var hevThread: Thread? = null

    fun idle() {
        jobId = 0L
        synchronized(hevLock) {
            hevThread = null
            hevInFlight = false
            hevStopRequested = false
        }
        _state.value = State()
    }

    private fun update(block: (State) -> State) {
        _state.value = block(_state.value)
    }

    fun markStarting(mode: RixMode, message: String) {
        update { it.copy(phase = Phase.STARTING, mode = mode, message = message, error = null) }
    }

    /**
     * The engine is up and listening, but the tunnel is not proven yet. The
     * clock deliberately does not start here: the session timer must measure
     * time connected, not time spent connecting.
     */
    fun markConnecting(port: Int, tun2socks: Boolean, message: String) {
        update {
            it.copy(
                phase = Phase.CONNECTING,
                message = message,
                error = null,
                socksPort = port,
                tun2socks = tun2socks,
                egressIp = "",
                egressColo = "",
                egressLoc = ""
            )
        }
    }

    /**
     * The tunnel is proven. [transport] is the one that actually carried it,
     * which is not necessarily the one the user picked: the fallback chain may
     * have settled on a different protocol, and the UI must not claim otherwise.
     */
    fun markRunning(
        port: Int,
        tun2socks: Boolean,
        egress: TunnelProbe.Egress? = null,
        transport: Transport? = null
    ) {
        update {
            it.copy(
                phase = Phase.RUNNING,
                message = "",
                error = null,
                startedAt = System.currentTimeMillis(),
                socksPort = port,
                tun2socks = tun2socks,
                egressIp = egress?.ip ?: it.egressIp,
                egressColo = egress?.colo ?: it.egressColo,
                egressLoc = egress?.loc ?: it.egressLoc,
                activeTransport = transport?.label ?: it.activeTransport
            )
        }
    }

    fun markStopping() = update { it.copy(phase = Phase.STOPPING, message = "") }

    fun markError(message: String) {
        LogBus.e("core", message)
        update { it.copy(phase = Phase.ERROR, message = "", error = message) }
    }

    fun markIdle() {
        update { State(mode = it.mode) }
    }

    // ---------------------------------------------------------------- paths

    fun configPath(context: Context): String {
        val dir = File(context.filesDir, "aether").apply { mkdirs() }
        return File(dir, "aether.toml").absolutePath
    }

    private fun hevConfigFile(context: Context): File =
        File(File(context.filesDir, "hev").apply { mkdirs() }, "tunnel.yml")

    /**
     * hev is handed an already-configured TUN fd, so it must NOT try to create
     * or configure an interface: `tunnel_init()` returns early for a supplied fd
     * (hev-socks5-tunnel.c) and only touches `socks5` + `misc`. The tunnel block
     * is still written because `hev_config_get_tunnel_offload()` is read before
     * that branch, and an absent section would be a parse edge case.
     */
    private fun writeHevConfig(context: Context, options: RixOptions): File {
        val file = hevConfigFile(context)
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${options.mtu}")
            appendLine("  multi-queue: false")
            appendLine("socks5:")
            appendLine("  port: ${options.bindPort}")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  connect-timeout: 8000")
            appendLine("  read-write-timeout: 120000")
            appendLine("  log-file: stderr")
            appendLine("  log-level: warn")
        }
        file.writeText(yaml)
        LogBus.i("hev", "config written to ${file.absolutePath}")
        return file
    }

    // ----------------------------------------------------------- core start

    /**
     * Starts the core as a cancellable job and returns its id.
     *
     * The core is a long-running SOCKS5 server, so `aether_core_start` answers
     * immediately with `{"ok":true,"job":N}`; progress is read with [poll].
     */
    suspend fun startCore(context: Context, options: RixOptions): Long = withContext(Dispatchers.IO) {
        val config = configPath(context)
        val args = options.toArgs(config)

        LogBus.i("core", "starting: ${args.joinToString(" ")}")

        val raw = NativeAether.startCore(args)
        LogBus.i("core", "reply: $raw")

        val json = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw CoreException("پاسخ هسته قابل خواندن نبود: $raw")
        }

        if (!json.optBoolean("ok")) {
            throw CoreException(json.optString("error", "هسته شروع نشد"))
        }

        val id = json.optLong("job", 0L)
        if (id <= 0L) throw CoreException("هسته شناسهٔ کار نداد: $raw")

        jobId = id
        id
    }

    /** Polls the job once. Returns the core's state string. */
    fun poll(): JobReport {
        if (jobId <= 0L) return JobReport("idle", null)
        val raw = NativeAether.poll(jobId) ?: return JobReport("running", null)
        return try {
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                JobReport("error", json.optString("error", raw))
            } else {
                JobReport(json.optString("state", "running"), json.optString("result", raw))
            }
        } catch (t: Throwable) {
            JobReport("unknown", raw)
        }
    }

    data class JobReport(val state: String, val detail: String?) {
        val running: Boolean get() = state == "running"
        val failed: Boolean get() = state == "error"
    }

    /**
     * Waits until the local SOCKS5 port actually accepts a TCP connection.
     *
     * Starting tun2socks before the proxy listens would make every early packet
     * fail and look like a broken tunnel, so this is a hard gate, not a sleep.
     */
    suspend fun awaitSocks(port: Int, timeoutMs: Long = 90_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val report = poll()
                if (report.failed) {
                    throw CoreException(report.detail ?: "هسته با خطا تمام شد")
                }
                if (!report.running) {
                    // The core exited before it served anything.
                    throw CoreException(report.detail ?: "هسته پیش از آمادهشدن بسته شد")
                }
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 700)
                        return@withContext true
                    }
                } catch (ignored: Exception) {
                    delay(400)
                }
            }
            false
        }

    /**
     * The transports to try for one connection, best bet first.
     *
     * The ranking is by what survives a hostile network rather than by speed:
     * MASQUE/HTTP-2 rides TCP 443 and is unaffected by UDP blocking, Gool and
     * MASQUE-in-MASQUE add camouflage on top of it, and the UDP-first options
     * come last. Fragmentation is switched on for the fallbacks because that is
     * the DPI workaround these candidates exist for; the user's own first
     * choice is passed through untouched.
     *
     * [remembered] is the transport that worked last time on this device, and it
     * is tried before the generic ranking so a second connect does not spend
     * the whole budget re-learning what the network blocks.
     */
    fun transportChain(
        options: RixOptions,
        remembered: Transport?,
        limit: Int = 3
    ): List<RixOptions> {
        if (!options.autoFallback) return listOf(options)

        val ranked = buildList {
            if (remembered != null) add(remembered)
            addAll(
                listOf(
                    Transport.MASQUE_H2,
                    Transport.GOOL,
                    Transport.MIM,
                    Transport.MASQUE_H3,
                    Transport.GOOL_CLASSIC,
                    Transport.WIREGUARD
                )
            )
        }

        val chain = mutableListOf(options)
        for (transport in ranked) {
            if (chain.size >= limit) break
            if (chain.any { it.transport == transport }) continue
            chain += options.copy(transport = transport, fragment = true)
        }
        return chain
    }

    private fun transportPrefs(context: Context) =
        context.getSharedPreferences("rix-runtime", Context.MODE_PRIVATE)

    /** The transport that last produced a verified tunnel, if any. */
    fun rememberedTransport(context: Context): Transport? {
        val name = transportPrefs(context).getString(KEY_TRANSPORT, null) ?: return null
        return Transport.entries.firstOrNull { it.name == name }
    }

    fun rememberTransport(context: Context, transport: Transport) {
        transportPrefs(context).edit().putString(KEY_TRANSPORT, transport.name).apply()
    }

    private const val KEY_TRANSPORT = "last-good-transport"

    /**
     * Waits until the tunnel really carries traffic, and reports the egress IP.
     *
     * A listening SOCKS5 port is not a working tunnel, so this is the gate the
     * UI calls "connected". The WARP handshake has no API to wait on, so the
     * only honest test is to send a request and see it come back; each attempt
     * is a full end-to-end round trip through the core.
     */
    suspend fun awaitTunnel(
        port: Int,
        timeoutMs: Long = 45_000,
        onProgress: (Int) -> Unit = {}
    ): TunnelProbe.Egress? = withContext(Dispatchers.IO) {
        // A deadline rather than a fixed attempt count: a refused connection
        // returns in milliseconds while a blackholed one eats the full socket
        // timeout, so counting attempts made the worst case several minutes of
        // «در حال اتصال» before it gave up.
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            onProgress(attempt)
            TunnelProbe.probe(port, timeoutMs = 6_000)?.let { return@withContext it }
            delay(500)
        }
        null
    }

    // ------------------------------------------------------------ tun2socks

    /**
     * hev keeps its run/stop handshake in one module-global `tsync` word:
     *
     *   stop()  -> if SYNC_SEND is set it kicks the event loop, ELSE it leaves
     *              SYNC_STOP latched in the word
     *   fini()  -> clears SYNC_SEND (so a later stop() takes the latching path)
     *   run()   -> if SYNC_STOP is latched it clears it and returns immediately,
     *              and main_inner() then returns the *init* result, i.e. 0
     *
     * A stop() issued after teardown therefore poisons the next start: it dies
     * instantly with rc=0 and looks like "the engine stopped by itself", and the
     * error path's own stop() latches the bit again, so every retry fails too.
     *
     * Two defences, because the bit is not reachable through the public API:
     *  1. never call stop() unless a run() call is actually in flight;
     *  2. if run() returns 0 and we did not ask it to stop, treat it as a
     *     consumed latch and call run() once more (that call clears the bit).
     */
    private val hevLock = Any()

    /** True only while [NativeHev.run] is executing inside the library. */
    @Volatile
    private var hevInFlight = false

    @Volatile
    private var hevStopRequested = false

    private var hevGeneration = 0

    /** Whether a tunnel engine is currently blocking inside hev. */
    fun hevActive(): Boolean = hevInFlight

    fun startTun2Socks(
        context: Context,
        options: RixOptions,
        tunFd: Int,
        onExit: (Int) -> Unit
    ): Int {
        val config = writeHevConfig(context, options)
        val generation: Int
        synchronized(hevLock) {
            hevGeneration += 1
            generation = hevGeneration
            hevStopRequested = false
        }

        val thread = Thread({
            var rc = -1
            var attempt = 0
            while (attempt <= HEV_START_ATTEMPTS) {
                // A stop that arrived before we entered the library needs no
                // signal: hev was never started, so nothing is running.
                if (hevStopRequested && !hevInFlight) {
                    rc = 0
                    break
                }
                hevInFlight = true
                rc = try {
                    NativeHev.run(config.absolutePath, tunFd)
                } catch (t: Throwable) {
                    LogBus.e("hev", t.message ?: "خطای اجرای tun2socks")
                    -1
                }
                hevInFlight = false

                val stoppedOnPurpose = hevStopRequested
                if (rc != 0 || stoppedOnPurpose) break
                if (attempt >= HEV_START_ATTEMPTS) break

                attempt += 1
                LogBus.i("hev", "engine returned at once; starting again (retry $attempt)")
                try {
                    Thread.sleep(120)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            if (generation == hevGeneration) onExit(rc)
        }, "rix-tun2socks")

        thread.isDaemon = true
        synchronized(hevLock) { hevThread = thread }
        thread.start()
        LogBus.i("hev", "tun2socks started on fd $tunFd")
        return generation
    }

    /** Waits briefly for the engine to stay alive; false means it died at once. */
    suspend fun awaitHevAlive(timeoutMs: Long = 2000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val thread = hevThread ?: return false
            if (thread.isAlive && hevInFlight) return true
            if (!thread.isAlive) return false
            delay(50)
        }
        return hevInFlight
    }

    /**
     * Stops the engine that [startTun2Socks] returned [token] for.
     *
     * A token that is no longer the live generation belongs to a run that has
     * already been replaced. Signalling hev for it would latch SYNC_STOP and
     * kill the run that replaced it, so a stale token stops nothing; `null`
     * means the caller never started an engine at all.
     */
    fun stopTun2Socks(token: Int?) {
        val thread: Thread?
        synchronized(hevLock) {
            if (token == null || token != hevGeneration) return
            hevStopRequested = true
            thread = hevThread
        }
        // Nothing was ever started: calling stop() here is exactly what latches
        // SYNC_STOP and breaks the next connection.
        if (thread == null) return

        // Give the worker a moment to either enter run() or honour the
        // stop request on its own, then signal hev only if it is really inside.
        var waited = 0
        while (waited < 400 && thread.isAlive && !hevInFlight) {
            try {
                Thread.sleep(10)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += 10
        }
        if (hevInFlight) {
            runCatching { NativeHev.stop() }
                .onFailure { LogBus.e("hev", it.message ?: "توقف tun2socks ناموفق بود") }
        }

        try {
            thread.join(2500)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(hevLock) {
            if (hevThread === thread) hevThread = null
        }
    }

    fun stats(): LongArray = try {
        NativeHev.stats()
    } catch (t: Throwable) {
        longArrayOf(0, 0, 0, 0)
    }

    fun pushStats() {
        val s = stats()
        update {
            it.copy(
                txPackets = s[0], txBytes = s[1],
                rxPackets = s[2], rxBytes = s[3]
            )
        }
    }

    // ------------------------------------------------------------ stop

    suspend fun stopCore() {
        if (jobId <= 0L) return
        val id = jobId
        withContext(Dispatchers.IO) {
            try {
                LogBus.i("core", "cancel: ${NativeAether.cancel(id)}")
                // Give the job loop a moment to observe the cancel before the
                // slot is released; freeing first can race the worker.
                delay(250)
                NativeAether.free(id)
            } catch (t: Throwable) {
                LogBus.e("core", t.message ?: "توقف هسته ناموفق بود")
            }
        }
        jobId = 0L
    }
}