package com.rix.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rix.vpn.MainActivity
import com.rix.vpn.R
import com.rix.vpn.RixApp
import com.rix.vpn.core.PsiphonBridge
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.core.TunnelProbe
import com.rix.vpn.data.LogBus
import com.rix.vpn.data.l10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-tunnel mode.
 *
 * Packet path: every app -> TUN (this service) -> hev userspace TCP/IP ->
 * SOCKS5 on 127.0.0.1:<port> -> Aether core -> WARP / MASQUE -> internet.
 */
class RixVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Volatile: [isUp] reads it from the UI thread to spot a stray tunnel. */
    @Volatile
    private var tun: ParcelFileDescriptor? = null
    private var worker: Job? = null
    private var ticker: Job? = null
    private var watch: Job? = null

    /**
     * Bumped once per bring-up. A teardown captures the value it was started
     * for, so the deferred half of a shutdown can tell whether the session it
     * was cleaning up is still the one that owns this service.
     */
    private val session = AtomicInteger(0)

    /** The hev generation this instance started; null when it started none. */
    @Volatile
    private var hevToken: Int? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LogBus.i("vpn", "stop requested")
                // The UI stops both services without checking which one is
                // live, so this command also reaches a service that was never
                // started -- created on the spot, with no session of its own.
                // That one has nothing to tear down and must not publish a
                // phase or dispose the core, which at that moment belongs to
                // the other service's live session.
                if (worker == null) stopSelf() else shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_OPTIONS)
                val options = RixOptions.fromJson(raw)
                startForegroundSafely(l10n(R.string.notif_starting))
                if (worker?.isActive == true) {
                    LogBus.i("vpn", "already starting or running; ignoring duplicate start")
                    return START_NOT_STICKY
                }
                worker = scope.launch { bringUp(options) }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------- bring-up

    private suspend fun bringUp(options: RixOptions) {
        val mine = session.incrementAndGet()
        try {
            // A setting that had to be corrected or dropped is said out loud,
            // once, before anything is started: otherwise the field looks like
            // it did something and the user has no way to find out it did not.
            options.problems().forEach { LogBus.e("vpn", "settings: $it") }
            RixRuntime.markStarting(RixMode.TUNNEL, l10n(R.string.state_starting))

            val fd = establishTun(options)
            LogBus.i("vpn", "TUN established, fd=${fd.fd}")

            // WireGuard and MASQUE/HTTP-3 are UDP-first, and on the networks this
            // app is used on UDP is the first thing that gets blocked. A single
            // fixed transport therefore means "وصل نمیشود" rather than "کمی
            // کندتر", so the chosen protocol is only the first candidate.
            val chain = RixRuntime.transportChain(options, RixRuntime.rememberedTransport(this))
            LogBus.i("vpn", "transport chain: ${chain.joinToString(" → ") { it.transport.label }}")

            var winner: RixOptions? = null
            var egress: TunnelProbe.Egress? = null

            for ((index, candidate) in chain.withIndex()) {
                if (session.get() != mine) return

                if (index > 0) {
                    LogBus.i("vpn", "${candidate.transport.label}: retrying after ${chain[index - 1].transport.label}")
                    // Psiphon outlives the core it dials through unless it is
                    // taken down first, and a Psiphon left pointing at a freed
                    // core reports an upstream error over the *next* attempt.
                    withContext(Dispatchers.IO) { PsiphonBridge.stop() }
                    runCatching { RixRuntime.stopCore() }
                    delay(200)
                }

                RixRuntime.markConnecting(
                    candidate.bindPort,
                    tun2socks = true,
                    message = getString(
                        R.string.state_trying_transport,
                        index + 1,
                        chain.size,
                        candidate.transport.label
                    )
                )
                startForegroundSafely(l10n(R.string.notif_connecting))

                // With Psiphon in the path the core moves one port along and
                // Psiphon takes the port the rest of the app expects, so the
                // two listeners can never collide. Without it, the two are the
                // same port and nothing here changes.
                val upstreamPort = candidate.corePort
                val listening = try {
                    RixRuntime.startCore(this, candidate.forCore())
                    LogBus.i("vpn", "${candidate.transport.label}: core started, waiting for SOCKS5 on $upstreamPort")
                    RixRuntime.awaitSocks(upstreamPort, timeoutMs = CORE_LISTEN_MS)
                } catch (t: Throwable) {
                    LogBus.e("vpn", "${candidate.transport.label}: ${t.message}")
                    false
                }
                if (!listening) continue
                if (session.get() != mine) return
                LogBus.i("vpn", "${candidate.transport.label}: SOCKS5 is accepting connections")

                // Psiphon rides on top of the core: it reaches its own servers
                // through the listener that just came up, and serves the port
                // the engine and the probes talk to. See [PsiphonBridge].
                if (candidate.psiphon) {
                    RixRuntime.markConnecting(
                        candidate.bindPort,
                        tun2socks = true,
                        message = l10n(R.string.state_psiphon)
                    )
                    val up = PsiphonBridge.start(
                        this,
                        upstreamPort,
                        candidate.bindPort,
                        candidate.psiphonRegion,
                        candidate.psiphonProtocol
                    )
                    if (!up) {
                        LogBus.e("vpn", "psiphon: nothing listening on ${candidate.bindPort}")
                        if (session.get() != mine) return
                        continue
                    }
                    if (session.get() != mine) return
                }

                // hev is started once, against whichever core won the first
                // "port is listening" race. A later core on the same port simply
                // takes over: hev keeps reading the TUN and opens fresh SOCKS5
                // connections, which the new core serves.
                if (hevToken == null) {
                    // detachFd hands ownership of the descriptor to hev; closing
                    // the ParcelFileDescriptor afterwards would close the same
                    // fd twice.
                    val raw = fd.detachFd()
                    hevToken = RixRuntime.startTun2Socks(this, candidate, raw) { rc ->
                        LogBus.i("hev", "tun2socks exited rc=$rc")
                        // Only an engine that died while it was still the live
                        // session is a failure; one that exits as part of a
                        // teardown is not.
                        if (session.get() == mine &&
                            RixRuntime.state.value.phase == RixRuntime.Phase.RUNNING
                        ) {
                            scope.launch { failAndStop(l10n(R.string.error_engine_stopped)) }
                        }
                    }

                    // hev can return within milliseconds (see the SYNC_STOP note
                    // in RixRuntime.startTun2Socks). Reporting "connected" before
                    // proving the engine survived would show a green screen over
                    // a dead tunnel.
                    if (!RixRuntime.awaitHevAlive()) {
                        throw IllegalStateException(l10n(R.string.error_engine_stopped))
                    }
                }

                // The hard gate. Neither the listening port nor a live engine
                // says anything about WARP; only a request that came back
                // through the tunnel does.
                // Psiphon needs far longer than WARP does: before any traffic can
                // come back it has to fetch a server list through the tunnel and
                // handshake with one of its servers. A 20 s budget would call a
                // working chain dead while it was still connecting.
                val probeBudget = when {
                    candidate.psiphon -> PSIPHON_PROBE_MS
                    index == 0 -> FIRST_PROBE_MS
                    else -> NEXT_PROBE_MS
                }
                val result = RixRuntime.awaitTunnel(candidate.bindPort, timeoutMs = probeBudget) { attempt ->
                    if (attempt > 1 && session.get() == mine) {
                        RixRuntime.markConnecting(
                            candidate.bindPort,
                            tun2socks = true,
                            message = if (candidate.psiphon) {
                                l10n(R.string.state_psiphon)
                            } else {
                                l10n(R.string.state_verifying_attempt, attempt)
                            }
                        )
                    }
                }

                if (result != null) {
                    winner = candidate
                    egress = result
                    break
                }
                LogBus.e("vpn", "${candidate.transport.label}: no traffic came back through the tunnel")
            }

            // A stop (or a fresh start) during the probe owns the service now.
            if (session.get() != mine) return

            val chosen = winner
            val verified = egress
            if (chosen == null || verified == null) {
                throw IllegalStateException(l10n(R.string.error_all_transports_failed))
            }

            // Remembering the winner is what makes the second connect fast: the
            // next bring-up puts it at the head of the chain.
            RixRuntime.rememberTransport(this, chosen.transport)
            LogBus.i("vpn", "tunnel verified via ${chosen.transport.label}: egress ${verified.ip} via ${verified.colo ?: "?"}")

            RixRuntime.markRunning(
                chosen.bindPort,
                tun2socks = true,
                egress = verified,
                transport = chosen.transport
            )
            startForegroundSafely(l10n(R.string.notif_running, chosen.bindPort))
            startTicker()
            startCoreWatch(chosen)

        } catch (t: Throwable) {
            failAndStop(t.message ?: l10n(R.string.error_unknown))
        }
    }

    /** Builds and establishes the TUN, including DNS and split tunnelling. */
    private fun establishTun(options: RixOptions): ParcelFileDescriptor {
        // A tunnel from a previous session of this same instance must not stay
        // open underneath the new one: two descriptors mean the system keeps
        // the interface (and the key icon) alive after either is closed.
        runCatching { tun?.close() }
        tun = null

        val builder = Builder()
            .setSession(l10n(R.string.app_name))
            .setMtu(options.safeMtu)
            .addAddress(options.tunnelIpv4, 30)
            .addRoute("0.0.0.0", 0)
            // IPv6 is captured too; leaving it unrouted would leak v6 traffic
            // outside the tunnel on dual-stack carriers.
            .addAddress(options.tunnelIpv6, 126)
            .addRoute("::", 0)

        primaryDns(options)?.let { builder.addDnsServer(it) }

        applySplitTunnelling(builder, options)

        val fd = builder.establish()
            ?: throw IllegalStateException(l10n(R.string.error_no_tun))
        tun = fd
        return fd
    }

    private fun primaryDns(options: RixOptions): String? =
        options.dns.split(',', ' ')
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) }

    /**
     * Split tunnelling.
     *
     * addAllowedApplication and addDisallowedApplication are mutually exclusive,
     * and the VPN app itself must never be captured by its own TUN — otherwise
     * the core's WARP sockets are routed back into the tunnel and deadlock.
     * Excluding our own package is what breaks that loop.
     */
    private fun applySplitTunnelling(builder: Builder, options: RixOptions) {
        val self = packageName
        when {
            options.splitMode == com.rix.vpn.core.SplitMode.ONLY && options.splitApps.isNotEmpty() -> {
                // Only the chosen apps are captured; the core stays outside by
                // simply not being on the list.
                options.splitApps.filter { it != self }.forEach {
                    runCatching { builder.addAllowedApplication(it) }
                        .onFailure { e -> LogBus.e("vpn", "allow $it: ${e.message}") }
                }
                LogBus.i("vpn", "split: only ${options.splitApps.size} app(s)")
            }

            options.splitMode == com.rix.vpn.core.SplitMode.EXCEPT && options.splitApps.isNotEmpty() -> {
                (options.splitApps + self).distinct().forEach {
                    runCatching { builder.addDisallowedApplication(it) }
                        .onFailure { e -> LogBus.e("vpn", "disallow $it: ${e.message}") }
                }
                LogBus.i("vpn", "split: all but ${options.splitApps.size} app(s)")
            }

            else -> {
                if (options.excludeSelf) {
                    runCatching { builder.addDisallowedApplication(self) }
                        .onFailure { e -> LogBus.e("vpn", "exclude self: ${e.message}") }
                }
                LogBus.i("vpn", "split: every app")
            }
        }
    }

    // ---------------------------------------------------------------- ticking

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                RixRuntime.pushStats()
                // Every 90 s the exit is re-read: a reconnect after a network
                // change can leave the session on a different address, and the
                // tile must not keep showing where it used to come out. Own
                // coroutine, because the lookup can take seconds and the
                // counters must keep ticking through it.
                if (++ticks % 90 == 0) {
                    val port = RixRuntime.state.value.socksPort
                    launch { RixRuntime.refreshEgress(port) }
                }
                delay(1000)
            }
        }
    }

    private fun startCoreWatch(options: RixOptions) {
        watch?.cancel()
        val mine = session.get()
        watch = scope.launch {
            while (isActive) {
                delay(2000)
                // poll() is a plain blocking call, so a stop that lands while it
                // is in flight cancels this coroutine but cannot interrupt it:
                // the loop still comes back here for one more pass. Without this
                // guard that pass reports "core closed" over a session the user
                // already ended, and its shutdown() is what poisons the engine.
                if (!isActive || session.get() != mine) return@launch
                val report = runCatching { RixRuntime.poll() }.getOrNull() ?: continue
                if (!isActive || session.get() != mine) return@launch
                if (report.failed) {
                    failAndStop(report.detail ?: l10n(R.string.error_core_stopped))
                    return@launch
                }
                if (!report.running) {
                    failAndStop(l10n(R.string.error_core_stopped))
                    return@launch
                }
            }
        }
    }

    private suspend fun failAndStop(message: String) {
        // No error notification: shutdown() removes the notification at once so
        // the key icon and the ongoing tile cannot outlive the session, and the
        // reason belongs on the screen, which the preserved error fills.
        shutdown(preserveError = message)
    }

    // --------------------------------------------------------------- shutdown

    /**
     * Tears the session down.
     *
     * [preserveError] keeps a failure on screen. A plain stop returns the UI to
     * idle, but a failed start used to do the same thing through markIdle() a
     * few hundred milliseconds after markError(), so the reason flashed once
     * and then the screen said «آماده به اتصال» with no explanation.
     */
    fun shutdown(preserveError: String? = null) {
        val mine = session.get()
        if (preserveError != null) {
            RixRuntime.markError(preserveError)
        } else {
            RixRuntime.markStopping()
        }
        ticker?.cancel()
        ticker = null
        // Without this a failed attempt leaves its poll loop running; it then
        // reports "core closed" over the *next* connection's error state.
        watch?.cancel()
        watch = null

        // tun2socks first: it is the only reader of the TUN fd, so stopping the
        // core before it would leave the engine spinning on dead sockets.
        val token = hevToken
        hevToken = null

        // The interface goes down here and now, not in the coroutine below.
        //
        // hev is handed a detached descriptor and never closes one, and the
        // ParcelFileDescriptor this service kept was detached with it, so
        // nothing used to release it -- which is exactly the key icon that
        // stayed in the status bar after a disconnect. Closing it is also
        // synchronous on purpose: the teardown below waits seconds for Psiphon,
        // and a user who pressed stop should not be looking at a VPN icon while
        // that runs, or forever if it blocks. Closing it also unblocks an
        // engine that is stuck in the library, since its poll then fails.
        runCatching { tun?.close() }
        tun = null
        RixRuntime.closeTunFd()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { LogBus.e("vpn", "stopForeground: ${it.message}") }

        scope.launch {
            // Before the core, always: Psiphon is the core's own client while it
            // is in the path, and it blocks for a few seconds on the way down.
            RixRuntime.stopTun2Socks(token)
            PsiphonBridge.stop()
            RixRuntime.stopCore()
            withContext(Dispatchers.Main) {
                // A start that arrived while this teardown was in flight owns
                // the service now. Reporting idle or stopping the service here
                // would kill a live connection.
                if (session.get() != mine) return@withContext
                // Only a clean stop resets the phase; a failure keeps the error
                // that shutdown() already published.
                if (preserveError == null) RixRuntime.markIdle()
                stopSelf()
            }
        }
        worker?.cancel()
        worker = null
    }

    override fun onRevoke() {
        LogBus.i("vpn", "revoked by the system or another VPN")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        LogBus.i("vpn", "service destroyed")
        // A newer instance may already own the runtime -- a stop followed
        // straight by a start -- and everything below the per-instance TUN is
        // process-wide. A dying instance that touched it would take the live
        // session's descriptor, engine and notification down with it.
        val superseded = instance !== this
        if (!superseded) instance = null

        runCatching { tun?.close() }
        tun = null

        if (!superseded) {
            // The forced-stop path: no shutdown() ran, so the descriptor -- and
            // the key icon with it -- is released here. Before the engine is
            // signalled, because a wedged engine is exactly what this path
            // exists for.
            RixRuntime.closeTunFd()
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            runCatching { RixRuntime.stopTun2Socks(hevToken) }
            hevToken = null
            // A forced stop never runs shutdown(), so the whole chain is
            // disposed here too -- but only while this service still owns the
            // live session, because the proxy service drives the same
            // process-wide core.
            if (RixRuntime.state.value.mode == RixMode.TUNNEL) {
                RixRuntime.stopChainAsync()
                // Nothing is left to publish a final phase for a service that
                // was destroyed outright, and leaving the screen on «متصل» over
                // a tunnel that no longer exists is what made the orb look
                // stuck. An error already on screen is the one thing kept: it is
                // the reason the service is going away.
                if (RixRuntime.state.value.phase != RixRuntime.Phase.ERROR) {
                    RixRuntime.markIdle()
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun startForegroundSafely(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        }.onFailure { LogBus.e("vpn", "startForeground: ${it.message}") }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RixVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RixApp.CHANNEL_STATUS)
            .setContentTitle(l10n(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_rix)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, l10n(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001

        /**
         * The live instance, if there is one.
         *
         * The UI uses this to notice a tunnel that outlived its session: the
         * shared state can only describe what the services told it, and the
         * failure that mattered was a TUN still holding the key icon while the
         * state said IDLE.
         */
        @Volatile
        private var instance: RixVpnService? = null

        /** True while an instance exists with an established TUN. */
        fun isUp(): Boolean = instance?.tun != null

        /** How long the core gets to open its SOCKS5 port, per transport. */
        private const val CORE_LISTEN_MS = 15_000L

        /**
         * Probe budgets per transport. The first candidate is the user's own
         * choice and gets a generous window; the fallbacks are there to get a
         * connection at all, so they are tried quickly.
         */
        private const val FIRST_PROBE_MS = 20_000L
        private const val NEXT_PROBE_MS = 10_000L

        /**
         * The budget when Psiphon is the last hop.
         *
         * It covers a server-list download and a tunnel handshake on top of
         * WARP, so it is measured in a minute rather than in seconds.
         */
        private const val PSIPHON_PROBE_MS = 60_000L
        const val ACTION_START = "com.rix.vpn.START_TUNNEL"
        const val ACTION_STOP = "com.rix.vpn.STOP_TUNNEL"
        const val EXTRA_OPTIONS = "options"

        fun start(context: Context, options: RixOptions) {
            val intent = Intent(context, RixVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OPTIONS, options.toJson())
            // startForegroundService: on API 26+ a plain startService can be
            // refused once the activity is no longer visible.
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Asks the running service to tear itself down.
         *
         * `startService` is refused with an [IllegalStateException] when the app
         * has no visible activity (the background-start restriction), and that
         * refusal used to be the end of it: the tunnel stayed up, and with it
         * the key icon and the notification. Destroying the service instead
         * skips the graceful teardown but does release the TUN, and the
         * disposal in `onDestroy` takes the rest with it.
         */
        fun stop(context: Context) {
            val intent = Intent(context, RixVpnService::class.java).setAction(ACTION_STOP)
            val asked = runCatching { context.startService(intent) }
                .onFailure { LogBus.e("vpn", "stop refused (${it.message}); stopping directly") }
                .isSuccess
            if (!asked) forceStop(context)
        }

        /** Destroys the service without a graceful teardown. */
        fun forceStop(context: Context) {
            runCatching { context.stopService(Intent(context, RixVpnService::class.java)) }
        }
    }
}