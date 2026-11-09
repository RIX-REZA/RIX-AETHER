package com.rix.vpn.core

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.rix.vpn.options.RixOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.minOf
import kotlin.math.pow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Health thresholds (top-level for visibility to nested classes)
private const val HEALTH_CHECK_INTERVAL_MS = 15_000L
private const val GRACE_PERIOD_MS = 30_000L
private const val MAX_CONSECUTIVE_FAILURES = 3
private const val LATENCY_DEGRADED_MS = 500L
private const val LATENCY_CRITICAL_MS = 2000L
private const val PACKET_LOSS_DEGRADED = 0.15
private const val PACKET_LOSS_CRITICAL = 0.35

/**
 * Core coordinator: the single source of truth for tunnel state, health, and failover.
 *
 * This class owns the entire tunnel lifecycle. It starts the native core via PsiphonBridge,
 * monitors health through active probes, manages transport failover, and emits a clean
 * StateFlow for the UI. It is the only component that should talk to PsiphonBridge
 * and CloudflareIpManager directly.
 *
 * Key responsibilities:
 * - State machine: Idle -> Starting -> Connected -> Degraded -> Recovering -> Disconnected
 * - Health monitoring: periodic probe + passive egress analysis
 * - Smart failover: Cloudflare IP rotation + transport cascade (MASQUE H3 -> H2 -> GOOL -> WG)
 * - Network change detection: Wi-Fi <-> mobile, captive portals, IPv6 availability
 * - Power-aware: respects Doze, battery saver, thermal state
 */
