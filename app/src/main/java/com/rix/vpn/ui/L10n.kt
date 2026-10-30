package com.rix.vpn.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.rix.vpn.core.IpFamily
import com.rix.vpn.core.LogLevel
import com.rix.vpn.core.PerfProfile
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.ScanMode
import com.rix.vpn.core.SplitMode
import com.rix.vpn.core.Transport

/**
 * Two languages, one screen each.
 *
 * Every literal is written at its call site as `s("فارسی", "English")` rather
 * than pulled out of a resource table by key. It costs a second argument, and
 * it buys the property that matters here: a translated string cannot go
 * missing, because the compiler will not compile a call without it, and a
 * screen reads the same in both languages side by side. The Persian wording
 * stays the first argument because it is the original.
 *
 * The names that come from the core -- [Transport], [ScanMode] and the rest --
 * are translated by the extensions below instead of in the enums themselves,
 * so the wire model does not have to know what the UI looks like.
 */
@Immutable
class Strings(val en: Boolean) {

    /** Picks the wording for the current language. */
    fun s(fa: String, en: String): String = if (this.en) en else fa
}

val LocalStrings = staticCompositionLocalOf { Strings(en = false) }

// ------------------------------------------------------------------- enums

fun Transport.label(s: Strings): String = if (!s.en) label else when (this) {
    Transport.MASQUE_H3 -> "MASQUE / HTTP-3"
    Transport.MASQUE_H2 -> "MASQUE / HTTP-2"
    Transport.WIREGUARD -> "WireGuard"
    Transport.GOOL -> "Gool (WG inside MASQUE)"
    Transport.GOOL_CLASSIC -> "Gool classic (WG in WG)"
    Transport.MIM -> "MASQUE in MASQUE"
}

fun Transport.desc(s: Strings): String = if (!s.en) desc else when (this) {
    Transport.MASQUE_H3 ->
        "HTTP/3 over QUIC: the lowest latency and the best speed on a phone."
    Transport.MASQUE_H2 ->
        "HTTP/2 over TLS on TCP 443: the most stable choice in Iran, and the first " +
            "fallback when the others cannot connect."
    Transport.WIREGUARD ->
        "Plain WireGuard: the lightest and the least power-hungry, but usually " +
            "blocked in Iran."
    Transport.GOOL ->
        "WireGuard wrapped in MASQUE: a balance between stability and disguise."
    Transport.GOOL_CLASSIC ->
        "A nested tunnel with two WireGuard layers, for very strict networks."
    Transport.MIM ->
        "Two MASQUE layers: the most resistance to DPI, at a little extra latency."
}

fun Transport.badge(s: Strings): String? = if (!s.en) badge else when (this) {
    Transport.MASQUE_H3 -> "Recommended"
    Transport.MASQUE_H2 -> "Compatible"
    Transport.WIREGUARD -> "Low power"
    Transport.GOOL -> "Balanced"
    Transport.MIM -> "Max disguise"
    Transport.GOOL_CLASSIC -> null
}

fun ScanMode.label(s: Strings): String = if (!s.en) label else when (this) {
    ScanMode.DEFAULT -> "Core default"
    ScanMode.TURBO -> "Turbo (fastest)"
    ScanMode.BALANCED -> "Balanced"
    ScanMode.THOROUGH -> "Thorough"
    ScanMode.VERIFIED -> "Verified"
    ScanMode.IRONCLAD -> "Ironclad (real test)"
}

fun IpFamily.label(s: Strings): String = if (!s.en) label else when (this) {
    IpFamily.V4 -> "IPv4"
    IpFamily.V6 -> "IPv6"
    IpFamily.BOTH -> "Both"
}

fun PerfProfile.label(s: Strings): String = if (!s.en) label else when (this) {
    PerfProfile.LOW -> "Low power"
    PerfProfile.MEDIUM -> "Balanced"
    PerfProfile.HIGH -> "High"
}

fun LogLevel.label(s: Strings): String = if (!s.en) label else when (this) {
    LogLevel.ERROR -> "Error"
    LogLevel.WARN -> "Warn"
    LogLevel.INFO -> "Info"
    LogLevel.DEBUG -> "Debug"
    LogLevel.TRACE -> "Trace"
}

fun SplitMode.label(s: Strings): String = if (!s.en) label else when (this) {
    SplitMode.ALL -> "All apps"
    SplitMode.ONLY -> "Selected only"
    SplitMode.EXCEPT -> "All except selected"
}

fun RixMode.label(s: Strings): String = if (!s.en) label else when (this) {
    RixMode.TUNNEL -> "Full tunnel"
    RixMode.PROXY -> "Proxy (SOCKS5)"
}

fun RixMode.short(s: Strings): String = if (!s.en) short else when (this) {
    RixMode.TUNNEL -> "All apps"
    RixMode.PROXY -> "Selected apps"
}

fun RixMode.desc(s: Strings): String = if (!s.en) desc else when (this) {
    RixMode.TUNNEL ->
        "VpnService captures the whole phone's traffic and hands it to the core; " +
            "no app can route around it."
    RixMode.PROXY ->
        "Nothing is captured: only a local SOCKS5 endpoint on 127.0.0.1 is opened " +
            "for you to point apps at."
}