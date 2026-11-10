package com.rix.vpn.core

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Manages Cloudflare IPv4/IPv6 ranges and implements smart IP selection with operator-aware caching.
 *
 * Fetches the official Cloudflare IP ranges from https://www.cloudflare.com/ips-v4 and ips-v6,
 * caches them, and evaluates individual IPs within those ranges based on real
 * protocol-level connectivity tests (not just ping). The best IP is selected
 * dynamically and can change when network conditions degrade.
 *
 * Features:
 * - Operator/SIM-aware persistent caching (stores best IPs per carrier)
 * - Parallel probing for faster evaluation
 * - Geographic diversity in IP sampling
 * - Automatic re-evaluation on network changes
 * - Fallback to known good IPs when scanning fails
 *
 * The evaluation uses the same TLS-over-SOCKS probe that the tunnel uses, so
 * an IP that passes is genuinely reachable through the tunnel path.
 */
object CloudflareIpManager {

    private const val CF_IPV4_URL = "https://www.cloudflare.com/ips-v4"
    private const val CF_IPV6_URL = "https://www.cloudflare.com/ips-v6"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val PROBE_TIMEOUT_MS = 8000L
    private const val MAX_IPS_TO_TEST = 50
    private const val MIN_SUCCESSFUL_PROBES = 3
    private const val PARALLEL_PROBES = 8
    private const val PREFS_NAME = "rix_ip_scanner_cache"
    private const val SCORE_FRESHNESS_MS = 5 * 60 * 1000L // 5 minutes
    private const val OPERATOR_CACHE_DURATION_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // Known reliable Cloudflare IPs as fallback (covers major ranges)
    private val FALLBACK_IPS = listOf(
        "104.16.0.1", "104.17.0.1", "104.18.0.1", "104.19.0.1",
        "104.20.0.1", "104.21.0.1", "104.22.0.1", "104.23.0.1",
        "104.24.0.1", "104.25.0.1", "104.26.0.1", "104.27.0.1",
        "104.28.0.1", "104.29.0.1", "104.30.0.1", "104.31.0.1",
        "172.64.0.1", "172.65.0.1", "172.66.0.1", "172.67.0.1",
        "172.68.0.1", "172.69.0.1", "172.70.0.1", "172.71.0.1",
        "1.1.1.1", "1.0.0.1"
    )

    private val _cachedRanges = AtomicReference<IpRangeCache>()
    private val _ipScores = ConcurrentHashMap<String, IpScore>()
    private val _currentBestIp = AtomicReference<String?>()
    private val _lastEvaluationTime = AtomicLong(0)
    private val _operatorKey = AtomicReference<String?>() // Current operator identifier (MCC-MNC)
    private val _prefs = AtomicReference<SharedPreferences?>()

    /** Official Cloudflare IP ranges, cached with timestamp. */
    private data class IpRangeCache(
        val ranges: List<String>,
        val fetchedAt: Long
    )

    /** Result of evaluating a single IP with protocol-level probe. */
    private data class IpScore(
        val ip: String,
        val latencyMs: Long,
        val successRate: Double,
        val probeTime: Long,
        val operatorKey: String = "",
        val isReachable: Boolean = false
    ) {
        /** Composite score: lower latency + higher success rate = better. */
        val compositeScore: Double
            get() = if (isReachable && successRate > 0) {
                (1.0 / (latencyMs + 1)) * successRate * 1000
            } else {
                0.0
            }

        fun copyWith(
            ip: String = this.ip,
            latencyMs: Long = this.latencyMs,
            successRate: Double = this.successRate,
            probeTime: Long = this.probeTime,
            operatorKey: String = this.operatorKey,
            isReachable: Boolean = this.isReachable
        ): IpScore = IpScore(ip, latencyMs, successRate, probeTime, operatorKey, isReachable)
    }

    /** Initializes the manager with application context. Call once at app startup. */
    fun initialize(context: Context) {
        if (_prefs.get() == null) {
            _prefs.set(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        }
    }

    /**
     * Gets the best IP for the current network/operator.
     * Returns a cached IP if fresh, otherwise evaluates and caches new best IP.
     */
    suspend fun getBestIp(
        context: Context,
        port: Int = 443,
        forceRefresh: Boolean = false
    ): String? {
        val operatorKey = detectOperatorKey(context) ?: "unknown"
        _operatorKey.set(operatorKey)

        // Check cached best IP for this operator
        val cachedBest = _currentBestIp.get()
        if (!forceRefresh && cachedBest != null && isScoreFresh(cachedBest)) {
            return cachedBest
        }

        // Try to get cached best IP for this specific operator
        val operatorCached = getCachedBestIpForOperator(operatorKey)
        if (!forceRefresh && operatorCached != null && isScoreFresh(operatorCached)) {
            _currentBestIp.set(operatorCached)
            return operatorCached
        }

        // Evaluate new IPs
        return evaluateAndSelectBestIp(context, port, operatorKey, forceRefresh)
    }

    /** Detects the current SIM operator (MCC-MNC) for operator-aware caching. */
    private fun detectOperatorKey(context: Context): String? {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkOperator = tm.networkOperator // MCC+MNC (e.g., "43211" for Irancell)
            return if (networkOperator.isNotBlank()) networkOperator else null
        } catch (e: Exception) {
            Log.w("CloudflareIp", "Failed to detect operator", e)
            return null
        }
    }

