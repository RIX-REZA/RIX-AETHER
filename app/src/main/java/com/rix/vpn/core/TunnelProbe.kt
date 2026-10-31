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
 *
 * Why this exists: everything the service can check before this point is local.
 * `awaitSocks` only shows that the core is listening on 127.0.0.1, and
 * `awaitHevAlive` only shows that the tun2socks thread did not fall over. Neither
 * says anything about WARP, so reporting "connected" there paints a green screen
 * over a tunnel that has not carried a single packet yet -- which is exactly what
 * it did: the UI said متصل while the handshake to Cloudflare had not happened.
 *
 * The probe is a real end-to-end request: it opens the app's own SOCKS5 endpoint,
 * asks the core to CONNECT to Cloudflare over TLS, and fetches `/cdn-cgi/trace`.
 * That request only completes if the tunnel is genuinely up, and its body carries
 * the address Cloudflare saw, i.e. the IP the VPN is giving this phone.
 *
 * The request is HTTPS, not HTTP. A plaintext probe can be answered by any
 * middlebox on the path -- a captive portal, a transparent proxy, the network's
 * own injection -- and every one of those answers would have been taken as proof
 * that the tunnel works. TLS with the platform's default trust store and
 * hostname verification means the answer comes from a host that proved it is
 * Cloudflare, so a successful probe is also evidence about the path rather than
 * only about the bytes.
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
        val isp: String? = null
    )

    /**
     * The same endpoint under three names.
     *
     * `cloudflare.com` is answered by whichever edge is nearest, so on a
     * network where DNS for that name is poisoned or the name itself is
     * filtered the probe would fail even though the tunnel is fine. The zone
     * serves the same trace under `www.`, and 1.1.1.1 answers it directly, so
     * the address is tried by name first and by literal last. The literal is
     * only safe because TLS still runs: its certificate has to name 1.1.1.1 or
     * the attempt fails, which is a failure the loop simply moves past.
     */
    private val TRACE_TARGETS = listOf(
        "cloudflare.com" to "/cdn-cgi/trace",
        "www.cloudflare.com" to "/cdn-cgi/trace",
        "1.1.1.1" to "/cdn-cgi/trace"
    )

    /**
     * Answers with the address, city, ISP and country the far end sees.
     *
     * HTTPS for the same reason as the trace: this request travels through the
     * tunnel too, and its answer is shown to the user as where they are. A
     * plaintext answer could be rewritten by the exit network, which would make
     * the location tile lie about the exit the user chose.
     *
     * The free JSON services that offer HTTPS are rate limited, so this is
     * best effort and its failure only means the tile keeps what the trace said.
     */
    private const val GEO_HOST = "ipwho.is"
    private const val GEO_PATH = "/?fields=success,ip,country,city,connection"

    private const val MAX_HEAD = 8 * 1024
    private const val MAX_BODY = 16 * 1024

    /**
     * An address, not a name that would have to be resolved.
     *
     * Parsed rather than matched loosely: an HTML error page that happens to
     * contain `ip=1.2.3.999` or `ip=foo` must not pass for a trace.
     */
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

    /**
     * One end-to-end attempt. Returns null on any failure -- a half-open socket,
     * a refused SOCKS5 handshake, a failed certificate check and a dead tunnel
     * are all "not ready yet", and the caller retries rather than guessing which.
     */
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

    /**
     * Adds the geography a country/city lookup knows, without ever replacing the
     * address the tunnel itself reported.
     *
     * The trace gives an IP and the edge that answered it; it does not say where
     * that IP is registered. The lookup answers that, but it is a second request
     * through the same tunnel and can be blocked on its own, so a failure here
     * only means the tile keeps showing what the trace already said.
     *
     * The answer is only used when the lookup saw the *same* address: behind a
     * rotating exit, or when the second request took another path, a different
     * address would make the tile name a country the user is not in.
     */
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

    // ------------------------------------------------------------------ https

    /**
     * Opens [host]:443 through the core's SOCKS5 endpoint and GETs [path] over
     * TLS, returning only a complete 200 response body.
     *
     * Verification is left exactly as the platform sets it: the default
     * `SSLSocketFactory`, the system trust store, and
     * `endpointIdentificationAlgorithm = "HTTPS"`, which is what makes the
     * handshake fail on a certificate issued for another name. Nothing here
     * weakens it, and the socket is closed on every path.
     */
    private fun httpsGet(
        socksPort: Int,
        host: String,
        path: String,
        deadline: Long,
        job: Job?
    ): String? {
        val socket = Socket()
        // A cancellation reaches blocking reads as a closed socket: the service
        // stops the session while this call can be parked in a read for a full
        // timeout, and the proxy half of the tunnel is going away underneath it
        // anyway.
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

            // Never hand the TLS layer a 0 timeout: on a Socket that means
            // "wait forever", which is the opposite of what a deadline means.
            val left = remainingMs(deadline)
            if (left <= 0) return null
            socket.soTimeout = left.coerceAtMost(60_000L).toInt()
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val created = factory.createSocket(socket, host, 443, true) as SSLSocket
            ssl = created
            created.soTimeout = left.coerceAtMost(60_000L).toInt()
            // Hostname verification. Without this line a certificate for any
            // name would be accepted for this one -- `createSocket` over an
            // existing socket does not turn it on by itself.
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

    /**
     * Reads one HTTP/1.x response and returns its body only when the response is
     * complete and successful.
     *
     * Complete means the framing said so -- a declared Content-Length that was
     * fully read, the terminal chunk of a chunked body, or end of stream for a
     * close-delimited one. A body that stops early is a truncated answer, and a
     * truncated trace parses into an Egress with the fields that happened to
     * arrive; that is precisely the fabricated success this refuses to report.
     */
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
        // Strict: exactly 200. A captive portal's 302 or a proxy's 407 is a
        // proxy answering, not Cloudflare.
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
                // Close-delimited: the body is complete only at end of stream.
                // Hitting the size cap before that end is a truncated answer and
                // is refused rather than parsed for whatever happened to arrive.
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

    /** Chunked framing, decoded; an unterminated body is not a body. */
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

        // VER=5, NMETHODS=1, METHOD=0 (no authentication)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = readFully(input, 2)
        // A proxy that wants authentication (0x02) is still a refusal here:
        // this endpoint is the app's own core, and it is configured for none.
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
        // Consume the bound address so the stream is positioned at the payload.
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

    /**
     * Checks a status line exactly: `HTTP/1.0 200` or `HTTP/1.1 200`, nothing
     * else. `contains(" 200")` also matched nonsense such as `HTTP/1.1 2000`,
     * and any status whose reason phrase happened to mention 200.
     */
    private val STATUS_200 = Regex("""^HTTP/1\.[01] 200(?: .*)?$""")

    /** Internal so the exactness itself can be unit tested. */
    internal fun isOkStatusLine(line: String): Boolean = STATUS_200.matches(line.trim())

    /**
     * `/cdn-cgi/trace` answers with `key=value` lines.
     *
     * A real trace always carries `ip`, `loc`, `colo` and `warp`; requiring the
     * address to be an address and the edge to look like an edge is what stops
     * an HTML page with a stray `ip=` in it -- or a truncated body that only got
     * as far as the first line -- from being reported as a working tunnel.
     */
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

    /**
     * The JSON ipwho.is answers with, read without a JSON dependency.
     *
     * `"success":true` is required, and so is an address: an error document, a
     * rate-limit notice or a half-delivered response is not a location.
     */
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

    // ------------------------------------------------------------------ time

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
}