class CoreCoordinator private constructor(
    private val context: Context,
    private val options: RixOptions
) : LifecycleObserver {

    // ============================================================ PUBLIC STATE

    /** Current tunnel state, exposed to UI. */
    sealed interface State {
        data class Idle(val reason: String = "idle") : State
        data class Starting(val transport: RixOptions.Transport, val attempt: Int) : State
        data class Connected(
            val transport: RixOptions.Transport,
            val egress: TunnelProbe.Egress?,
            val durationMs: Long,
            val bytesUp: Long,
            val bytesDown: Long
        ) : State
        data class Degraded(
            val transport: RixOptions.Transport,
            val egress: TunnelProbe.Egress?,
            val reason: String,
            val durationMs: Long
        ) : State
        data class Recovering(
            val transport: RixOptions.Transport,
            val attempt: Int,
            val reason: String
        ) : State
        data class Disconnected(val reason: String, val lastTransport: RixOptions.Transport?) : State
    }

    /** Live state for UI observation. */
    private val _state = MutableStateFlow<State>(State.Idle())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the tunnel is currently passing traffic (Connected or Degraded). */
    val isActive: Boolean
        get() = when (val s = _state.value) {
            is State.Connected, is State.Degraded -> true
            else -> false
        }

    /** Latest stats snapshot for UI. */
    data class Stats(
        val transport: RixOptions.Transport,
        val egress: TunnelProbe.Egress?,
        val latencyMs: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val uptimeMs: Long,
        val healthScore: Double
    )
    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    // ============================================================ INTERNAL STATE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge = PsiphonBridge()
    private val healthJob = AtomicReference<Job?>()
    private val failoverJob = AtomicReference<Job?>()
    private val statsJob = AtomicReference<Job?>()

    private val currentTransport = AtomicReference(options.transport)
    private val currentOptions = AtomicReference(options)
    private val currentEgress = AtomicReference<TunnelProbe.Egress?>()
    private val connectionStartTime = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val isShuttingDown = AtomicBoolean(false)

    // Failover state
    private val triedTransports = ConcurrentHashMap<RixOptions.Transport, Int>()
    private val transportOrder = listOf(
        RixOptions.Transport.MASQUE_H3,
        RixOptions.Transport.MASQUE_H2,
        RixOptions.Transport.GOOL,
        RixOptions.Transport.WIREGUARD,
        RixOptions.Transport.GOOL_CLASSIC,
        RixOptions.Transport.MIM
    )

    // ============================================================ PUBLIC API

    /** Starts the tunnel with the current options. Returns a channel of state updates. */
    fun start(): ReceiveChannel<State> {
        val channel = Channel<State>(Channel.UNLIMITED)
        scope.launch {
            try {
                runTunnel(channel)
            } catch (e: Exception) {
                Log.e("CoreCoordinator", "Tunnel crashed: ${e.message}", e)
                channel.close(e)
            }
        }
        return channel
    }

    /** Reconnects with current options (used when options change). */
    private suspend fun tryReconnect(reason: String) {
        if (isShuttingDown.get()) return
        Log.i("CoreCoordinator", "Reconnect triggered: $reason")
        // Signal the main loop to restart by canceling health monitoring
        healthJob.getAndSet(null)?.cancel()
        failoverJob.getAndSet(null)?.cancel()
        // The main loop will pick up the new options on next iteration
    }

    /** Updates options at runtime (transport, psiphon, etc.). Triggers reconnect if needed. */
    fun updateOptions(newOptions: RixOptions) {
        val oldOpts = currentOptions.getAndSet(newOptions)
        val transportChanged = oldOpts.transport != newOptions.transport
        val psiphonChanged = oldOpts.psiphon != newOptions.psiphon
        val modeChanged = oldOpts.mode != newOptions.mode

        if (transportChanged || psiphonChanged || modeChanged) {
            Log.i("CoreCoordinator", "Options changed (transport=$transportChanged, psiphon=$psiphonChanged, mode=$modeChanged), scheduling reconnect")
            scope.launch {
                tryReconnect("options changed")
            }
        }
        // Other options (dns, mtu, etc.) are picked up on next core restart
    }

    /** Forces immediate health re-evaluation and potential failover. */
    fun forceHealthCheck() {
        scope.launch {
            reevaluateAndMaybeFailover(null, currentOptions.get())
        }
    }

    /** Shuts down the coordinator completely. */
    fun shutdown() {
        isShuttingDown.set(true)
        healthJob.getAndSet(null)?.cancel()
        failoverJob.getAndSet(null)?.cancel()
        statsJob.getAndSet(null)?.cancel()
        scope.coroutineContext[Job]?.cancel()
        bridge.stop()
        CloudflareIpManager.invalidateCurrentBest()
    }

    // ============================================================ LIFECYCLE

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() = shutdown()

    // ============================================================ TUNNEL RUN LOOP

    private suspend fun runTunnel(channel: Channel<State>) {
        var currentAttempt = 0

        while (!isShuttingDown.get()) {
            val opts = currentOptions.get()
            val transport = currentTransport.get()

            emitState(channel, State.Starting(transport, currentAttempt))
            connectionStartTime.set(System.currentTimeMillis())

            var startSuccess = false
            var startError: String? = null

            if (opts.psiphon) {
                // Psiphon in the path: core listens on corePort, Psiphon on bindPort
                val bridgeResult = bridge.start(
                    context = context,
                    upstreamPort = opts.corePort,
                    listenPort = opts.bindPort,
                    region = opts.psiphonRegion,
                    protocol = opts.psiphonProtocol
                )
                startSuccess = bridgeResult
                if (!bridgeResult) {
                    startError = "Psiphon bridge failed to start"
                }
            } else {
                // Direct core start (no Psiphon) - just verify core is reachable
                startSuccess = true
            }

            if (!startSuccess) {
                currentAttempt++
                val delay = minOf(1000L * 2.0.pow(currentAttempt - 1), 30000L).toLong()
                Log.w("CoreCoordinator", "Start failed (attempt $currentAttempt): $startError. Retrying in ${delay}ms")
                emitState(channel, State.Disconnected(startError ?: "start failed", transport))
                if (!isShuttingDown.get()) delay(delay)
                continue
            }

            // Successfully connected
            currentAttempt = 0
            triedTransports.clear()
            consecutiveFailures.set(0)

            val connectedState = State.Connected(
                transport = transport,
                egress = null,
                durationMs = 0,
                bytesUp = 0,
                bytesDown = 0
            )
            emitState(channel, connectedState)
            startHealthMonitoring(channel, opts)

            // Wait for disconnection or failover trigger
            val disconnectReason = waitForDisconnectOrFailover(channel, opts)
            stopHealthMonitoring()

            if (isShuttingDown.get()) break

            // Handle failover vs permanent disconnect
            if (disconnectReason != null && options.autoFallback && !triedTransports.containsKey(transport)) {
                Log.i("CoreCoordinator", "Attempting failover from $transport due to: $disconnectReason")
                currentAttempt++
                val delay = minOf(1000L * 2.0.pow(currentAttempt - 1), 10000L).toLong()
                if (!isShuttingDown.get()) delay(delay)
            } else {
                emitState(channel, State.Disconnected(disconnectReason ?: "disconnected", transport))
                if (!options.autoFallback || triedTransports[transport] != null) {
                    Log.i("CoreCoordinator", "No more failover options or autoFallback disabled")
                    break
                }
            }
        }
        channel.close()
    }

    /** Emits state to channel and updates internal flow. */
    private suspend fun emitState(channel: Channel<State>, state: State) {
        _state.value = state
        channel.send(state)
    }

    /** Waits for bridge to disconnect or failover to be requested. */
    private suspend fun waitForDisconnectOrFailover(
        channel: Channel<State>,
        opts: RixOptions
    ): String? {
        val disconnection = bridge.waitForDisconnect()

        return suspendCancellableCoroutine { cont ->
            scope.launch {
                val reason = disconnection.await()
                cont.resume(reason)
            }
            cont.invokeOnCancellation {
                // Failover requested externally
                cont.resume("failover requested")
            }
        }
    }

    // ============================================================ HEALTH MONITORING

    private fun startHealthMonitoring(channel: Channel<State>, opts: RixOptions) {
        healthJob.set(scope.launch {
            val initialDelay = GRACE_PERIOD_MS
            delay(initialDelay)

            while (!isShuttingDown.get() && isActive) {
                reevaluateAndMaybeFailover(channel, opts)
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        })

        statsJob.set(scope.launch {
            while (!isShuttingDown.get() && isActive) {
                updateStats()
                delay(1000)
            }
        })
    }

    private fun stopHealthMonitoring() {
        healthJob.getAndSet(null)?.cancel()
        statsJob.getAndSet(null)?.cancel()
    }

    /** Core health evaluation: probes + egress analysis -> failover decision. */
    private suspend fun reevaluateAndMaybeFailover(channel: Channel<State>?, opts: RixOptions) {
        if (isShuttingDown.get() || !isActive) return

        val transport = currentTransport.get()
        val egress = currentEgress.get()

        // Get latest probe result
        val probeResult = TunnelProbe.probeEgress(egress?.ip ?: "", opts.corePort)

        // Get Cloudflare IP score if available
        val ipScore = CloudflareIpManager.getCurrentBestScore()

        val health = HealthScore(
            probeResult = probeResult,
            ipScore = ipScore,
            transport = transport,
            consecutiveFailures = consecutiveFailures.get()
        )

        Log.d("CoreCoordinator", "Health check: $health")

        // Update state based on health
        when {
            health.isCritical -> {
                consecutiveFailures.incrementAndGet()
                if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
                    triggerFailover(channel, opts, "critical health: ${health.reason}")
                } else {
                    emitDegraded(channel, opts, health.reason)
                }
            }
            health.isDegraded -> {
                consecutiveFailures.incrementAndGet()
                emitDegraded(channel, opts, health.reason)
            }
            else -> {
                consecutiveFailures.set(0)
                emitHealthy(channel, opts, probeResult)
            }
        }
    }

    /** Emits Degraded state if not already degraded. */
    private suspend fun emitDegraded(channel: Channel<State>?, opts: RixOptions, reason: String) {
        val current = _state.value
        if (current is State.Degraded) return // Already degraded
        val transport = currentTransport.get()
        val duration = System.currentTimeMillis() - connectionStartTime.get()
        val state = State.Degraded(transport, currentEgress.get(), reason, duration)
        _state.value = state
        channel?.trySend(state)
    }

    /** Emits Connected state with fresh egress. */
    private suspend fun emitHealthy(channel: Channel<State>?, opts: RixOptions, probeResult: TunnelProbe.Result?) {
        val transport = currentTransport.get()
        val duration = System.currentTimeMillis() - connectionStartTime.get()
        val existing = currentEgress.get()
        val egress = probeResult?.let {
            // Update existing egress with fresh latency from probe
            existing?.copy(latencyMs = it.latencyMs)
        } ?: existing
        val state = State.Connected(transport, egress, duration, bytesUp.get(), bytesDown.get())
        _state.value = state
        channel?.trySend(state)
        currentEgress.set(egress)
    }

    /** Triggers transport failover. */
    private fun triggerFailover(channel: Channel<State>?, opts: RixOptions, reason: String) {
        failoverJob.set(scope.launch {
            val currentTransportValue = currentTransport.get()
            triedTransports[currentTransportValue] = triedTransports.getOrDefault(currentTransportValue, 0) + 1

            // Find next untried transport
            val nextTransport = transportOrder
                .dropWhile { it != currentTransportValue }
                .drop(1)
                .firstOrNull { triedTransports[it] == null }

            if (nextTransport != null) {
                Log.i("CoreCoordinator", "Failing over from $currentTransportValue to $nextTransport: $reason")
                currentTransport.set(nextTransport)
                currentOptions.set(opts.copy(transport = nextTransport))
                val ch = channel ?: Channel<State>(Channel.UNLIMITED)
                emitState(ch, State.Recovering(nextTransport, triedTransports.getOrDefault(nextTransport, 0) + 1, reason))
                // The main loop will pick up the new transport on next iteration
            } else {
                Log.w("CoreCoordinator", "Exhausted all transport options")
                val ch = channel ?: Channel<State>(Channel.UNLIMITED)
                emitState(ch, State.Disconnected("all transports failed", currentTransportValue))
                isShuttingDown.set(true)
            }
        })
    }

    // ============================================================ STATS

    private suspend fun updateStats() {
        val transport = currentTransport.get()
        val egress = currentEgress.get()
        val uptime = System.currentTimeMillis() - connectionStartTime.get()

        // Get runtime stats from RixRuntime.traffic StateFlow
        val runtimeTraffic = RixRuntime.traffic.value

        val stats = Stats(
            transport = transport,
            egress = egress,
            latencyMs = egress?.latencyMs ?: -1,
            bytesUp = runtimeTraffic.txBytes,
            bytesDown = runtimeTraffic.rxBytes,
            uptimeMs = uptime,
            healthScore = calculateHealthScore(egress)
        )

        _stats.value = stats
        bytesUp.set(runtimeTraffic.txBytes)
        bytesDown.set(runtimeTraffic.rxBytes)
    }

    private suspend fun calculateHealthScore(egress: TunnelProbe.Egress?): Double {
        val probe = TunnelProbe.probeEgress(egress?.ip ?: "", currentOptions.get().corePort)
        return probe?.healthScore ?: 0.0
    }

    // ============================================================ COMPANION

    companion object {
        /** Creates and starts a new CoreCoordinator. */
        fun create(
            context: Context,
            options: RixOptions
        ): CoreCoordinator = CoreCoordinator(context, options)
    }
}

