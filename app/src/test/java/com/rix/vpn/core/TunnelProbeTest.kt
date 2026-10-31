package com.rix.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing is where a probe answer becomes a claim shown to the user, so the
 * tests are about refusing answers that are not proof: a captive portal's page,
 * a truncated trace, a status-looking string, an address that is not one.
 *
 * The socket and TLS half cannot be unit tested without a network, and is
 * covered by the device run described in the audit notes; what is tested here is
 * every decision made about bytes that did arrive.
 */
class TunnelProbeTest {

    private val goodTrace = """
        fl=123abc
        h=cloudflare.com
        ip=203.0.113.9
        ts=1760000000.123
        visit_scheme=https
        uag=rix
        colo=FRA
        sliver=none
        http=http/2
        loc=DE
        tls=TLSv1.3
        sni=plaintext
        warp=on
        gateway=off
        rbi=off
        kex=X25519
    """.trimIndent()

    @Test
    fun `a complete trace is accepted`() {
        val egress = TunnelProbe.parseTraceBody(goodTrace)
        assertNotNull(egress)
        assertEquals("203.0.113.9", egress!!.ip)
        assertEquals("FRA", egress.colo)
        assertEquals("DE", egress.loc)
        assertEquals("on", egress.warp)
    }

    @Test
    fun `an IPv6 exit address is accepted`() {
        val trace = goodTrace.replace("ip=203.0.113.9", "ip=2606:4700:4700::1111")
        assertEquals("2606:4700:4700::1111", TunnelProbe.parseTraceBody(trace)?.ip)
    }

    @Test
    fun `a trace that stopped before warp is refused`() {
        val truncated = goodTrace.lineSequence()
            .takeWhile { !it.startsWith("warp=") }
            .joinToString("\n")
        assertNull(TunnelProbe.parseTraceBody(truncated))
    }

    @Test
    fun `a trace without colo is refused`() {
        val trace = goodTrace.lines().filterNot { it.startsWith("colo=") }.joinToString("\n")
        assertNull(TunnelProbe.parseTraceBody(trace))
    }

    @Test
    fun `a lowercase colo is not an edge code`() {
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("colo=FRA", "colo=fra")))
    }

    @Test
    fun `a country that is not two letters is refused`() {
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("loc=DE", "loc=DEU")))
    }

    @Test
    fun `a warp value that is neither on nor off is refused`() {
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("warp=on", "warp=maybe")))
    }

    @Test
    fun `a captive portal page is not a trace`() {
        val portal = "<html><body>Sign in to Wi-Fi. ip=10.0.0.1</body></html>"
        assertNull(TunnelProbe.parseTraceBody(portal))
    }

    @Test
    fun `an address field that is not an address is refused`() {
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("ip=203.0.113.9", "ip=203.0.113.999")))
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("ip=203.0.113.9", "ip=example.com")))
        assertNull(TunnelProbe.parseTraceBody(goodTrace.replace("ip=203.0.113.9", "ip=1:2:3:4:5:6:7:8:9")))
    }

    @Test
    fun `an empty body is refused`() {
        assertNull(TunnelProbe.parseTraceBody(""))
    }

    @Test
    fun `only an exact 200 is accepted`() {
        assertTrue(TunnelProbe.isOkStatusLine("HTTP/1.1 200 OK"))
        assertTrue(TunnelProbe.isOkStatusLine("HTTP/1.0 200"))
        assertTrue(TunnelProbe.isOkStatusLine("HTTP/1.1 200"))

        assertFalse(TunnelProbe.isOkStatusLine("HTTP/1.1 2000 OK"))
        assertFalse(TunnelProbe.isOkStatusLine("HTTP/1.1 204 No Content"))
        assertFalse(TunnelProbe.isOkStatusLine("HTTP/1.1 302 Found"))
        assertFalse(TunnelProbe.isOkStatusLine("HTTP/1.1 407 Proxy Authentication Required"))
        assertFalse(TunnelProbe.isOkStatusLine("HTTP/2 200"))
        assertFalse(TunnelProbe.isOkStatusLine("ICY 200 OK"))
        assertFalse(TunnelProbe.isOkStatusLine("<html>200</html>"))
    }

    // ------------------------------------------------------------------ geo

    private val goodGeo = """
        {"success":true,"ip":"203.0.113.9","country":"Germany","city":"Frankfurt",
         "connection":{"asn":12345,"isp":"Example Networks","domain":"example.net"}}
    """.trimIndent()

    @Test
    fun `a successful lookup is read`() {
        val geo = TunnelProbe.parseGeoBody(goodGeo)
        assertNotNull(geo)
        assertEquals("203.0.113.9", geo!!.ip)
        assertEquals("Germany", geo.country)
        assertEquals("Frankfurt", geo.city)
        assertEquals("Example Networks", geo.isp)
    }

    @Test
    fun `a failed lookup is refused`() {
        assertNull(
            TunnelProbe.parseGeoBody(
                """{"success":false,"message":"reserved range"}"""
            )
        )
    }

    @Test
    fun `a lookup without an address is refused`() {
        assertNull(TunnelProbe.parseGeoBody("""{"success":true,"country":"Germany"}"""))
        assertNull(TunnelProbe.parseGeoBody("""{"success":true,"ip":"not-an-ip"}"""))
    }

    @Test
    fun `a truncated lookup body is refused`() {
        assertNull(TunnelProbe.parseGeoBody("""{"success":true,"ip":"203.0.11"""))
    }
}