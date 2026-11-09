package com.rix.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Proves the tunnel actually carries traffic, and reads back the egress IP.
 */
object TunnelProbe {

    /** What the far end reported about us. */
    data class Egress(
        val ip: String,
        val colo: String? = null,
        val loc: String? = null,
        val warp: String? = null,
        val country: String? = null,
        val city: String? = null,
        val isp: String? = null,
        val latencyMs: Long = -1 // Added for health scoring
    )

    private val TRACE_TARGETS = listOf(
        "cloudflare.com" to "/cdn-cgi/trace",
        "www.cloudflare.com" to "/cdn-cgi/trace",
        "1.1.1.1" to "/cdn-cgi/trace"
    )

    private const val GEO_HOST = "ipwho.is"
    private const val GEO_PATH = "/?fields=success,ip,country,city,connection"

    private const val MAX_HEAD = 8 * 1024
    private const val MAX_BODY = 16 * 1024

    /** Probe result for health scoring. */
    data class Result(
        val latencyMs: Long,
        val packetLoss: Double,
        val success: Boolean,
        val healthScore: Double // 0.0 - 1.0
    )

    /** Probes the tunnel through the core's SOCKS5 endpoint. */
    suspend fun probe(port: Int, timeoutMs: Int = 6000): Egress? = withContext(Dispatchers.IO) {
        if (port <= 0 || timeoutMs <= 0) return@withContext null
        val job = coroutineContext[Job]
        val deadline = deadlineAfter(timeoutMs.toLong())
        for ((host, path) in TRACE_TARGETS) {
            ensureActive()
            if (remainingMs(deadline) < 250) return@withContext null
            val body = httpsGet(port, host, path, deadline, job) ?: continue
            parseTraceBody(body)?.let { return@withContext it }
        }
        null
    }

    /** Probes a specific egress IP and returns latency/loss metrics. */
    suspend fun probeEgress(ip: String, port: Int, timeoutMs: Int = 5000): Result? = withContext(Dispatchers.IO) {
        if (ip.isBlank() || port <= 0) return@withContext null

        val startTime = System.nanoTime()
        val egress = probeDirect(ip, port, timeoutMs)
        val latencyMs = (System.nanoTime() - startTime) / 1_000_000L

        if (egress != null) {
            return Result(
                latencyMs = latencyMs,
                packetLoss = 0.0,
                success = true,
                healthScore = when {
                    latencyMs < 100 -> 1.0
                    latencyMs < 300 -> 0.8
                    latencyMs < 500 -> 0.6
                    latencyMs < 1000 -> 0.4
                    latencyMs < 2000 -> 0.2
                    else -> 0.1
                }
            )
        } else {
            return Result(
                latencyMs = -1,
                packetLoss = 1.0,
                success = false,
                healthScore = 0.0
            )
        }
    }

    /** Adds geography lookup without replacing the tunnel's reported address. */
    suspend fun enrich(base: Egress, port: Int, timeoutMs: Int = 5_000): Egress =
        withContext(Dispatchers.IO) {
            if (port <= 0 || timeoutMs <= 0) return@withContext base
            val job = coroutineContext[Job]
            val deadline = deadlineAfter(timeoutMs.toLong())
            val body = httpsGet(port, GEO_HOST, GEO_PATH, deadline, job)
                ?: return@withContext base
            val geo = parseGeoBody(body) ?: return@withContext base
            if (geo.ip.isBlank() || geo.ip != base.ip) {
                return@withContext base
            }
            base.copy(
                country = geo.country ?: base.country,
                city = geo.city ?: base.city,
                isp = geo.isp ?: base.isp
            )
        }

    // ------------------------------------------------------------------ https (internal for probeDirect extension)

    /**
     * Opens [host]:443 through the core's SOCKS5 endpoint and GETs [path] over TLS.
     * Internal visibility allows probeDirect extension to call it.
     */
    internal fun httpsGet(
        socksPort: Int,
        host: String,
        path: String,
        deadline: Long,
        job: Job?
    ): String? {
        val socket = Socket()
        val registration = job?.invokeOnCompletion { runCatching { socket.close() } }
        var ssl: SSLSocket? = null
        try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress("127.0.0.1", socksPort),
                connectTimeout(deadline).toInt()
            )
            socket.soTimeout = connectTimeout(deadline).toInt()
            socks5Connect(socket, host, 443)

