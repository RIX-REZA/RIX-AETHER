package com.rix.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

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
 * asks the core to CONNECT to Cloudflare, and fetches `/cdn-cgi/trace`. That
 * request only completes if the tunnel is genuinely up, and its body carries the
 * address Cloudflare saw, i.e. the IP the VPN is giving this phone.
 *
 * Plain HTTP on port 80 on purpose: no TLS means no certificate or clock
 * dependency, and the trace endpoint is served over both.
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
     * the address is tried by name first and by literal last.
     */
    private val TRACE_ENDPOINTS = listOf(
        "cloudflare.com" to "/cdn-cgi/trace",
        "www.cloudflare.com" to "/cdn-cgi/trace",
        "1.1.1.1" to "/cdn-cgi/trace"
    )

    /** Answers with the address, city, ISP and country the far end sees. */
    private const val GEO_HOST = "ip-api.com"
    private const val GEO_PATH = "/json/?fields=status,country,countryCode,city,isp,query"

    private const val MAX_BODY = 16 * 1024

    /**
     * One end-to-end attempt. Returns null on any failure -- a half-open socket,
     * a refused SOCKS5 handshake and a dead tunnel are all "not ready yet", and
     * the caller retries rather than guessing which.
     */
    suspend fun probe(port: Int, timeoutMs: Int = 6000): Egress? = withContext(Dispatchers.IO) {
        for ((host, path) in TRACE_ENDPOINTS) {
            val body = httpGet(port, host, path, timeoutMs) ?: continue
            parseTrace(body)?.let { return@withContext it }
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
            val body = httpGet(port, GEO_HOST, GEO_PATH, timeoutMs) ?: return@withContext base
            val geo = parseGeo(body) ?: return@withContext base
            if (geo.ip.isNotBlank() && geo.ip != base.ip) {
                return@withContext base
            }
            base.copy(
                country = geo.country ?: base.country,
                city = geo.city ?: base.city,
                isp = geo.isp ?: base.isp
            )
        }

    /** Opens [host]:[port] through the core's SOCKS5 endpoint and GETs [path]. */
    private fun httpGet(socksPort: Int, host: String, path: String, timeoutMs: Int): String? {
        val socket = Socket()
        return try {
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            socks5Connect(socket, host, 80)

            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.0\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: rix\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            readAll(socket.getInputStream())
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** SOCKS5, no auth, CONNECT to a domain name so the core resolves it in-tunnel. */
    private fun socks5Connect(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // VER=5, NMETHODS=1, METHOD=0 (no authentication)
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

    private fun readAll(input: InputStream): String {
        val buffer = StringBuilder()
        val chunk = ByteArray(2048)
        while (buffer.length < MAX_BODY) {
            val n = try {
                input.read(chunk)
            } catch (t: Throwable) {
                break
            }
            if (n < 0) break
            buffer.append(String(chunk, 0, n, Charsets.UTF_8))
            // The trace ends at warp=, so the body is complete once it is there.
            // The JSON lookup has no such marker -- stopping at its first field
            // would truncate the city and the ISP that come after it.
            if (buffer.contains("\nwarp=") && !buffer.contains("{")) break
        }
        return buffer.toString()
    }

    /**
     * `/cdn-cgi/trace` answers with `key=value` lines. The status line is checked
     * first: a captive portal or a proxy error page would otherwise be parsed as
     * a successful probe with no IP in it.
     */
    private fun parseTrace(response: String): Egress? {
        val status = response.lineSequence().firstOrNull()?.trim() ?: return null
        if (!status.startsWith("HTTP/") || !status.contains(" 200")) return null

        val fields = HashMap<String, String>()
        response.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at in 1 until line.length - 1) {
                fields[line.substring(0, at).trim()] = line.substring(at + 1).trim()
            }
        }
        val ip = fields["ip"]?.takeIf { it.isNotBlank() } ?: return null
        return Egress(
            ip = ip,
            colo = fields["colo"]?.takeIf { it.isNotBlank() },
            loc = fields["loc"]?.takeIf { it.isNotBlank() },
            warp = fields["warp"]?.takeIf { it.isNotBlank() }
        )
    }

    /** The small JSON ip-api answers with, read without a JSON dependency. */
    private fun parseGeo(response: String): Egress? {
        val status = response.lineSequence().firstOrNull()?.trim() ?: return null
        if (!status.startsWith("HTTP/") || !status.contains(" 200")) return null

        val json = response.substringAfter("\r\n\r\n", "")
            .ifBlank { response.substringAfter("\n\n", "") }
            .trim()
        if (json.isEmpty() || !json.contains("\"status\":\"success\"")) return null

        return Egress(
            ip = jsonField(json, "query").orEmpty(),
            country = jsonField(json, "country")?.takeIf { it.isNotBlank() },
            city = jsonField(json, "city")?.takeIf { it.isNotBlank() },
            isp = jsonField(json, "isp")?.takeIf { it.isNotBlank() }
        )
    }

    private fun jsonField(json: String, name: String): String? {
        val key = "\"$name\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = json.indexOf('"', from)
        if (end < 0) return null
        return json.substring(from, end)
    }
}