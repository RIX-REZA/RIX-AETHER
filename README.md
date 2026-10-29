# RIX — Android client for the Aether WARP/MASQUE core

A Material 3 Android front end for [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether),
a Rust userspace WARP core built around MASQUE over HTTP/3 and HTTP/2.

RIX does not reimplement the tunnel. It packages the upstream core as
`libaether.so`, adds a userspace TCP/IP bridge so Android can hand it a `VpnService`
descriptor, and drives both from Kotlin.

## Packet path

Full-tunnel mode:

```
every app -> TUN (VpnService) -> libhev-socks5-tunnel.so (lwip tun2socks)
          -> SOCKS5 127.0.0.1:1819 -> libaether.so -> WARP / MASQUE -> internet
```

Proxy mode: no TUN and no capture. `libaether.so` alone serves a local SOCKS5
endpoint that you point a browser or a single app at.

The upstream core does **not** create a TUN device — it only serves SOCKS5 — which
is why the tun2socks layer exists at all.

## Two modes

| Mode | Capture | What it is for |
|---|---|---|
| تونل کامل (full tunnel) | all apps, via `VpnService` | normal use: the whole phone goes through WARP |
| پروکسی (proxy only) | none | when another VPN already owns the phone, or only one app should be tunnelled |

Split tunnelling is available in full-tunnel mode: all apps, only the chosen
apps, or all except the chosen apps.

## Options

The UI promotes the settings a phone user actually changes — transport
(MASQUE H3/H2, WireGuard, gool, gool-classic, MASQUE-in-MASQUE), scan mode,
port, DNS, MTU, IP family, exit country, manual peer, SNI, upstream proxy, ECH,
route-direct/block, reconnect and keepalive, ClientHello fragmentation, perf
profile, log level.

The upstream core has ~136 flags; everything not promoted is reachable through
the **آرگومانهای دلخواه** field, whose tokens are appended last so they win.

## Layout

```
projects/rix/
  app/src/main/cpp/jni_bridge.c        dlopen bridge to both native libraries
  app/src/main/java/com/rix/vpn/
    core/Native.kt                     the JNI surface (JSON in, JSON out)
    core/RixOptions.kt                 UI model -> real CLI argv
    core/RixRuntime.kt                 single owner of core + tun2socks + state
    service/RixVpnService.kt           full tunnel
    service/RixProxyService.kt         proxy only
    ui/                                Compose screens (Persian, RTL)
  .github/workflows/android-rix.yml    builds both .so, then the APK
```

## Building

CI does it. `libaether.so` needs BoringSSL, quiche and boringtun cross-compiled
with the NDK, which does not fit in a 2 GB container:

```
gh workflow run android-rix.yml -f abis=arm64-v8a,armeabi-v7a,x86_64
```

Locally, with the native libraries already placed in `app/src/main/jniLibs/<abi>/`:

```
gradle :app:assembleDebug -PrixAbis=arm64-v8a
```

## Licence and naming

RIX is a derivative work of Aether and is licensed **AGPL-3.0-only**, the same
licence as upstream. See `LICENSE` and `NOTICE`.

The Aether project's [TRADEMARK.md](https://github.com/CluvexStudio/Aether/blob/main/TRADEMARK.md)
forbids using the "Aether" name or a confusingly similar name for forks and
derivative clients without written permission. This app is therefore named
**RIX**, not "Aether" or "AETHER RIX". If you have written permission from
CluvexStudio, the display name is a one-line change in
`app/src/main/res/values/strings.xml`.

## Status

Compiles and packages; the native libraries are built from pinned upstream
sources in CI. Runtime behaviour on a real handset has not been exercised here —
this host has no KVM and no device, so the VPN handshake, DNS inside the tunnel
and per-app split tunnelling still need a run on hardware.