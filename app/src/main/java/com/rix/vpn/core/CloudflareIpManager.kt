package com.rix.vpn.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * Manages Cloudflare IPv4 ranges and implements smart IP selection.
 *
 * Fetches the official Cloudflare IPv4 ranges from https://www.cloudflare.com/ips-v4,
 * caches them, and evaluates individual IPs within those ranges based on real
 * protocol-level connectivity tests (not just ping). The best IP is selected
 * dynamically and can change when network conditions degrade.
 *
 * The evaluation uses the same TLS-over-SOCKS probe that the tunnel uses, so
 * an IP that passes is genuinely reachable through the tunnel path.
 */
object CloudflareIpManager {

    private const val CF_IPV4_URL = "https://www.cloudflare.com/ips-v4"
    private const val CF_IPV6_URL = "https://www.cloudflare.com/ips-v6"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val PROBE_TIMEOUT_MS = 5000L
    private const val MAX_IPS_TO_TEST = 20 // Limit concurrent tests
    private const val MIN_SUCCESSFUL_PROBES = 3 // Minimum probes for stable score

    private val _cachedRanges = AtomicReference<IpRangeCache>()
    private val _ipScores = ConcurrentHashMap<String, IpScore>()
    private val _currentBestIp = AtomicReference<String?>()
    private val _lastEvaluationTime = AtomicLong(0)

    /** Official Cloudflare IPv4 ranges, cached with timestamp. */
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
        val isReachable: Boolean
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
            isReachable: Boolean = this.isReachable
        ): IpScore = IpScore(
            ip = ip,
            latencyMs = latencyMs,
            successRate = successRate,
            packetLoss = packetLoss,
            stabilityScore = stabilityScore,
            probeCount = probeCount,
            lastProbedAt = lastProbedAt,
            isReachable = isReachable
        )
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
                return IpScore(
                    ip = ip,
                    latencyMs = -1,
                    successRate = 0.0,
                    packetLoss = 1.0,
                    stabilityScore = 0.0,
                    probeCount = probeCount,
                    lastProbedAt = System.currentTimeMillis(),
                    isReachable = false
                )
            }

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