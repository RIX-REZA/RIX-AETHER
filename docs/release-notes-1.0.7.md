# RIX-AETHER 1.0.7 — Multi-Platform Release

RIX-AETHER is a censorship-resilience client built around the Aether tunnel core.
This release publishes the same 1.0.7 application for Android and Windows, with
architecture-specific Android packages and separate portable and desktop
editions for Windows.

All artifacts below were produced from the repository at the `v1.0.7` source
tree. SHA-256 checksums are published both in `SHA256SUMS.txt` and as a
`<filename>.sha256` sidecar next to every file.

---

## Android

| Package | Architecture | Minimum Android |
|---|---|---|
| `RIX-AETHER-Android-ARMv7.apk` | 32-bit ARM (`armeabi-v7a`) | Android 7.0 (API 24) |
| `RIX-AETHER-Android-ARM64.apk` | 64-bit ARM (`arm64-v8a`) | Android 7.0 (API 24) |
| `RIX-AETHER-Android-Universal.apk` | `arm64-v8a` + `armeabi-v7a` | Android 7.0 (API 24) |

**Install**

1. Download the package that matches the device. If unsure, use the Universal
   APK.
2. Allow installation from the app you used to download it when Android asks.
3. Open the APK and install. Upgrades install over an existing RIX installation
   signed with the project key; settings are preserved.

The APKs are signed with the project's release key. The certificate SHA-256 is
`DC:35:1A:F6:E1:FE:07:C1:C8:6C:D6:F4:E7:63:82:51:1C:FF:26:8C:3F:2E:68:E5:8D:96:2C:07:BC:EA:03:79`.
That key was disclosed in the project's public history; see `SECURITY.md`.
Install only from this release page and compare the SHA-256 before installing if
you obtained the file anywhere else.

Each APK contains the Aether core (`libaether.so`), the tun2socks engine
(`libhev-socks5-tunnel.so`), the JNI bridge (`librixjni.so`) and the Psiphon
Android library for the ABIs listed above. The Universal package contains both
ARM ABIs; it is not an APK that silently omits an architecture.

---

## Windows

| Package | Edition | Minimum Windows | Architecture |
|---|---|---|---|
| `RIX-AETHER-Windows-10-Portable.zip` | Portable | Windows 10 1809 (build 17763) | x64 |
| `RIX-AETHER-Windows-11-Portable.zip` | Portable | Windows 11 (build 22000) | x64 |
| `RIX-AETHER-Windows-10-Desktop-Setup.exe` | Desktop installer | Windows 10 1809 (build 17763) | x64 |
| `RIX-AETHER-Windows-11-Desktop-Setup.exe` | Desktop installer | Windows 11 (build 22000) | x64 |

### Portable edition

Extract the ZIP to any folder you can write to and run `RIX.exe`. Nothing is
installed and there is no uninstaller. The included `portable.marker` makes RIX
store settings, logs, the WebView profile and the Psiphon identity in the
`data\` folder beside the executable, so the whole folder can be moved or
deleted as one unit. The ZIP also carries Microsoft's WebView2 bootstrapper for
machines that do not already have the runtime.

### Desktop edition

Run the setup and follow the wizard. The installer is per-user, needs no
administrator prompt, creates Start Menu shortcuts, offers an optional desktop
shortcut and start-with-Windows task, and registers a normal uninstaller in
Apps & Features. Installing a newer build over an older one upgrades in place;
settings in `%APPDATA%\RIX` are deliberately preserved, including across
uninstall/reinstall.

The Windows 10 and Windows 11 installers contain the same application. Their
minimum-OS declarations differ so the Windows 11 package refuses to install on
an older system. The 64-bit x64 build is the only Windows build: the upstream
Aether Windows core used by this version is x86_64 only, so a Windows ARM64
package is not published rather than shipping one that cannot run.

RIX.exe is self-contained (.NET 8 runtime included). WebView2 is the only
external runtime dependency; it is present by default on Windows 11 and updated
Windows 10, and the installer/portable bundle carries the bootstrapper for the
remaining machines.

The Windows binaries are **not Authenticode-signed** in this release because no
code-signing certificate is configured for the project. Windows SmartScreen may
warn. Verify the published SHA-256 before running. No Defender, Smart App
Control, WDAC or other Windows security feature is disabled or bypassed.

---

## What is in 1.0.7

- The complete Android application, built from the existing Kotlin/Compose
  source with the Aether core and hev-socks5-tunnel native engines.
- The complete Windows application, built from the existing .NET 8 WinForms +
  WebView2 source, with the pinned and hash-verified Aether Windows core.
- Portable mode for Windows, activated by `portable.marker`, keeping all user
  data beside the executable.
- Separate Windows 10 and Windows 11 desktop installers, sharing one AppId so
  upgrades work, with OS-appropriate minimum versions.
- Architecture-specific Android APKs plus a real two-ABI Universal APK.
- SHA-256 checksums for every artifact.

## Verification

- Android APK ABIs were inspected after packaging; each package contains only
  the native libraries for its declared architectures and all three required
  libraries are present.
- APK signatures were verified with `apksigner`.
- The Windows published executable was run with `--selfcheck` on a Windows x64
  runner; the embedded UI resources were confirmed present and readable.
- Both portable ZIPs were extracted and the extracted `RIX.exe --selfcheck`
  passed.
- PE headers of `RIX.exe` and `core\aether.exe` were checked as x64.
- The Aether Windows core archive was checked against its pinned SHA-256 before
  packaging.

**Compatibility limitation:** packaging and static compatibility were verified
on the build runners (Linux for Android; Windows Server 2022 x64 for Windows).
The installers and portable builds were not run on physical Windows 10 or
Windows 11 hardware in this release pipeline. The declared minimum versions are
enforced by the installers and match the application's documented requirements.

## Source and security

- Repository: https://github.com/RIX-REZA/RIX-AETHER
- Security policy and signing-key disclosure: `SECURITY.md`
- License: `LICENSE`; third-party notices: `NOTICE` and the core's `LICENSES`
  folder inside the Windows packages.

No API keys, tokens or private credentials are included in any artifact.
