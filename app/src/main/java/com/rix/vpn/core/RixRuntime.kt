package com.rix.vpn.core

import android.content.Context
import android.system.Os
import com.rix.vpn.data.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        /** Country the exit address is registered in, when a lookup answered. */
        val egressCountry: String = "",
        /** City of the exit address, when a lookup answered. */
        val egressCity: String = "",
        /** Network the exit address belongs to, e.g. "Cloudflare, Inc.". */
        val egressIsp: String = "",
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

    /** Written from the service threads and from the disposal thread. */
    @Volatile
    private var jobId: Long = 0L

    private val jobLock = Any()

    /**
     * Claims the live job id and leaves none behind.
     *
     * Disposal runs from two places that can race — the suspended [stopCore]
     * and the thread [stopCoreAsync] uses — and freeing one id twice is a
     * native double free. Claiming under a lock makes exactly one of them the
     * owner of the free.
     */
    private fun takeJobId(): Long = synchronized(jobLock) {
        val id = jobId
        jobId = 0L
        id
    }

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
                egressLoc = "",
                egressCountry = "",
                egressCity = "",
                egressIsp = ""
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
            val proven = it.copy(
                phase = Phase.RUNNING,
                message = "",
                error = null,
                startedAt = System.currentTimeMillis(),
                socksPort = port,
                tun2socks = tun2socks,
                activeTransport = transport?.label ?: it.activeTransport
            )
            if (egress != null) proven.withEgress(egress) else proven
        }
    }

    /**
     * Re-reads where the traffic is coming out while the session runs.
     *
     * A tunnel's exit is not fixed for its lifetime: the core reconnects by
     * itself after a network change, and Cloudflare can hand the new connection
     * a different edge. The tile used to keep the address from the moment of
     * connecting, which is exactly the number a user looks at to know where they
     * appear to be -- so it is re-read on a slow timer, and the lookup that adds
     * the city and the network is only used to enrich it.
     */
    suspend fun refreshEgress(port: Int) {
        if (port <= 0) return
        val base = TunnelProbe.probe(port, timeoutMs = 5_000) ?: return
        val full = TunnelProbe.enrich(base, port)
        update { if (it.phase == Phase.RUNNING) it.withEgress(full) else it }
    }

    private fun State.withEgress(e: TunnelProbe.Egress): State = copy(
        egressIp = e.ip,
        egressColo = e.colo ?: egressColo,
        egressLoc = e.loc ?: egressLoc,
        egressCountry = e.country ?: egressCountry,
        egressCity = e.city ?: egressCity,
        egressIsp = e.isp ?: egressIsp
    )

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

        synchronized(jobLock) { jobId = id }
        id
    }

    /** Polls the job once. Returns the core's state string. */
    fun poll(): JobReport {
        // One read: disposal can zero the field between a check and its use,
        // and polling id 0 is a question the core cannot answer.
        val id = jobId
        if (id <= 0L) return JobReport("idle", null)
        val raw = NativeAether.poll(id) ?: return JobReport("running", null)
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

    /**
     * The TUN descriptor hev was handed.
     *
     * hev does not own it: `tunnel_init(extern_tun_fd)` returns early for a
     * supplied descriptor without setting `tun_fd_local`, and `tunnel_fini()`
     * only closes the descriptor when that flag is set. Whoever detached it --
     * the VPN service, through [android.os.ParcelFileDescriptor.detachFd] --
     * has given up the object that could, so this object keeps the number and
     * closes it. That close is what tells the system the app no longer holds a
     * VPN, i.e. it is what takes the key icon away.
     */
    private var tunFd: Int = -1
    private var tunFdClosed = true

    /** Whether a tunnel engine is currently blocking inside hev. */
    fun hevActive(): Boolean = hevInFlight

    fun startTun2Socks(
        context: Context,
        options: RixOptions,
        descriptor: Int,
        onExit: (Int) -> Unit
    ): Int {
        val config = writeHevConfig(context, options)
        val generation: Int
        synchronized(hevLock) {
            hevGeneration += 1
            generation = hevGeneration
            hevStopRequested = false
            // A descriptor from a previous generation is dead by definition:
            // the caller only starts a new engine after tearing the old one
            // down. Closing it here keeps a failed teardown from leaving the
            // interface (and the key icon) behind.
            val stale = if (!tunFdClosed) tunFd else -1
            if (stale >= 0) closeQuietly(stale)
            tunFd = descriptor
            tunFdClosed = false
        }

        val thread = Thread({
            try {
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
                        NativeHev.run(config.absolutePath, descriptor)
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
            } finally {
                // The engine is done with the descriptor, and hev will never
                // close one it was handed: this is the only close.
                closeTunFd(generation)
            }
        }, "rix-tun2socks")

        thread.isDaemon = true
        synchronized(hevLock) { hevThread = thread }
        thread.start()
        LogBus.i("hev", "tun2socks started on fd $descriptor")
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
        // The worker closes the descriptor on its way out; this covers the one
        // case it cannot -- the engine is stuck inside the library and the join
        // above timed out. Closing the descriptor there makes hev's poll return
        // EBADF, so this is also what forces a wedged engine out.
        closeTunFd()
    }

    /**
     * Closes the TUN descriptor hev was handed, whoever got there first.
     *
     * The system tears a VPN down when the last descriptor for its interface is
     * closed. Nothing else in the app holds one -- the service detached it --
     * so missing this call was the reason the key icon stayed in the status bar
     * after a disconnect.
     */
    fun closeTunFd() {
        closeTunFd(-1)
    }

    /** [generation] of -1 means "whatever the current engine owns". */
    private fun closeTunFd(generation: Int) {
        val descriptor = synchronized(hevLock) {
            if (generation >= 0 && generation != hevGeneration) return
            if (tunFdClosed) return
            tunFdClosed = true
            val held = tunFd
            tunFd = -1
            held
        }
        closeQuietly(descriptor)
    }

    private fun closeQuietly(descriptor: Int) {
        if (descriptor < 0) return
        runCatching { Os.close(descriptor) }
            .onSuccess { LogBus.i("hev", "TUN fd $descriptor closed") }
            .onFailure { LogBus.e("hev", "closing TUN fd $descriptor: ${it.message}") }
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
        val id = takeJobId()
        if (id <= 0L) return
        // NonCancellable: the caller is usually a service whose scope is
        // cancelled moments later, and an id claimed but never freed leaks the
        // core slot for the rest of the process's life.
        withContext(Dispatchers.IO + NonCancellable) {
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
    }

    /**
     * The same disposal, without suspending.
     *
     * For `onDestroy`, where the coroutine scope that would run [stopCore] is
     * cancelled a few lines later — a launch there is cancelled before it is
     * ever dispatched, which silently leaves the core running. It uses a
     * detached thread rather than blocking the main thread for the 250 ms the
     * job loop needs to observe the cancel.
     */
    fun stopCoreAsync() {
        Thread({ stopCoreBlocking() }, "rix-core-dispose").apply { isDaemon = true }.start()
    }

    /**
     * The whole chain, in the order it has to come apart.
     *
     * Psiphon first: when it is in the path it sits *on top of* the core's own
     * socks5 listener, and freeing the core underneath a live Psiphon leaves it
     * retrying an upstream that no longer exists -- which is what kept the
     * notification and the key icon alive for a few seconds after a stop.
     */
    fun stopChainAsync() {
        Thread({
            PsiphonBridge.stop()
            stopCoreBlocking()
        }, "rix-chain-dispose").apply { isDaemon = true }.start()
    }

    /** The blocking body of both disposals; claims the job id exactly once. */
    private fun stopCoreBlocking() {
        val id = takeJobId()
        if (id <= 0L) return
        runCatching { NativeAether.cancel(id) }
            .onFailure { LogBus.e("core", it.message ?: "توقف هسته ناموفق بود") }
        try {
            Thread.sleep(250)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { NativeAether.free(id) }
            .onFailure { LogBus.e("core", it.message ?: "آزادسازی هسته ناموفق بود") }
    }
}