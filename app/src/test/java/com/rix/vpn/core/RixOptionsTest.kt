package com.rix.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration layer is the one place where a bad value becomes a bad
 * tunnel, so it is tested directly rather than through the services.
 *
 * These tests cover the contract the rest of the app relies on: out-of-range
 * numbers are clamped instead of passed through, unusable network values are
 * dropped instead of breaking the core's argv, and the escape hatch cannot
 * override the flags the app itself owns.
 */
class RixOptionsTest {

    private fun args(options: RixOptions): List<String> = options.toArgs("/tmp/aether.toml")

    /** The token after [flag], or null when the flag was not emitted. */
    private fun arg(args: List<String>, flag: String): String? {
        val at = args.indexOf(flag)
        return if (at >= 0 && at + 1 < args.size) args[at + 1] else null
    }

    @Test
    fun `port outside the unprivileged range is clamped`() {
        assertEquals("127.0.0.1:1024", arg(args(RixOptions(bindPort = 80)), "--bind"))
        assertEquals("127.0.0.1:65535", arg(args(RixOptions(bindPort = 70_000)), "--bind"))
    }

    @Test
    fun `mtu outside the workable range is clamped`() {
        assertEquals(1280, RixOptions(mtu = 100).safeMtu)
        assertEquals(9000, RixOptions(mtu = 65_000).safeMtu)
        assertEquals(1500, RixOptions().safeMtu)
    }

    @Test
    fun `dns entries that are not addresses are dropped`() {
        val args = args(
            RixOptions(dns = "1.1.1.1, example.com, 2606:4700:4700::1111, 9:9:9")
        )
        assertEquals("1.1.1.1,2606:4700:4700::1111", arg(args, "--dns"))
    }

    @Test
    fun `the vpn and the core see the same dns list`() {
        val options = RixOptions(dns = "1.1.1.1, nope, 2606:4700:4700::1111")
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), options.dnsServers())
    }

    @Test
    fun `a dns list with nothing usable is not handed to the core`() {
        val args = args(RixOptions(dns = "not-an-address"))
        assertFalse(args.contains("--dns"))
    }

    @Test
    fun `invalid network options are omitted`() {
        val args = args(
            RixOptions(
                exitLoc = "Germany",
                peer = "example.com",
                masqueSni = "bad host!",
                upstreamProxy = "ftp://proxy:21",
                fragment = true,
                fragmentSize = "0",
                fragmentDelay = "99999"
            )
        )
        assertFalse(args.contains("--exit-loc"))
        assertFalse(args.contains("--peer"))
        assertFalse(args.contains("--masque-sni"))
        assertFalse(args.contains("--upstream"))
        assertFalse(args.contains("--fragment-size"))
        assertFalse(args.contains("--fragment-delay"))
    }

    @Test
    fun `valid advanced options survive`() {
        val args = args(
            RixOptions(
                exitLoc = "de",
                peer = "162.159.192.1:2408",
                masqueSni = "consumer.example.com",
                upstreamProxy = "socks5://127.0.0.1:9050",
                routeDirect = "10.0.0.0/8, 192.168.0.0/16",
                reconnectSecs = 3,
                keepalive = 25
            )
        )
        assertEquals("DE", arg(args, "--exit-loc"))
        assertEquals("162.159.192.1:2408", arg(args, "--peer"))
        assertEquals("consumer.example.com", arg(args, "--masque-sni"))
        assertEquals("socks5://127.0.0.1:9050", arg(args, "--upstream"))
        assertEquals("10.0.0.0/8, 192.168.0.0/16", arg(args, "--route-direct"))
        assertEquals("3", arg(args, "--reconnect-secs"))
        assertEquals("25", arg(args, "--keepalive"))
    }

    @Test
    fun `custom arguments cannot override internal flags`() {
        val extra = "--tcp-keepalive 30 --bind 10.0.0.1:9999 --config /sdcard/evil.toml " +
            "--dns=8.8.8.8 --wg --h2 --scan turbo"

        val split = RixOptions().splitExtraArgs(extra)
        assertEquals(listOf("--tcp-keepalive", "30"), split.tokens)
        assertEquals(
            listOf("--bind", "--config", "--dns", "--wg", "--h2", "--scan"),
            split.protectedFlags
        )
        assertFalse(split.malformed)

        val args = args(RixOptions(extraArgs = extra))
        // The value of a protected flag goes with it: the core would otherwise
        // take it as a positional argument.
        assertFalse(args.contains("10.0.0.1:9999"))
        assertFalse(args.contains("/sdcard/evil.toml"))
        assertFalse(args.contains("8.8.8.8"))
        assertFalse(args.contains("turbo"))
        assertTrue(args.contains("--tcp-keepalive"))
        assertTrue(args.contains("30"))
    }

    @Test
    fun `a switch does not swallow the option after it`() {
        val split = RixOptions().splitExtraArgs("--h2 --foo bar")
        assertEquals(listOf("--foo", "bar"), split.tokens)
        assertEquals(listOf("--h2"), split.protectedFlags)
    }

    @Test
    fun `quoted values are kept whole`() {
        val split = RixOptions().splitExtraArgs("--iface \"My Adapter\" --x 1")
        assertEquals(listOf("--iface", "My Adapter", "--x", "1"), split.tokens)
        assertFalse(split.malformed)
    }

    @Test
    fun `an unclosed quote drops the whole field`() {
        val options = RixOptions(extraArgs = "--iface \"My Adapter")
        assertTrue(options.splitExtraArgs(options.extraArgs).malformed)

        // Nothing from a malformed field is used, because the split cannot be
        // trusted to have found the token boundaries.
        val args = args(options)
        assertFalse(args.contains("--iface"))
        assertFalse(args.contains("My Adapter"))
    }

    @Test
    fun `problems reports every correction in user terms`() {
        val problems = RixOptions(
            bindPort = 80,
            mtu = 100,
            dns = "nope",
            exitLoc = "Germany",
            peer = "example.com",
            upstreamProxy = "ftp://proxy:21",
            extraArgs = "--bind 1.2.3.4:5"
        ).problems()

        assertTrue(problems.any { it.startsWith("port:") })
        assertTrue(problems.any { it.startsWith("mtu:") })
        assertTrue(problems.any { it.startsWith("dns:") })
        assertTrue(problems.any { it.startsWith("exit country:") })
        assertTrue(problems.any { it.startsWith("peer:") })
        assertTrue(problems.any { it.startsWith("upstream:") })
        assertTrue(problems.any { it.contains("--bind") })
    }

    @Test
    fun `a clean configuration reports nothing`() {
        assertTrue(RixOptions().problems().isEmpty())
    }

    @Test
    fun `psiphon moves the core off the user's port`() {
        assertEquals(1819, RixOptions(psiphon = false, bindPort = 1819).corePort)
        assertEquals(1820, RixOptions(psiphon = true, bindPort = 1819).corePort)
        assertEquals(1821, RixOptions(psiphon = true, bindPort = 1820).corePort)
    }

    @Test
    fun `an out of range port also moves the core consistently`() {
        val options = RixOptions(bindPort = 80)
        assertEquals(1024, options.corePort)
        assertEquals(1024, options.forCore().bindPort)
    }
}