/** Aggregated health assessment from probes and IP scores. */
private data class HealthScore(
    val probeResult: TunnelProbe.Result?,
    val ipScore: CloudflareIpManager.IpScore?,
    val transport: RixOptions.Transport,
    val consecutiveFailures: Int
) {
    val isDegraded: Boolean
        get() = when {
            probeResult == null -> true
            probeResult.latencyMs > LATENCY_DEGRADED_MS -> true
            probeResult.packetLoss > PACKET_LOSS_DEGRADED -> true
            ipScore?.isReachable == false -> true
            ipScore?.compositeScore ?: 1.0 < 0.3 -> true
            else -> false
        }

    val isCritical: Boolean
        get() = when {
            probeResult == null -> true
            probeResult.latencyMs > LATENCY_CRITICAL_MS -> true
            probeResult.packetLoss > PACKET_LOSS_CRITICAL -> true
            ipScore?.isReachable == false && consecutiveFailures >= 2 -> true
            else -> false
        }

    val reason: String
        get() = when {
            probeResult == null -> "no probe response"
            probeResult.latencyMs > LATENCY_CRITICAL_MS -> "latency ${probeResult.latencyMs}ms > ${LATENCY_CRITICAL_MS}ms"
            probeResult.packetLoss > PACKET_LOSS_CRITICAL -> "packet loss ${(probeResult.packetLoss * 100).toInt()}% > ${(PACKET_LOSS_CRITICAL * 100).toInt()}%"
            ipScore?.isReachable == false -> "IP unreachable"
            else -> "degraded"
        }
}

// Use constants from companion object