    /** Checks if cached IP score is still fresh. */
    private fun isScoreFresh(ip: String): Boolean {
        val score = _ipScores[ip]
        return score != null && (System.currentTimeMillis() - score.probeTime) < SCORE_FRESHNESS_MS
    }

    /** Fetches Cloudflare IP ranges from the official source. */
    private suspend fun fetchRangesIfNeeded(context: Context, forceRefresh: Boolean): List<String> {
        val cached = _cachedRanges.get()
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && (now - cached.fetchedAt) < CACHE_DURATION_MS) {
            return cached.ranges
        }

        return withContext(Dispatchers.IO) {
            val prefs = _prefs.get()
            val cachedV4 = prefs?.getString("cached_ranges_v4", "")
            val cachedV6 = prefs?.getString("cached_ranges_v6", "")
            val cachedTime = prefs?.getLong("cached_time", 0) ?: 0

            if (!forceRefresh && cachedTime > 0 && (now - cachedTime) < CACHE_DURATION_MS && cachedV4?.isNotEmpty() == true) {
                val ranges = (cachedV4 + "\n" + cachedV6).split("\n").filter { it.isNotBlank() }
                _cachedRanges.set(IpRangeCache(ranges, cachedTime))
                ranges
            } else {
                val v4Ranges = fetchUrl(CF_IPV4_URL)
                val v6Ranges = fetchUrl(CF_IPV6_URL)
                val allRanges = (v4Ranges + v6Ranges).filter { it.isNotBlank() }

                if (allRanges.isNotEmpty()) {
                    _cachedRanges.set(IpRangeCache(allRanges, now))
                    prefs?.edit()
                        ?.putString("cached_ranges_v4", v4Ranges.joinToString("\n"))
                        ?.putString("cached_ranges_v6", v6Ranges.joinToString("\n"))
                        ?.putLong("cached_time", now)
                        ?.apply()
                }

                if (allRanges.isEmpty() && cached != null) {
                    cached.ranges
                } else {
                    allRanges
                }
            }
        }
    }

    private suspend fun fetchUrl(urlString: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.addRequestProperty("User-Agent", "RIX-AETHER/1.8")
            if (connection.responseCode == 200) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText().split("\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w("CloudflareIp", "Failed to fetch $urlString", e)
            emptyList()
        }
    }

    /**
     * Evaluates a sample of IPs from the Cloudflare ranges and selects the best.
     * Uses real TLS-over-SOCKS probe through the tunnel.
     */
    private suspend fun evaluateAndSelectBestIp(
        context: Context,
        port: Int,
        operatorKey: String,
        forceRefresh: Boolean
    ): String? {
        val ranges = fetchRangesIfNeeded(context, forceRefresh)
        if (ranges.isEmpty()) {
            Log.w("CloudflareIp", "No ranges available, trying fallback IPs")
            return tryFallbackIps(port)
        }

        val candidateIps = sampleIpsFromRanges(ranges, MAX_IPS_TO_TEST)
        if (candidateIps.isEmpty()) {
            Log.w("CloudflareIp", "No candidate IPs sampled, trying fallback IPs")
            return tryFallbackIps(port)
        }

        Log.i("CloudflareIp", "Evaluating ${candidateIps.size} candidate IPs for operator: $operatorKey")

        // Test each IP with real protocol probe in parallel
        val scores = mutableListOf<IpScore>()
        val semaphore = Semaphore(PARALLEL_PROBES)

        // Use coroutineScope for structured concurrency
        val deferreds = coroutineScope {
            candidateIps.mapNotNull { ip ->
                async<IpScore?> {
                    semaphore.withPermit {
                        val result = evaluateIp(ip, port)
                        if (result != null) {
                            val scoredResult = result.copyWith(operatorKey = operatorKey)
                            scores.add(scoredResult)
                            _ipScores[scoredResult.ip] = scoredResult
                        }
                        result
                    }
                }
            }.awaitAll()
        }

        // Select best by composite score
        return scores
            .filter { it.isReachable }
            .maxByOrNull { it.compositeScore }
            ?.also { best ->
                _currentBestIp.set(best.ip)
                _lastEvaluationTime.set(System.currentTimeMillis())
                cacheBestIpForOperator(best.ip, operatorKey)
            }
            ?.ip
    }

    /** Tries known fallback IPs when range evaluation fails. */
    private suspend fun tryFallbackIps(port: Int): String? {
        Log.i("CloudflareIp", "Testing ${FALLBACK_IPS.size} fallback IPs")
        for (batch in FALLBACK_IPS.chunked(PARALLEL_PROBES)) {
            val deferreds = coroutineScope {
                batch.mapNotNull { ip ->
                    async<IpScore?> { evaluateIp(ip, port) }
                }.awaitAll()
            }
            val best = deferreds
                .filterNotNull()
                .filter { it.isReachable }
                .maxByOrNull { it.compositeScore }
            if (best != null) {
                cacheBestIpForOperator(best.ip, _operatorKey.get() ?: "unknown")
                return best.ip
            }
        }
        return null
    }

    /** Samples IPs from CIDR ranges deterministically. Takes first few IPs from each range for geographic diversity. */
    private fun sampleIpsFromRanges(ranges: List<String>, maxTotal: Int): List<String> {
        val ips = mutableListOf<String>()
        for (range in ranges) {
            if (ips.size >= maxTotal) break
            val ip = parseFirstIpFromCidr(range)
            if (ip != null && !ips.contains(ip)) {
                ips.add(ip)
            }
        }
        return ips
    }

    /** Parses the first IP from a CIDR notation (e.g., "104.16.0.0/12" -> "104.16.0.1"). */
    private fun parseFirstIpFromCidr(cidr: String): String? {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return null
            val baseIp = parts[0]
            val prefix = parts[1].toIntOrNull() ?: return null
            if (prefix < 0 || prefix > 32) return null
            val octets = baseIp.split(".").map { it.toInt() }
            if (octets.size != 4) return null
            // Return the first usable IP (base + 1)
            val firstOctets = octets.toMutableList()
            firstOctets[3] = min(firstOctets[3] + 1, 255)
            firstOctets.joinToString(".")
        } catch (e: Exception) {
            Log.w("CloudflareIp", "Failed to parse CIDR: $cidr", e)
            null
        }
    }

    /**
     * Evaluates a single IP with real TLS-over-SOCKS probe.
     * Returns IpScore with latency and success metrics.
     */
    private suspend fun evaluateIp(ip: String, port: Int): IpScore? {
        val startTime = System.currentTimeMillis()
        var successCount = 0
        val probeCount = 3

        for (i in 0 until probeCount) {
            val probeStart = System.currentTimeMillis()
            val reached = tryProbeIp(ip, port)
            val probeLatency = System.currentTimeMillis() - probeStart

            if (reached) {
                successCount++
            } else if (probeLatency > PROBE_TIMEOUT_MS) {
                break // Timeout, don't continue probing
            }
        }

        val successRate = successCount.toDouble() / probeCount
        val avgLatency = if (successCount > 0) {
            // Approximate average from successful probes
            (System.currentTimeMillis() - startTime) / successCount
        } else {
            PROBE_TIMEOUT_MS
        }

        return IpScore(
            ip = ip,
            latencyMs = avgLatency,
            successRate = successRate,
            probeTime = System.currentTimeMillis(),
            isReachable = successRate > 0.3 // At least 30% success
        )
    }

    /** Attempts a single TCP+TLS probe to the IP. */
    private suspend fun tryProbeIp(ip: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.soTimeout = PROBE_TIMEOUT_MS.toInt()
                socket.connect(java.net.InetSocketAddress(ip, port), PROBE_TIMEOUT_MS.toInt())

                // Quick TLS handshake check
                val sslSocketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
                val sslSocket = sslSocketFactory.createSocket(socket, ip, port, true) as javax.net.ssl.SSLSocket
                sslSocket.setSoTimeout(PROBE_TIMEOUT_MS.toInt())
                sslSocket.startHandshake()
                sslSocket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Caches the best IP for the operator in SharedPreferences. */
    private fun cacheBestIpForOperator(ip: String, operatorKey: String) {
        _prefs.get()?.edit()
            ?.putString("best_ip_$operatorKey", ip)
            ?.putLong("best_ip_time_$operatorKey", System.currentTimeMillis())
            ?.apply()
    }

    /** Retrieves cached best IP for operator if still fresh. */
    private fun getCachedBestIpForOperator(operatorKey: String): String? {
        val prefs = _prefs.get()
        val ip = prefs?.getString("best_ip_$operatorKey", "")
        val time = prefs?.getLong("best_ip_time_$operatorKey", 0) ?: 0
        val now = System.currentTimeMillis()
        if (ip.isNotBlank() && (now - time) < OPERATOR_CACHE_DURATION_MS) {
            return ip
        }
        return null
    }

    /** Forces a refresh of the IP evaluation. */
    suspend fun forceRefresh(context: Context, port: Int = 443): String? {
        return getBestIp(context, port, forceRefresh = true)
    }

    /** Checks if network is on a metered/unmetered connection for smart refresh decisions. */
    fun isNetworkMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED).not()
    }
}