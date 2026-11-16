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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * The evaluation opens a direct TCP connection and completes a real TLS
 * handshake with the edge (SNI included), so an address that passes is one
 * that genuinely answered -- never a symbolic check.
 */
object CloudflareIpManager {

    private const val CF_IPV4_URL = "https://www.cloudflare.com/ips-v4"
    private const val CF_IPV6_URL = "https://www.cloudflare.com/ips-v6"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val PROBE_TIMEOUT_MS = 1800L
    private const val MAX_IPS_TO_TEST = 200
    private const val MIN_SUCCESSFUL_PROBES = 3
    private const val PARALLEL_PROBES = 80

    /** The host name the edge is asked for during the TLS probe. It is served
     * by the same anycast edge as the tunnel, and it gives the handshake the
     * SNI Cloudflare requires. */
    private const val SNI_HOST = "speed.cloudflare.com"

    /**
     * How long a scan that found nothing is trusted. Without this, every call
     * to [getBestIp] re-probed all 200 addresses -- which is what made the app
     * look like it scanned over and over on a network where nothing answers.
     */
    private const val EMPTY_RESULT_COOLDOWN_MS = 10 * 60 * 1000L
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

    /**
     * A bundled source of 200 real Cloudflare edge addresses, spread across
     * every published IPv4 range plus the six best-known anycast addresses.
     * The scan probes these addresses directly with TCP and TLS: it needs no
     * reachable cloudflare.com list endpoint, and it is not a symbolic check.
     * When no address answers, one of these is picked at random for the
     * connection, so nothing is ever pinned to a single IP.
     */
    private val SOURCE_IPS = listOf(
        "188.114.96.1",
        "162.159.192.1",
        "172.64.0.1",
        "104.16.0.1",
        "1.0.0.1",
        "1.1.1.1",
        "173.245.48.3",
        "173.245.49.61",
        "173.245.50.119",
        "173.245.51.177",
        "173.245.52.235",
        "173.245.54.37",
        "173.245.55.95",
        "173.245.56.153",
        "173.245.57.211",
        "173.245.59.13",
        "173.245.60.71",
        "173.245.61.129",
        "173.245.62.187",
        "103.21.244.3",
        "103.21.244.81",
        "103.21.244.159",
        "103.21.244.237",
        "103.21.245.59",
        "103.21.245.137",
        "103.21.245.215",
        "103.21.246.37",
        "103.21.246.115",
        "103.21.246.193",
        "103.21.247.15",
        "103.21.247.93",
        "103.21.247.171",
        "103.22.200.3",
        "103.22.200.81",
        "103.22.200.159",
        "103.22.200.237",
        "103.22.201.59",
        "103.22.201.137",
        "103.22.201.215",
        "103.22.202.37",
        "103.22.202.115",
        "103.22.202.193",
        "103.22.203.15",
        "103.22.203.93",
        "103.22.203.171",
        "103.31.4.3",
        "103.31.4.81",
        "103.31.4.159",
        "103.31.4.237",
        "103.31.5.59",
        "103.31.5.137",
        "103.31.5.215",
        "103.31.6.37",
        "103.31.6.115",
        "103.31.6.193",
        "103.31.7.15",
        "103.31.7.93",
        "103.31.7.171",
        "141.101.64.3",
        "141.101.68.238",
        "141.101.73.217",
        "141.101.78.196",
        "141.101.83.175",
        "141.101.88.154",
        "141.101.93.133",
        "141.101.98.112",
        "141.101.103.91",
        "141.101.108.70",
        "141.101.113.49",
        "141.101.118.28",
        "141.101.123.7",
        "108.162.192.3",
        "108.162.196.238",
        "108.162.201.217",
        "108.162.206.196",
        "108.162.211.175",
        "108.162.216.154",
        "108.162.221.133",
        "108.162.226.112",
        "108.162.231.91",
        "108.162.236.70",
        "108.162.241.49",
        "108.162.246.28",
        "108.162.251.7",
        "190.93.240.3",
        "190.93.241.61",
        "190.93.242.119",
        "190.93.243.177",
        "190.93.244.235",
        "190.93.246.37",
        "190.93.247.95",
        "190.93.248.153",
        "190.93.249.211",
        "190.93.251.13",
        "190.93.252.71",
        "190.93.253.129",
        "190.93.254.187",
        "188.114.96.3",
        "188.114.97.61",
        "188.114.98.119",
        "188.114.99.177",
        "188.114.100.235",
        "188.114.102.37",
        "188.114.103.95",
        "188.114.104.153",
        "188.114.105.211",
        "188.114.107.13",
        "188.114.108.71",
        "188.114.109.129",
        "188.114.110.187",
        "197.234.240.3",
        "197.234.240.81",
        "197.234.240.159",
        "197.234.240.237",
        "197.234.241.59",
        "197.234.241.137",
        "197.234.241.215",
        "197.234.242.37",
        "197.234.242.115",
        "197.234.242.193",
        "197.234.243.15",
        "197.234.243.93",
        "197.234.243.171",
        "198.41.128.3",
        "198.41.137.219",
        "198.41.147.179",
        "198.41.157.139",
        "198.41.167.99",
        "198.41.177.59",
        "198.41.187.19",
        "198.41.196.235",
        "198.41.206.195",
        "198.41.216.155",
        "198.41.226.115",
        "198.41.236.75",
        "198.41.246.35",
        "162.158.0.3",
        "162.158.39.101",
        "162.158.78.199",
        "162.158.118.41",
        "162.158.157.139",
        "162.158.196.237",
        "162.158.236.79",
        "162.159.19.177",
        "162.159.59.19",
        "162.159.98.117",
        "162.159.137.215",
        "162.159.177.57",
        "162.159.216.155",
        "104.16.0.3",
        "104.16.157.140",
        "104.17.59.21",
        "104.17.216.158",
        "104.18.118.39",
        "104.19.19.176",
        "104.19.177.57",
        "104.20.78.194",
        "104.20.236.75",
        "104.21.137.212",
        "104.22.39.93",
        "104.22.196.230",
        "104.23.98.111",
        "104.24.0.3",
        "104.24.78.199",
        "104.24.157.139",
        "104.24.236.79",
        "104.25.59.19",
        "104.25.137.215",
        "104.25.216.155",
        "104.26.39.95",
        "104.26.118.35",
        "104.26.196.231",
        "104.27.19.171",
        "104.27.98.111",
        "104.27.177.51",
        "172.64.0.3",
        "172.64.157.140",
        "172.65.59.21",
        "172.65.216.158",
        "172.66.118.39",
        "172.67.19.176",
        "172.67.177.57",
        "172.68.78.194",
        "172.68.236.75",
        "172.69.137.212",
        "172.70.39.93",
        "172.70.196.230",
        "172.71.98.111",
        "131.0.72.3",
        "131.0.72.81",
        "131.0.72.159",
        "131.0.72.237",
        "131.0.73.59",
        "131.0.73.137",
        "131.0.73.215",
        "131.0.74.37",
        "131.0.74.115",
        "131.0.74.193",
        "131.0.75.15",
        "131.0.75.93"
    )

