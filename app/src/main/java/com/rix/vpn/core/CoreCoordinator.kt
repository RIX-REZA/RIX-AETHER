package com.rix.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Smart coordination layer between WARP (Aether) and Psiphon cores.
 *
 * This is the operational heart of the dual-core architecture. It manages:
 * - Coordinated lifecycle of both cores
 * - Real-time health monitoring with protocol-level probes
 * - Smart failover when one core degrades
 * - Network change detection and seamless handover (Wi-Fi ↔ Mobile)
 * - Connection state machine with proper transitions
 * - Retry/timeout management with exponential backoff
 * - Preventing duplicate connections and resource leaks
 *
 * The layer sits ABOVE the individual cores (WARP via Aether, Psiphon via PsiphonBridge)
 * and orchestrates them. It does NOT replace or rewrite the cores -- it coordinates them.
 *
 * Usage: Services (RixVpnService, RixProxyService) create a CoreCoordinator and call
 * `start()` to get a state channel. The coordinator handles connection lifecycle,
 * health monitoring, and failover. Services observe the state channel for UI updates.
 */
class CoreCoordinator(
    private val context: Context,
    private val initialOptions: RixOptions,
    private val runtime: RixRuntime
) {

    // ──────────────────────────────────────────────────────────────
    // State Machine
    // ──────────────────────────────────────────────────────────────

    sealed interface State {
        data object Idle : State
        data class Starting(val transport: RixOptions.Transport) : State
        data class Connecting(val transport: RixOptions.Transport, val attempt: Int) : State
        data class PsiphonHandshake(val transport: RixOptions.Transport) : State
        data class Running(val transport: RixOptions.Transport, val egress: TunnelProbe.Egress) : State
        data class Degraded(val transport: RixOptions.Transport, val reason: String) : State
        data class Recovering(val transport: RixOptions.Transport, val attempt: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow().distinctUntilChanged()

    private val _currentOptions = AtomicReference<RixOptions>(initialOptions)
    private val _currentEgress = AtomicReference<TunnelProbe.Egress?>(null)
    private val _psiphonActive = AtomicBoolean(false)

    // ──────────────────────────────────────────────────────────────
    // Health Monitoring
    // ──────────────────────────────────────────────────────────────

    private val _healthScores = ConcurrentHashMap<RixOptions.Transport, HealthScore>()
    private val _lastProbeTime = AtomicLong(0)
    private val _consecutiveFailures = AtomicInteger(0)

    private data class HealthScore(
        val latencyMs: Long,
        val successRate: Double, // 0.0 - 1.0 over last N probes
        val packetLoss: Double,  // 0.0 - 1.0
        val stabilityScore: Double, // 0.0 - 1.0
        val lastProbeAt: Long,
        val consecutiveFailures: Int,
        val isHealthy: Boolean
    ) {
        val compositeScore: Double
            get() = if (!isHealthy) 0.0 else {
                val latencyNorm = (1.0 - (latencyMs / 500.0).coerceIn(0.0, 1.0)) * 0.4
                val successNorm = successRate * 0.3
                val stabilityNorm = stabilityScore * 0.2
                val lossNorm = (1.0 - packetLoss) * 0.1
                latencyNorm + successNorm + stabilityNorm + lossNorm
            }
    }

    // ──────────────────────────────────────────────────────────────
    // Network Change Detection
    // ──────────────────────────────────────────────────────────────

    private val _networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
            val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            handleNetworkChange(type, metered)
        }

        override fun onLost(network: Network) {
            handleNetworkChange(NetworkType.UNKNOWN, true)
        }
    }

    private val _lastNetworkType = AtomicReference<NetworkType>(NetworkType.UNKNOWN)
    private val _lastMetered = AtomicBoolean(false)
    private val _networkChangeTime = AtomicLong(0)

    private enum class NetworkType { WIFI, CELLULAR, ETHERNET, UNKNOWN }

    // ──────────────────────────────────────────────────────────────
    // Retry / Timeout Management
    // ──────────────────────────────────────────────────────────────

    private val _retryCount = AtomicInteger(0)
    private val _maxRetries = 3
    private val _baseRetryDelayMs = 2000L
    private val _maxRetryDelayMs = 30000L
    private val _healthProbeIntervalMs = 15000L
    private val _degradedThreshold = 0.4 // composite score below this = degraded

    // ──────────────────────────────────────────────────────────────
    // Coroutine Scope
    // ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _healthJob = AtomicReference<Job?>(null)
    private val _networkManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var _pendingPsiphonConfig: PsiphonConfig? = null

    private data class PsiphonConfig(
        val region: String?,
        val protocol: String?
    )

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Starts the coordinator and brings up the initial connection.
     * Returns a channel that receives state transitions.
     */
    fun start(): ReceiveChannel<State> {
        val channel = Channel<State>(Channel.UNLIMITED)

        // Register network callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        _networkManager.registerNetworkCallback(request, _networkCallback)

        // Initial network type
        val activeNetwork = _networkManager.activeNetwork
        if (activeNetwork != null) {
            val caps = _networkManager.getNetworkCapabilities(activeNetwork)
            if (caps != null) {
                _lastNetworkType.set(when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                    else -> NetworkType.UNKNOWN
                })
                _lastMetered.set(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
            }
        }

        // Start connection
        scope.launch {
            try {
                bringUp()
            } catch (e: Exception) {
                Log.e("CoreCoordinator", "Initial bringUp failed: ${e.message}")
                transition(State.Error(e.message ?: "Unknown error"))
                channel.send(State.Error(e.message ?: "Unknown error"))
            }
        }

        // Forward state changes to channel
        scope.launch {
            state.collect { newState ->
                channel.send(newState)
            }
        }

        return channel
    }

    /**
     * Gracefully shuts down the coordinator.
     */
    fun shutdown() {
        scope.cancel()
        _healthJob.getAndSet(null)?.cancel()
        try {
            _networkManager.unregisterNetworkCallback(_networkCallback)
        } catch (e: Exception) {
            Log.w("CoreCoordinator", "Failed to unregister network callback: ${e.message}")
        }
        // Don't stop runtime here - let the service handle it
    }

    /**
     * Forces re-evaluation of the current connection (e.g., after network change).
     */
    fun forceReevaluate() {
        val currentOptions = _currentOptions.get()
        scope.launch {
            reevaluateAndMaybeFailover(currentOptions)
        }
    }

    /**
     * Gets the current health score for a transport.
     */
    fun getHealthScore(transport: RixOptions.Transport): HealthScore? = _healthScores[transport]

    /**
     * Current connection options (for services to start core).
     */
    fun currentOptions(): RixOptions = _currentOptions.get()

    /**
     * Whether Psiphon is currently active.
     */
    fun isPsiphonActive(): Boolean = _psiphonActive.get()

    // ──────────────────────────────────────────────────────────────
    // Connection Lifecycle
    // ──────────────────────────────────────────────────────────────

    private suspend fun bringUp() {
        val chain = runtime.transportChain(initialOptions, runtime.rememberedTransport(context))
        if (chain.isEmpty()) {
            transition(State.Error("No transports available"))
            return
        }

        _retryCount.set(0)
        awaitConnectChain(chain, 0)
    }

    private suspend fun awaitConnectChain(chain: List<RixOptions>, index: Int) {
        if (index >= chain.size) {
            transition(State.Error("All transports exhausted"))
            return
        }

        val candidate = chain[index]
        _currentOptions.set(candidate)
        _psiphonActive.set(candidate.psiphon)

        transition(State.Connecting(candidate.transport, index + 1))

        // Store Psiphon config if needed
        if (candidate.psiphon) {
            _pendingPsiphonConfig = PsiphonConfig(candidate.psiphonRegion, candidate.psiphonProtocol)
        }

        // Start core with candidate options
        val coreStarted = try {
            runtime.startCore(context, candidate)
            runtime.awaitSocks(candidate.safeBindPort, timeoutMs = 15000L)
        } catch (e: Exception) {
            Log.e("CoreCoordinator", "${candidate.transport.label}: core start failed: ${e.message}")
            false
        }

        if (!coreStarted) {
            // Try next transport
            awaitConnectChain(chain, index + 1)
            return
        }

        // Handle Psiphon if needed
        if (candidate.psiphon) {
            transition(State.PsiphonHandshake(candidate.transport))
            val config = _pendingPsiphonConfig
                ?: PsiphonConfig(candidate.psiphonRegion, candidate.psiphonProtocol)

            val psiphonUp = PsiphonBridge.start(
                context,
                candidate.safeBindPort,
                candidate.safeBindPort, // Psiphon uses same port for now
                config.region,
                config.protocol
            )
            if (!psiphonUp) {
                PsiphonBridge.stop()
                runtime.stopCore()
                delay(500)
                _pendingPsiphonConfig = null
                awaitConnectChain(chain, index + 1)
                return
            }
            _pendingPsiphonConfig = null
        }

        // Probe the tunnel
        val probeTimeout = when {
            candidate.psiphon -> 60000L
            index == 0 -> 20000L
            else -> 10000L
        }

        val egress = runtime.awaitTunnel(candidate.safeBindPort, timeoutMs = probeTimeout)
        if (egress == null) {
            Log.e("CoreCoordinator", "${candidate.transport.label}: tunnel probe failed")
            if (candidate.psiphon) PsiphonBridge.stop()
            runtime.stopCore()
            delay(500)
            awaitConnectChain(chain, index + 1)
            return
        }

        // Success!
        _currentEgress.set(egress)
        _retryCount.set(0)
        _consecutiveFailures.set(0)
        runtime.rememberTransport(context, candidate.transport)

        transition(State.Running(candidate.transport, egress))
        startHealthMonitoring(candidate)
    }

    private fun startHealthMonitoring(candidate: RixOptions) {
        _healthJob.getAndSet(null)?.cancel()
        _healthJob.set(scope.launch {
            var ticks = 0
            while (scope.coroutineContext.isActive) {
                delay(_healthProbeIntervalMs)
                if (!scope.coroutineContext.isActive) break

                val currentOpts = _currentOptions.get()
                val transport = currentOpts.transport
                val bindPort = currentOpts.safeBindPort

                val egress = runtime.awaitTunnel(bindPort, timeoutMs = 10000L)
                if (egress != null) {
                    _currentEgress.set(egress)
                    updateHealthScore(transport, egress, success = true)
                    _consecutiveFailures.set(0)

                    // Check if we're degraded
                    val score = _healthScores[transport]
                    if (score != null && score.compositeScore < _degradedThreshold) {
                        transition(State.Degraded(transport, "Health score dropped to ${score.compositeScore}"))
                        scope.launch { reevaluateAndMaybeFailover(currentOpts) }
                    }
                } else {
                    _consecutiveFailures.incrementAndGet()
                    updateHealthScore(transport, null, success = false)

                    if (_consecutiveFailures.get() >= 3) {
                        transition(State.Degraded(transport, "Consecutive probe failures"))
                        scope.launch { reevaluateAndMaybeFailover(currentOpts) }
                    }
                }

                // Periodic egress refresh (every 90s)
                if (++ticks % 6 == 0) {
                    scope.launch { runtime.refreshEgress(bindPort) }
                }
            }
        })
    }

    private suspend fun reevaluateAndMaybeFailover(currentOpts: RixOptions) {
        val chain = runtime.transportChain(currentOpts, currentOpts.transport)
        val currentIndex = chain.indexOfFirst { it.transport == currentOpts.transport }

        // Try next transports in chain
        for (i in currentIndex + 1 until chain.size) {
            val candidate = chain[i]
            transition(State.Recovering(candidate.transport, i - currentIndex))

            // Stop current
            if (_psiphonActive.get()) PsiphonBridge.stop()
            runtime.stopCore()
            delay(500)

            // Try candidate
            val coreStarted = try {
                runtime.startCore(context, candidate)
                runtime.awaitSocks(candidate.safeBindPort, timeoutMs = 15000L)
            } catch (e: Exception) {
                Log.e("CoreCoordinator", "${candidate.transport.label}: core start failed: ${e.message}")
                false
            }

            if (!coreStarted) continue

            if (candidate.psiphon) {
                val psiphonUp = PsiphonBridge.start(
                    context,
                    candidate.safeBindPort,
                    candidate.safeBindPort,
                    candidate.psiphonRegion,
                    candidate.psiphonProtocol
                )
                if (!psiphonUp) {
                    PsiphonBridge.stop()
                    runtime.stopCore()
                    delay(500)
                    continue
                }
            }

            val probeTimeout = when {
                candidate.psiphon -> 60000L
                else -> 15000L
            }
            val egress = runtime.awaitTunnel(candidate.safeBindPort, timeoutMs = probeTimeout)
            if (egress != null) {
                _currentOptions.set(candidate)
                _currentEgress.set(egress)
                _psiphonActive.set(candidate.psiphon)
                _consecutiveFailures.set(0)
                runtime.rememberTransport(context, candidate.transport)
                transition(State.Running(candidate.transport, egress))
                startHealthMonitoring(candidate)
                Log.i("CoreCoordinator", "Failover successful: ${candidate.transport.label}")
                return
            }

            if (candidate.psiphon) PsiphonBridge.stop()
            runtime.stopCore()
            delay(500)
        }

        // All failover attempts exhausted
        transition(State.Error("Failover exhausted all transports"))
    }

    // ──────────────────────────────────────────────────────────────
    // Network Change Handling
    // ──────────────────────────────────────────────────────────────

    private fun handleNetworkChange(newType: NetworkType, metered: Boolean) {
        val oldType = _lastNetworkType.getAndSet(newType)
        val wasMetered = _lastMetered.getAndSet(metered)
        val now = System.currentTimeMillis()
        _networkChangeTime.set(now)

        if (oldType != newType) {
            Log.i("CoreCoordinator", "Network changed: $oldType -> $newType (metered: $metered)")

            // If we're running, trigger a gentle re-evaluation
            val state = _state.value
            if (state is State.Running || state is State.Degraded) {
                // Don't immediately tear down - give the current connection a chance
                // but schedule a health check sooner
                _healthJob.get()?.cancel()
                scope.launch {
                    delay(2000) // Brief grace period
                    forceReevaluate()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Health Scoring
    // ──────────────────────────────────────────────────────────────

    private fun updateHealthScore(transport: RixOptions.Transport, egress: TunnelProbe.Egress?, success: Boolean) {
        val now = System.currentTimeMillis()
        _lastProbeTime.set(now)

        val existing = _healthScores[transport]
        val newScore = if (success) {
            val latency = egress?.latencyMs ?: 100L
            HealthScore(
                latencyMs = latency,
                successRate = existing?.let { (it.successRate * 0.8 + 0.2) } ?: 0.9,
                packetLoss = existing?.let { (it.packetLoss * 0.8) } ?: 0.1,
                stabilityScore = existing?.let { (it.stabilityScore * 0.9 + 0.1).coerceIn(0.0, 1.0) } ?: 0.8,
                lastProbeAt = now,
                consecutiveFailures = 0,
                isHealthy = true
            )
        } else {
            existing?.copy(
                successRate = (existing.successRate * 0.8).coerceIn(0.0, 1.0),
                packetLoss = (existing.packetLoss * 0.8 + 0.2).coerceIn(0.0, 1.0),
                stabilityScore = (existing.stabilityScore * 0.7).coerceIn(0.0, 1.0),
                lastProbeAt = now,
                consecutiveFailures = existing.consecutiveFailures + 1,
                isHealthy = false
            ) ?: HealthScore(
                latencyMs = -1,
                successRate = 0.0,
                packetLoss = 1.0,
                stabilityScore = 0.0,
                lastProbeAt = now,
                consecutiveFailures = 1,
                isHealthy = false
            )
        }

        _healthScores[transport] = newScore
    }

    // ──────────────────────────────────────────────────────────────
    // State Transitions
    // ──────────────────────────────────────────────────────────────

    private fun transition(newState: State) {
        val oldState = _state.value
        if (oldState != newState) {
            Log.i("CoreCoordinator", "State: ${oldState::class.simpleName} -> ${newState::class.simpleName}")
            _state.value = newState
        }
    }
}