            val left = remainingMs(deadline)
            if (left <= 0) return null
            socket.soTimeout = left.coerceAtMost(60_000L).toInt()
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val created = factory.createSocket(socket, host, 443, true) as SSLSocket
            ssl = created
            created.soTimeout = left.coerceAtMost(60_000L).toInt()
            created.sslParameters = created.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            created.startHandshake()

            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: rix\r\n")
                append("Accept: text/plain\r\n")
                append("Connection: close\r\n\r\n")
            }
            created.outputStream.apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            return readResponse(created.inputStream, deadline)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { registration?.dispose() }
            runCatching { ssl?.close() }
            runCatching { socket.close() }
        }
    }

    private fun readResponse(input: InputStream, deadline: Long): String? {
        var head = ByteArray(0)
        val chunk = ByteArray(2048)
        while (true) {
            if (remainingMs(deadline) <= 0) return null
            val n = try {
                input.read(chunk)
            } catch (t: Throwable) {
                return null
            }
            if (n < 0) return null
            head += chunk.copyOf(n)
            val at = indexOf(head, HEAD_END)
            if (at >= 0) {
                val headerBytes = head.copyOfRange(0, at)
                val rest = head.copyOfRange(at + HEAD_END.size, head.size)
                return finishBody(headerBytes, rest, input, deadline)
            }
            if (head.size > MAX_HEAD) return null
        }
    }

    private fun finishBody(
        headerBytes: ByteArray,
        initial: ByteArray,
        input: InputStream,
        deadline: Long
    ): String? {
        val header = String(headerBytes, Charsets.ISO_8859_1)
        val lines = header.split("\r\n")
        val statusLine = lines.firstOrNull() ?: return null
        if (!isOkStatusLine(statusLine)) return null

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }

        val declared = headers["content-length"]?.toLongOrNull()
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true

        return when {
            chunked -> readChunked(initial, input, deadline)
            declared != null -> {
                if (declared < 0 || declared > MAX_BODY) return null
                val body = ByteArray(declared.toInt())
                if (initial.size >= body.size) {
                    System.arraycopy(initial, 0, body, 0, body.size)
                } else {
                    System.arraycopy(initial, 0, body, 0, initial.size)
                    val extra = readExactly(input, body.size - initial.size, deadline)
                        ?: return null
                    System.arraycopy(extra, 0, body, initial.size, extra.size)
                }
                String(body, Charsets.UTF_8)
            }
            else -> {
                val body = StringBuilder(String(initial, Charsets.UTF_8))
                val buffer = ByteArray(2048)
                var ended = false
                while (body.length < MAX_BODY) {
                    if (remainingMs(deadline) <= 0) return null
                    val n = try {
                        input.read(buffer)
                    } catch (t: Throwable) {
                        return null
                    }
                    if (n < 0) {
                        ended = true
                        break
                    }
                    body.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                if (!ended) return null
                body.toString()
            }
        }
    }

    private fun readChunked(initial: ByteArray, input: InputStream, deadline: Long): String? {
        var pending = initial
        val out = StringBuilder()
        while (true) {
            val lineEnd = indexOf(pending, CRLF)
            if (lineEnd < 0) {
                pending += readMore(input, deadline) ?: return null
                continue
            }
            val size = pending.copyOfRange(0, lineEnd)
                .toString(Charsets.ISO_8859_1)
                .substringBefore(';')
                .trim()
                .toIntOrNull(16) ?: return null
            val rest = pending.copyOfRange(lineEnd + 2, pending.size)
            if (size == 0) return out.toString()
            if (out.length + size > MAX_BODY) return null
            val need = size + 2
            var data = rest
            while (data.size < need) {
                data += readMore(input, deadline) ?: return null
            }
            out.append(String(data, 0, size, Charsets.UTF_8))
            pending = data.copyOfRange(need, data.size)
        }
    }

    private fun readMore(input: InputStream, deadline: Long): ByteArray? {
        if (remainingMs(deadline) <= 0) return null
        val buffer = ByteArray(2048)
        val n = try {
            input.read(buffer)
        } catch (t: Throwable) {
            return null
        }
        if (n <= 0) return null
        return buffer.copyOf(n)
    }

    private fun readExactly(input: InputStream, count: Int, deadline: Long): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            if (remainingMs(deadline) <= 0) return null
            val n = try {
                input.read(out, read, count - read)
            } catch (t: Throwable) {
                return null
            }
            if (n < 0) return null
            read += n
        }
        return out
    }

    /** SOCKS5, no auth, CONNECT to a domain name so the core resolves it in-tunnel. */
    private fun socks5Connect(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = readFully(input, 2)
        if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
            throw IllegalStateException("socks5: unsupported auth method ${greeting[1]}")
        }

        val name = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(4 + 1 + name.size + 2)
        request[0] = 0x05                    // VER
        request[1] = 0x01                    // CONNECT
        request[2] = 0x00                    // RSV
        request[3] = 0x03                    // ATYP = domain
        request[4] = name.size.toByte()
        System.arraycopy(name, 0, request, 5, name.size)
        request[5 + name.size] = ((port shr 8) and 0xFF).toByte()
        request[6 + name.size] = (port and 0xFF).toByte()
        out.write(request)
        out.flush()

        val reply = readFully(input, 4)
        if (reply[0].toInt() != 0x05) throw IllegalStateException("socks5: bad version ${reply[0]}")
        if (reply[1].toInt() != 0x00) {
            throw IllegalStateException("socks5: connect refused (0x${"%02x".format(reply[1])})")
        }
        when (reply[3].toInt()) {
            0x01 -> readFully(input, 4 + 2)
            0x04 -> readFully(input, 16 + 2)
            0x03 -> readFully(input, readFully(input, 1)[0].toInt() + 2)
            else -> throw IllegalStateException("socks5: bad ATYP ${reply[3]}")
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw IllegalStateException("socks5: unexpected end of stream")
            read += n
        }
        return buffer
    }

    // ------------------------------------------------------------------ parse

    private val STATUS_200 = Regex("""^HTTP/1\.[01] 200(?: .*)?$""")

    internal fun isOkStatusLine(line: String): Boolean = STATUS_200.matches(line.trim())

    internal fun parseTraceBody(body: String): Egress? {
        val fields = HashMap<String, String>()
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            val at = trimmed.indexOf('=')
            if (at in 1 until trimmed.length) {
                fields[trimmed.substring(0, at)] = trimmed.substring(at + 1)
            }
        }
        val ip = fields["ip"]?.trim()?.takeIf { isIpLiteral(it) } ?: return null
        val warp = fields["warp"]?.trim()?.lowercase()?.takeIf { it == "on" || it == "off" }
            ?: return null
        val colo = fields["colo"]?.trim()?.takeIf { COLO.matches(it) } ?: return null
        val loc = fields["loc"]?.trim()?.takeIf { LOC.matches(it) } ?: return null

        return Egress(ip = ip, colo = colo, loc = loc, warp = warp)
    }

    internal fun parseGeoBody(body: String): Egress? {
        if (!SUCCESS_TRUE.containsMatchIn(body)) return null
        val ip = jsonField(body, "ip")?.trim()?.takeIf { isIpLiteral(it) } ?: return null
        return Egress(
            ip = ip,
            country = jsonField(body, "country")?.takeIf { it.isNotBlank() },
            city = jsonField(body, "city")?.takeIf { it.isNotBlank() },
            isp = jsonField(body, "isp")?.takeIf { it.isNotBlank() }
        )
    }

    private val SUCCESS_TRUE = Regex("""["']?success["']?\s*:\s*true""")
    private val COLO = Regex("""^[A-Z]{3}$""")
    private val LOC = Regex("""^[A-Z]{2}$""")

    private fun jsonField(json: String, name: String): String? {
        val key = "\"$name\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = json.indexOf('"', from)
        if (end < 0) return null
        return json.substring(from, end)
    }

    // ------------------------------------------------------------------ utils

    private fun deadlineAfter(timeoutMs: Long): Long =
        System.nanoTime() + timeoutMs * 1_000_000L

    private fun remainingMs(deadline: Long): Long =
        (deadline - System.nanoTime()) / 1_000_000L

    private fun connectTimeout(deadline: Long): Long =
        remainingMs(deadline).coerceIn(1L, 10_000L)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val HEAD_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    private fun isIpLiteral(value: String): Boolean =
        isIpv4Literal(value) || isIpv6Literal(value)

    private fun isIpv4Literal(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toInt() in 0..255
        }
    }

    private fun isIpv6Literal(text: String): Boolean {
        if (!text.contains(':')) return false
        if (text.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) {
            return false
        }
        val halves = text.split("::")
        if (halves.size > 2) return false
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val tail = if (halves.size == 2) {
            if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
        } else {
            emptyList()
        }
        val groups = head + tail
        var count = 0
        for ((index, group) in groups.withIndex()) {
            if (group.isEmpty()) return false
            if (group.contains('.')) {
                if (index != groups.lastIndex || !isIpv4Literal(group)) return false
                count += 2
            } else {
                if (group.length > 4) return false
                count += 1
            }
        }
        return if (halves.size == 2) count <= 7 else count == 8
    }
}

/**
 * Extension to TunnelProbe for direct IP probing (bypasses DNS).
 * Used by CloudflareIpManager for smart IP selection.
 */
suspend fun TunnelProbe.probeDirect(
    ip: String,
    port: Int,
    timeoutMs: Int = 5000
): Egress? = withContext(Dispatchers.IO) {
    if (port <= 0 || timeoutMs <= 0) return@withContext null
    val job = coroutineContext[Job]
    val deadline = deadlineAfter(timeoutMs.toLong())

    // Use IP literal directly as host for TLS SNI
    val body = httpsGet(port, ip, "/cdn-cgi/trace", deadline, job) ?: return@withContext null
    parseTraceBody(body)
}