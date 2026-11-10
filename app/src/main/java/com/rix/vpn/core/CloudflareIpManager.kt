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
    private const val PROBE_TIMEOUT_MS = 8000L // Increased timeout for unreliable networks
    private const val MAX_IPS_TO_TEST = 50 // Test more IPs for better selection
    private const val MIN_SUCCESSFUL_PROBES = 3 // Minimum probes for stable score
    private const val PARALLEL_PROBES = 10 // Concurrent probe limit
    private const val PREFS_NAME = "rix_ip_scanner_cache"
    private const val SCORE_FRESHNESS_MS = 5 * 60 * 1000L // 5 minutes
    private const val OPERATOR_CACHE_DURATION_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // Known reliable Cloudflare IPs as fallback (updated periodically)
    private val FALLBACK_IPS = listOf(
        "104.16.0.1", "104.17.0.1", "104.18.0.1", "104.19.0.1",
        "104.20.0.1", "104.21.0.1", "104.22.0.1", "104.23.0.1",
        "104.24.0.1", "104.25.0.1", "104.26.0.1", "104.27.0.1",
        "104.28.0.1", "104.29.0.1", "104.30.0.1", "104.31.0.1",
        "172.64.0.1", "172.65.0.1", "172.66.0.1", "172.67.0.1",
        "172.68.0.1", "172.69.0.1", "172.70.0.1", "172.71.0.1"
    )

    private val _cachedRanges = AtomicReference<IpRangeCache>()
    private val _ipScores = ConcurrentHashMap<String, IpScore>()
    private val _currentBestIp = AtomicReference<String?>()
    private val _lastEvaluationTime = AtomicLong(0)
    private val _operatorKey = AtomicReference<String?>() // Current operator identifier
    private val _prefs = AtomicReference<SharedPreferences?>()
    private var _scope: CoroutineScope? = null

    /** Official Cloudflare IP ranges, cached with timestamp. */
    private data class IpRangeCache(
        val ranges: List<String>, // CIDR notation e.g. "104.16.0.0/12"
        val fetchedAt: Long
    )

    /** Scored IP candidate with evaluation metadata. */
    data class IpScore(
        val ip: String,
        val latencyMs: Long,
        val successRate: Double, // 0.0 - 1.0
        val packetLoss: Double, // 0.0 - 1.0
        val stabilityScore: Double, // 0.0 - 1.0 (lower variance = higher)
        val probeCount: Int,
        val lastProbedAt: Long,
        val isReachable: Boolean,
        val operatorKey: String? = null // Which operator this score belongs to
    ) {
        /** Composite score: higher is better. Weights: latency 40%, success 30%, stability 20%, loss 10% */
        val compositeScore: Double
            get() = if (!isReachable) 0.0 else {
                val latencyNorm = (1.0 - (latencyMs / 500.0).coerceIn(0.0, 1.0)) * 0.4
                val successNorm = successRate * 0.3
                val stabilityNorm = stabilityScore * 0.2
                val lossNorm = (1.0 - packetLoss) * 0.1
                latencyNorm + successNorm + stabilityNorm + lossNorm
            }

        fun copyWith(
            latencyMs: Long = this.latencyMs,
            successRate: Double = this.successRate,
            packetLoss: Double = this.packetLoss,
            stabilityScore: Double = this.stabilityScore,
            probeCount: Int = this.probeCount,
            lastProbedAt: Long = this.lastProbedAt,
            isReachable: Boolean = this.isReachable,
            operatorKey: String? = this.operatorKey
        ): IpScore = IpScore(
            ip = ip,
            latencyMs = latencyMs,
            successRate = successRate,
            packetLoss = packetLoss,
            stabilityScore = stabilityScore,
            probeCount = probeCount,
            lastProbedAt = lastProbedAt,
            isReachable = isReachable,
            operatorKey = operatorKey
        )
    }

    /** Initialize with context for SharedPreferences and network monitoring. */
    fun initialize(context: Context) {
        _prefs.set(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        _scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        detectAndSetOperator(context)
    }

    /** Get current operator key for operator-aware caching. */
    private fun detectAndSetOperator(context: Context): String? {
        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val operator = telephony.simOperator ?: telephony.networkOperator ?: "unknown"
            val networkType = when (telephony.networkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "unknown"
            }
            val key = "${operator}_$networkType"
            _operatorKey.set(key)
            key
        } catch (e: Exception) {
            _operatorKey.set("unknown")
            "unknown"
        }
    }

    /** Gets the current best IP for connection, fetching ranges if needed. */
    suspend fun getBestIp(
        context: Context,
        options: RixOptions,
        port: Int,
        forceRefresh: Boolean = false
    ): String? {
        // Initialize if needed
        if (_prefs.get() == null) initialize(context)

        // Detect operator change
        val currentOperator = _operatorKey.get() ?: detectAndSetOperator(context)

        // Ensure ranges are loaded
        val ranges = fetchRangesIfNeeded(context, forceRefresh)
        if (ranges.isEmpty()) {
            Log.e("CloudflareIp", "No Cloudflare ranges available, trying fallback IPs")
            return tryFallbackIps(port)
        }

        // Check if we have a valid cached best IP for current operator
        val currentBest = _currentBestIp.get()
        if (!forceRefresh && currentBest != null && isScoreFresh(currentBest, currentOperator)) {
            val score = _ipScores[currentBest]
            if (score != null && score.isReachable && score.compositeScore > 0.4) {
                Log.i("CloudflareIp", "Using cached best IP: $currentBest (score: ${score.compositeScore}, operator: $currentOperator)")
                return currentBest
            }
        }

        // Try operator-specific cached IPs first
        val operatorBest = getOperatorBestIp(currentOperator)
        if (operatorBest != null && !forceRefresh) {
            val score = _ipScores[operatorBest]
            if (score != null && score.isReachable && score.compositeScore > 0.4 && isScoreFresh(operatorBest, currentOperator)) {
                Log.i("CloudflareIp", "Using operator-cached best IP: $operatorBest (operator: $currentOperator)")
                _currentBestIp.set(operatorBest)
                return operatorBest
            }
        }

        // Evaluate IPs from ranges
        val bestIp = evaluateAndSelectBestIp(context, options, port, ranges, currentOperator)
        if (bestIp != null) {
            _currentBestIp.set(bestIp)
            _lastEvaluationTime.set(System.currentTimeMillis())
            // Cache for this operator
            cacheOperatorBestIp(currentOperator, bestIp)
            Log.i("CloudflareIp", "Selected best IP: $bestIp (operator: $currentOperator)")
        } else {
            // Try fallback IPs if evaluation failed
            Log.w("CloudflareIp", "No suitable IP found from ranges, trying fallback IPs")
            return tryFallbackIps(port)
        }
        return bestIp
    }

    /** Tries known fallback IPs when range evaluation fails. */
    private suspend fun tryFallbackIps(port: Int): String? {
        Log.i("CloudflareIp", "Testing ${FALLBACK_IPS.size} fallback IPs")
        val scores = mutableListOf<IpScore>()

        // Test fallback IPs in parallel batches
        for (batch in FALLBACK_IPS.chunked(PARALLEL_PROBES)) {
            val results = _scope?.async {
                batch.map { ip ->
                    evaluateIp(ip, port, "fallback")
                }.filterNotNull()
            }?.await() ?: emptyList()
            scores.addAll(results)
        }

        return scores
            .filter { it.isReachable }
            .maxByOrNull { it.compositeScore }
            ?.ip
    }

    /** Fetches Cloudflare IP ranges from the official source. */
    private suspend fun fetchRangesIfNeeded(context: Context, forceRefresh: Boolean): List<String> {
        val cached = _cachedRanges.get()
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.fetchedAt) < CACHE_DURATION_MS) {
            return cached.ranges
        }

        return withContext(Dispatchers.IO) {
            try {
                val ipv4Ranges = fetchUrl(CF_IPV4_URL)
                val ipv6Ranges = fetchUrl(CF_IPV6_URL)
                val allRanges = (ipv4Ranges + ipv6Ranges).distinct()
                    .filter { it.isNotBlank() && it.contains("/") }

                val newCache = IpRangeCache(allRanges, now)
                _cachedRanges.set(newCache)
                Log.i("CloudflareIp", "Fetched ${allRanges.size} Cloudflare ranges")
                allRanges
            } catch (e: Exception) {
                Log.e("CloudflareIp", "Failed to fetch ranges: ${e.message}")
                cached?.ranges ?: emptyList()
            }
        }
    }

    private suspend fun fetchUrl(urlString: String): List<String> = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.addRequestProperty("User-Agent", "RIX-AETHER/1.8")

        try {
            if (connection.responseCode == 200) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                }
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Evaluates a sample of IPs from the Cloudflare ranges and selects the best. */
    private suspend fun evaluateAndSelectBestIp(
        context: Context,
        options: RixOptions,
        port: Int,
        ranges: List<String>,
        operatorKey: String
    ): String? {
        // Sample IPs from ranges with geographic diversity
        val candidateIps = sampleIpsFromRanges(ranges, MAX_IPS_TO_TEST)
        if (candidateIps.isEmpty()) return null

        Log.i("CloudflareIp", "Evaluating ${candidateIps.size} candidate IPs for operator: $operatorKey")

        // Test IPs in parallel batches
        val allScores = mutableListOf<IpScore>()
        for (batch in candidateIps.chunked(PARALLEL_PROBES)) {
            val batchScores = _scope?.async {
                batch.map { ip ->
                    evaluateIp(ip, port, operatorKey)
                }.filterNotNull()
            }?.await() ?: emptyList()
            allScores.addAll(batchScores)
        }

        // Also include any previously cached good IPs for this operator
        val cachedScores = _ipScores.values.filter { it.operatorKey == operatorKey && it.isReachable && isScoreFresh(it.ip, operatorKey) }
        allScores.addAll(cachedScores)

        // Select best by composite score
        return allScores
            .filter { it.isReachable }
            .maxByOrNull { it.compositeScore }
            ?.ip
    }

    /** Samples IPs from CIDR ranges with geographic diversity. */
    private fun sampleIpsFromRanges(ranges: List<String>, maxTotal: Int): List<String> {
        val ips = mutableListOf<String>()
        val perRange = max(1, maxTotal / ranges.size)

        for (range in ranges) {
            val cidr = parseCidr(range) ?: continue
            val (startIp, endIp) = cidr
            val rangeSize = endIp - startIp + 1

            // Sample from beginning, middle, and end of each range for diversity
            val samples = mutableListOf<Long>()
            if (rangeSize <= perRange * 3) {
                // Small range - take all
                for (i in 0 until rangeSize.toInt()) samples.add(startIp + i)
            } else {
                // Large range - sample from 3 positions
                val step = rangeSize / 3
                for (pos in 0..2) {
                    val base = startIp + pos * step
                    for (i in 0 until perRange / 3 + 1) {
                        if (base + i <= endIp) samples.add(base + i)
                    }
                }
            }

            for (ip in samples.take(perRange)) {
                ips.add(intToIp(ip))
            }
            if (ips.size >= maxTotal) break
        }
        return ips.take(maxTotal)
    }

    /** Evaluates a single IP using real protocol-level probe. */
    private suspend fun evaluateIp(ip: String, port: Int, operatorKey: String): IpScore? {
        return withContext(Dispatchers.IO) {
            var totalLatency = 0L
            var successes = 0
            val latencies = mutableListOf<Long>()
            val probeCount = MIN_SUCCESSFUL_PROBES

            for (i in 0 until probeCount) {
                val start = System.nanoTime()
                val result = TunnelProbe.probeDirect(ip, port, PROBE_TIMEOUT_MS.toInt())
                val latencyMs = (System.nanoTime() - start) / 1_000_000L

                if (result != null) {
                    successes++
                    totalLatency += latencyMs
                    latencies.add(latencyMs)
                }
                if (i < probeCount - 1) Thread.sleep(50) // Reduced delay
            }

            if (successes == 0) {
                IpScore(
                    ip = ip,
                    latencyMs = -1,
                    successRate = 0.0,
                    packetLoss = 1.0,
                    stabilityScore = 0.0,
                    probeCount = probeCount,
                    lastProbedAt = System.currentTimeMillis(),
                    isReachable = false,
                    operatorKey = operatorKey
                )
            } else {
                val avgLatency = totalLatency / successes
                val successRate = successes.toDouble() / probeCount
                val packetLoss = 1.0 - successRate

                // Calculate stability (inverse of coefficient of variation)
                val stabilityScore = if (latencies.size >= 2) {
                    val mean = latencies.average()
                    val variance = latencies.map { (it.toDouble() - mean).pow(2) }.average()
                    val stdDev = sqrt(variance)
                    if (mean > 0) (1.0 - (stdDev / mean).coerceIn(0.0, 1.0)) else 0.5
                } else {
                    0.5
                }

                IpScore(
                    ip = ip,
                    latencyMs = avgLatency,
                    successRate = successRate,
                    packetLoss = packetLoss,
                    stabilityScore = stabilityScore,
                    probeCount = probeCount,
                    lastProbedAt = System.currentTimeMillis(),
                    isReachable = true,
                    operatorKey = operatorKey
                )
            }
        }
    }

    private fun isScoreFresh(ip: String, operatorKey: String): Boolean {
        val score = _ipScores[ip]
        return score != null &&
               score.operatorKey == operatorKey &&
               (System.currentTimeMillis() - score.lastProbedAt) < SCORE_FRESHNESS_MS
    }

    /** Gets the best cached IP for the given operator. */
    private fun getOperatorBestIp(operatorKey: String): String? {
        val prefs = _prefs.get() ?: return null
        val key = "best_ip_$operatorKey"
        val cached = prefs.getString(key, null)
        if (cached != null) {
            val timestamp = prefs.getLong("${key}_time", 0)
            if (System.currentTimeMillis() - timestamp < OPERATOR_CACHE_DURATION_MS) {
                return cached
            }
        }
        return null
    }

    /** Caches the best IP for the given operator. */
    private fun cacheOperatorBestIp(operatorKey: String, ip: String) {
        val prefs = _prefs.get() ?: return
        val key = "best_ip_$operatorKey"
        prefs.edit()
            .putString(key, ip)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    private fun parseCidr(cidr: String): Pair<Long, Long>? {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return null
            val ipStr = parts[0]
            val prefix = parts[1].toInt()
            val ip = ipToLong(ipStr)
            val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix))
            val start = (ip and mask) + 1 // Skip network address
            val end = ((ip and mask) or mask.inv()) - 1 // Skip broadcast
            if (start > end) return null
            start to end
        } catch (e: Exception) {
            null
        }
    }

    private fun ipToLong(ip: String): Long {
        val octets = ip.split(".").map { it.toLong() }
        return (octets[0] shl 24) + (octets[1] shl 16) + (octets[2] shl 8) + octets[3]
    }

    private fun intToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    private fun isScoreFresh(ip: String): Boolean {
        val score = _ipScores[ip]
        return score != null && (System.currentTimeMillis() - score.lastProbedAt) < SCORE_FRESHNESS_MS
    }

    /** Invalidates the current best IP, forcing re-evaluation on next request. */
    fun invalidateCurrentBest() {
        _currentBestIp.set(null)
        _lastEvaluationTime.set(0)
        Log.i("CloudflareIp", "Invalidated current best IP")
    }

    /** Call when network changes (Wi-Fi <-> Mobile, carrier change). */
    fun onNetworkChange(context: Context) {
        detectAndSetOperator(context)
        invalidateCurrentBest()
        Log.i("CloudflareIp", "Network changed, invalidated IP cache")
    }

    /** Gets the current best IP score for UI display. */
    fun getCurrentBestScore(): IpScore? {
        val ip = _currentBestIp.get()
        return if (ip != null) _ipScores[ip] else null
    }

    /** Gets all evaluated IP scores for debugging. */
    fun getAllScores(): Map<String, IpScore> = _ipScores.toMap()

    /** Gets the current operator key. */
    fun getCurrentOperator(): String = _operatorKey.get() ?: "unknown"
}

    /**
     * Gets the current best IP for connection, fetching ranges if needed.
     * Returns null if no suitable IP is found.
     */
    suspend fun getBestIp(
        context: Context,
        options: RixOptions,
        port: Int,
        forceRefresh: Boolean = false
    ): String? {
        // Ensure ranges are loaded
        val ranges = fetchRangesIfNeeded(context, forceRefresh)
        if (ranges.isEmpty()) {
            Log.e("CloudflareIp", "No Cloudflare ranges available")
            return null
        }

        // Check if we have a valid cached best IP
        val currentBest = _currentBestIp.get()
        if (!forceRefresh && currentBest != null && isScoreFresh(currentBest)) {
            val score = _ipScores[currentBest]
            if (score != null && score.isReachable && score.compositeScore > 0.5) {
                Log.i("CloudflareIp", "Using cached best IP: $currentBest (score: ${score.compositeScore})")
                return currentBest
            }
        }

        // Evaluate IPs from ranges
        val bestIp = evaluateAndSelectBestIp(context, options, port, ranges)
        if (bestIp != null) {
            _currentBestIp.set(bestIp)
            _lastEvaluationTime.set(System.currentTimeMillis())
            Log.i("CloudflareIp", "Selected best IP: $bestIp")
        }
        return bestIp
    }

    /**
     * Fetches Cloudflare IP ranges from the official source.
     * Uses cached ranges if fresh (within 24 hours).
     */
    private suspend fun fetchRangesIfNeeded(context: Context, forceRefresh: Boolean): List<String> {
        val cached = _cachedRanges.get()
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.fetchedAt) < CACHE_DURATION_MS) {
            return cached.ranges
        }

        return withContext(Dispatchers.IO) {
            try {
                val ipv4Ranges = fetchUrl(CF_IPV4_URL)
                val ipv6Ranges = fetchUrl(CF_IPV6_URL)
                val allRanges = (ipv4Ranges + ipv6Ranges).distinct()
                    .filter { it.isNotBlank() && it.contains("/") }

                val newCache = IpRangeCache(allRanges, now)
                _cachedRanges.set(newCache)
                Log.i("CloudflareIp", "Fetched ${allRanges.size} Cloudflare ranges")
                allRanges
            } catch (e: Exception) {
                Log.e("CloudflareIp", "Failed to fetch ranges: ${e.message}")
                cached?.ranges ?: emptyList()
            }
        }
    }

    private suspend fun fetchUrl(urlString: String): List<String> = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.addRequestProperty("User-Agent", "RIX-AETHER/1.0.8")

        try {
            if (connection.responseCode == 200) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                }
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Evaluates a sample of IPs from the Cloudflare ranges and selects the best.
     * Uses real TLS-over-SOCKS probe through the tunnel.
     */
    private suspend fun evaluateAndSelectBestIp(
        context: Context,
        options: RixOptions,
        port: Int,
        ranges: List<String>
    ): String? {
        // Sample IPs from ranges (deterministic sampling from each range)
        val candidateIps = sampleIpsFromRanges(ranges, MAX_IPS_TO_TEST)
        if (candidateIps.isEmpty()) return null

        Log.i("CloudflareIp", "Evaluating ${candidateIps.size} candidate IPs")

        // Test each IP with real protocol probe
        val scores = mutableListOf<IpScore>()
        for (ip in candidateIps) {
            val score = evaluateIp(ip, port)
            if (score != null) {
                scores.add(score)
                _ipScores[ip] = score
            }
        }

        // Select best by composite score
        return scores
            .filter { it.isReachable }
            .maxByOrNull { it.compositeScore }
            ?.ip
    }

    /**
     * Samples IPs from CIDR ranges deterministically.
     * Takes first few IPs from each range to get geographic diversity.
     */
    private fun sampleIpsFromRanges(ranges: List<String>, maxTotal: Int): List<String> {
        val ips = mutableListOf<String>()
        val perRange = max(1, maxTotal / ranges.size)

        for (range in ranges) {
            val cidr = parseCidr(range) ?: continue
            val (startIp, endIp) = cidr
            val count = minOf(perRange, (endIp - startIp + 1).toInt())
            for (i in 0 until count) {
                ips.add(intToIp(startIp + i))
            }
            if (ips.size >= maxTotal) break
        }
        return ips.take(maxTotal)
    }

    private fun parseCidr(cidr: String): Pair<Long, Long>? {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return null
            val ipStr = parts[0]
            val prefix = parts[1].toInt()
            val ip = ipToLong(ipStr)
            val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix))
            val start = (ip and mask) + 1 // Skip network address
            val end = ((ip and mask) or mask.inv()) - 1 // Skip broadcast
            if (start > end) return null
            start to end
        } catch (e: Exception) {
            null
        }
    }

    private fun ipToLong(ip: String): Long {
        val octets = ip.split(".").map { it.toLong() }
        return (octets[0] shl 24) + (octets[1] shl 16) + (octets[2] shl 8) + octets[3]
    }

    private fun intToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    /**
     * Evaluates a single IP using real protocol-level probe.
     * Returns null if the IP is unreachable or evaluation fails.
     */
    private suspend fun evaluateIp(ip: String, port: Int): IpScore? {
        return withContext(Dispatchers.IO) {
            var totalLatency = 0L
            var successes = 0
            val latencies = mutableListOf<Long>()
            val probeCount = MIN_SUCCESSFUL_PROBES

            for (i in 0 until probeCount) {
                val start = System.nanoTime()
                val result = TunnelProbe.probeDirect(ip, port, PROBE_TIMEOUT_MS.toInt())
                val latencyMs = (System.nanoTime() - start) / 1_000_000L

                if (result != null) {
                    successes++
                    totalLatency += latencyMs
                    latencies.add(latencyMs)
                }
                // Small delay between probes to avoid overwhelming
                if (i < probeCount - 1) Thread.sleep(100)
            }

            if (successes == 0) {
                IpScore(
                    ip = ip,
                    latencyMs = -1,
                    successRate = 0.0,
                    packetLoss = 1.0,
                    stabilityScore = 0.0,
                    probeCount = probeCount,
                    lastProbedAt = System.currentTimeMillis(),
                    isReachable = false
                )
            } else {
                val avgLatency = totalLatency / successes
                val successRate = successes.toDouble() / probeCount
                val packetLoss = 1.0 - successRate

                // Calculate stability (inverse of coefficient of variation)
                val stabilityScore = if (latencies.size >= 2) {
                    val mean = latencies.average()
                    val variance = latencies.map { (it.toDouble() - mean).pow(2) }.average()
                    val stdDev = sqrt(variance)
                    if (mean > 0) (1.0 - (stdDev / mean).coerceIn(0.0, 1.0)) else 0.5
                } else {
                    0.5
                }

                IpScore(
                    ip = ip,
                    latencyMs = avgLatency,
                    successRate = successRate,
                    packetLoss = packetLoss,
                    stabilityScore = stabilityScore,
                    probeCount = probeCount,
                    lastProbedAt = System.currentTimeMillis(),
                    isReachable = true
                )
            }
        }
    }

    private fun isScoreFresh(ip: String): Boolean {
        val score = _ipScores[ip]
        return score != null && (System.currentTimeMillis() - score.lastProbedAt) < 5 * 60 * 1000L // 5 min
    }

    /**
     * Invalidates the current best IP, forcing re-evaluation on next request.
     * Call when network conditions change (e.g., Wi-Fi to mobile, disconnection).
     */
    fun invalidateCurrentBest() {
        _currentBestIp.set(null)
        _lastEvaluationTime.set(0)
        Log.i("CloudflareIp", "Invalidated current best IP")
    }

    /**
     * Gets the current best IP score for UI display.
     */
    fun getCurrentBestScore(): IpScore? {
        val ip = _currentBestIp.get()
        return if (ip != null) _ipScores[ip] else null
    }

    /**
     * Gets all evaluated IP scores for debugging.
     */
    fun getAllScores(): Map<String, IpScore> = _ipScores.toMap()
}