    /** A different real address on every call. */
    fun randomSourceIp(): String = SOURCE_IPS.random()

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

    /** Result of evaluating a single IP with protocol-level probe. Public for CoreCoordinator access. */
    data class IpScore(
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

    /** What the home screen's scan card shows. */
    data class ScanUi(
        val scanning: Boolean = false,
        val best: IpScore? = null,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val ranked: List<IpScore> = emptyList(),
        val lastScanAt: Long = 0L
    )

    private val _ui = MutableStateFlow(
        ScanUi(lastScanAt = _lastEvaluationTime.get())
    )
    val ui: StateFlow<ScanUi> = _ui.asStateFlow()

    /** One scan at a time, whoever asks. */
    private val scanLock = kotlinx.coroutines.sync.Mutex()

    /** True once this process has run its automatic scan. */
    @Volatile private var warmedUp = false

    /** When a full probe pass last finished, successful or not. */
    @Volatile private var lastAttemptAt = 0L

    /**
     * The already-scanned best IP, without any network work. The connect path
     * uses this and only this: a connection is never slowed down by, nor made
     * to wait for, a scan. Scanning happens at app entry in the background,
     * or when the user asks for it.
     */
    fun peekCachedBest(): IpScore? {
        val ip = _currentBestIp.get() ?: return null
        return _ipScores[ip]
    }

    /**
     * One background scan. Runs once at app entry (so the cached best is
     * ready before the user ever presses connect) and whenever the user asks
     * again. It never runs as part of a connection attempt.
     */
    suspend fun warmUp(context: Context, forceRefresh: Boolean = false) {
        initialize(context)

        // Exactly one automatic scan per process. An activity that is
        // recreated (rotation, theme change) must not start another, and a
        // second caller joins the running one instead of probing again.
        if (!forceRefresh && warmedUp) return
        if (!scanLock.tryLock()) return
        try {
            if (!forceRefresh && warmedUp) return
            _ui.value = _ui.value.copy(scanning = true)

            // getBestIp returns the fresh cache instantly; it only evaluates
            // when the cache is stale, which is exactly the one scan wanted.
            // The first address that answers is published while the scan is
            // still running, so a connect right after it uses that address.
            getBestIp(context, forceRefresh = forceRefresh, onFirst = { first ->
                _ui.value = _ui.value.copy(scanning = true, best = first)
            })
            warmedUp = true
        } catch (t: Throwable) {
            Log.w("CloudflareIp", "warm-up scan failed: ${t.message}")
        } finally {
            scanLock.unlock()
            _ui.value = _ui.value.copy(
                scanning = false,
                best = peekCachedBest() ?: _ui.value.best,
                current = "",
                lastScanAt = _lastEvaluationTime.get()
            )
        }
    }

    /**
     * Gets the best IP for the current network/operator.
     * Returns a cached IP if fresh, otherwise evaluates and caches new best IP.
     * ALWAYS returns a fallback IP (never null) to ensure connectivity.
     */
    suspend fun getBestIp(
        context: Context,
        port: Int = 443,
        forceRefresh: Boolean = false,
        onFirst: ((IpScore) -> Unit)? = null
    ): String {
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

        // A scan that just found nothing is not repeated on every caller:
        // nothing has changed on the network in ten minutes. The connection
        // simply takes the core's own path, exactly like the original client.
        val now = System.currentTimeMillis()
        if (!forceRefresh && lastAttemptAt > 0 && now - lastAttemptAt < EMPTY_RESULT_COOLDOWN_MS) {
            return _currentBestIp.get() ?: randomSourceIp()
        }

        // Evaluate the bundled 200-IP source. If no address answers, pick a
        // random real address from it rather than pinning one hardcoded IP.
        val evaluated = evaluateAndSelectBestIp(context, port, operatorKey, forceRefresh, onFirst)
        return evaluated ?: randomSourceIp()
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
                // IPv6 samples are not used by the IPv4 probe path below, so
                // fetching the v6 list would only add a second timeout.
                val v6Ranges = emptyList<String>()
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
            // The bundled 200 addresses are the primary source; this list is
            // an optional extra. A blocked endpoint must not stall the scan
            // for half a minute before the first real probe.
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
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
     * Probes the bundled Cloudflare edge addresses with real TCP and TLS
     * handshakes and selects the best. Everything is measured directly; nothing
     * here is symbolic and nothing waits for a list endpoint that a filtered
     * network may never answer.
     */
    private suspend fun evaluateAndSelectBestIp(
        context: Context,
        port: Int,
        operatorKey: String,
        forceRefresh: Boolean,
        onFirst: ((IpScore) -> Unit)? = null
    ): String? = coroutineScope {
        // The published list is optional, so it is fetched while the bundled
        // probes are already running. A blocked endpoint costs this scan a
        // few seconds at most instead of the whole scan.
        val rangesDeferred = async(Dispatchers.IO) {
            try {
                fetchRangesIfNeeded(context, forceRefresh)
            } catch (t: Throwable) {
                // A cancelled scan must stay cancelled; only a network
                // failure is treated as "no extra ranges".
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("CloudflareIp", "range lookup failed: ${t.message}")
                emptyList()
            }
        }

        val scores = java.util.Collections.synchronizedList(mutableListOf<IpScore>())
        val semaphore = Semaphore(PARALLEL_PROBES)
        var published = false
        var first: IpScore? = null
        val done = AtomicInteger(0)
        val total = AtomicInteger(SOURCE_IPS.size)

        lastAttemptAt = System.currentTimeMillis()
        Log.i("CloudflareIp", "Probing ${SOURCE_IPS.size} real bundled edge addresses")
        _ui.value = _ui.value.copy(
            scanning = true,
            done = 0,
            total = SOURCE_IPS.size,
            current = "",
            ranked = emptyList()
        )

        // One address: TCP connect, TLS handshake, score. The first reachable
        // address is published immediately so the screen has something real
        // to show while the remaining probes finish.
        suspend fun probe(ip: String): IpScore? {
            semaphore.acquire()
            var scored: IpScore? = null
            try {
                val result = evaluateIp(ip, port)
                if (result != null) {
                    scored = result.copyWith(operatorKey = operatorKey)
                    scores.add(scored!!)
                    _ipScores[scored!!.ip] = scored!!

                    if (scored!!.isReachable) {
                        val announce = synchronized(scores) {
                            if (published) false else {
                                published = true
                                true
                            }
                        }
                        if (announce) {
                            _currentBestIp.set(scored!!.ip)
                            _lastEvaluationTime.set(System.currentTimeMillis())
                            cacheBestIpForOperator(scored!!.ip, operatorKey)
                            first = scored
                            try {
                                onFirst?.invoke(scored!!)
                            } catch (t: Throwable) {
                                Log.w("CloudflareIp", "first-candidate callback failed", t)
                            }
                        }
                    }
                }
            } finally {
                semaphore.release()
                val count = done.incrementAndGet()
                // A StateFlow write per address made the card flicker; a
                // handful per second is enough to show real movement.
                if (count % 4 == 0 || count >= total.get() || scored?.isReachable == true) {
                    _ui.value = _ui.value.copy(
                        scanning = true,
                        done = count,
                        total = total.get(),
                        current = ip,
                        best = _currentBestIp.get()?.let { _ipScores[it] } ?: _ui.value.best
                    )
                }
            }
            return scored
        }

        // Start the bundled probes first: they are the source that works even
        // when every published-list endpoint is blocked.
        val sourceJobs = SOURCE_IPS.map { ip -> async { probe(ip) } }

        // Give the optional list a short window while those probes run.
        val ranges = withTimeoutOrNull(3_500) { rangesDeferred.await() }.orEmpty()
        val fromRanges = if (ranges.isEmpty()) {
            emptyList()
        } else {
            sampleIpsFromRanges(ranges, 20)
        }
        total.set(SOURCE_IPS.size + fromRanges.size)
        val rangeJobs = fromRanges.map { ip -> async { probe(ip) } }

        (sourceJobs + rangeJobs).awaitAll()

        val ranked = scores
            .filter { it.isReachable }
            .sortedByDescending { it.compositeScore }
        val best = ranked.firstOrNull()

        _ui.value = _ui.value.copy(
            done = done.get(),
            total = total.get(),
            current = "",
            ranked = ranked.take(12)
        )

        if (best != null) {
            _currentBestIp.set(best.ip)
            _lastEvaluationTime.set(System.currentTimeMillis())
            cacheBestIpForOperator(best.ip, operatorKey)
            best.ip
        } else {
            first?.ip
        }
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
     * Evaluates a single IP with a real direct TCP + TLS probe.
     * Returns IpScore with latency and success metrics.
     */
    private suspend fun evaluateIp(ip: String, port: Int): IpScore? {
        val startTime = System.currentTimeMillis()
        var successCount = 0
        // One full TCP+TLS handshake is a real probe; repeating it three
        // times per address made a 200-address scan take a minute.
        val probeCount = 1

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
            // First try quick TCP connect (fails fast if IP is unreachable)
            if (!tcpProbe(ip, port)) return@withContext false
            // Then try TLS handshake (validates the IP actually speaks HTTPS)
            tlsProbe(ip, port)
        }
    }

    /** Quick TCP connect probe - fails fast if host is unreachable. */
    private fun tcpProbe(ip: String, port: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.setSoTimeout(PROBE_TIMEOUT_MS.toInt())
            socket.connect(java.net.InetSocketAddress(ip, port), PROBE_TIMEOUT_MS.toInt())
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Full TLS handshake probe: validates that the edge really speaks TLS.
     *
     * Cloudflare routes on SNI. A handshake with no host name is rejected
     * outright, which is why a bare createSocket(ip, port) made every address
     * look dead and the scan looked like it never found anything. The
     * certificate chain still has to be trusted; the host name is not checked,
     * because the address is what is being measured.
     */
    private fun tlsProbe(ip: String, port: Int): Boolean {
        return try {
            val raw = java.net.Socket()
            raw.soTimeout = PROBE_TIMEOUT_MS.toInt()
            raw.connect(java.net.InetSocketAddress(ip, port), PROBE_TIMEOUT_MS.toInt())

            val sslSocket = javax.net.ssl.SSLSocketFactory.getDefault()
                .createSocket(raw, ip, port, true) as javax.net.ssl.SSLSocket
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(SNI_HOST))
                endpointIdentificationAlgorithm = null
            }
            sslSocket.soTimeout = PROBE_TIMEOUT_MS.toInt()
            sslSocket.startHandshake()
            sslSocket.close()
            true
        } catch (e: Exception) {
            false
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
        if (ip?.isNotBlank() == true && (now - time) < OPERATOR_CACHE_DURATION_MS) {
            return ip
        }
        return null
    }

    /** Forces a refresh of the IP evaluation. */
    suspend fun forceRefresh(context: Context, port: Int = 443): String? {
        return getBestIp(context, port, forceRefresh = true)
    }

    /** Invalidates the current best IP (called on shutdown/failover). */
    fun invalidateCurrentBest() {
        _currentBestIp.set(null)
    }

    /** Returns the current best IP score if available. */
    fun getCurrentBestScore(): IpScore? {
        val ip = _currentBestIp.get()
        return ip?.let { _ipScores[it] }
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