# Security

## Signing key: disclosed, and what happens next

The Android signing key `keystore/rix.p12` (alias `rix`, password
`rix-signing-2026`) was committed to this public repository. It is in the git
history, and its password was written in the build files and in the release
notes of previous versions. Treat that key as **compromised**.

What that does and does not mean:

- Anyone can produce an APK signed with the same certificate
  (`SHA-256 DC:35:1A:F6:E1:FE:07:C1:C8:6C:D6:F4:E7:63:82:51:1C:FF:26:8C:3F:2E:68:E5:8D:96:2C:07:BC:EA:03:79`).
  A phone that has "install unknown apps" enabled and is persuaded to install
  such a file cannot tell it apart from an official build.
- It does **not** give access to a Google Play listing, to the repository, to
  the update channel, or to any user data. Play App Signing, if it is ever
  used, keeps its own key.
- Checksums published with a release only help if the user compares them; they
  are not a substitute for a key that was never published.

### What the repository does now

1. The key is **not** in the working tree and is ignored by `.gitignore`
   (`keystore/`, `*.p12`, `*.jks`, `*.keystore`).
2. CI restores it at build time from the repository variable (or secret)
   `RIX_KEYSTORE_B64`. With no value there, the build **fails**; it never falls
   back to the debug keystore, because an APK signed with a different key
   installs over nothing and breaks every future update.
3. A CI step refuses to build if any signing material is tracked in git.
4. Every build prints the APK's signing certificate with
   `apksigner verify --print-certs`, so a change of key is visible in the log.

Removing the file from the tip of the branch does not remove it from history.
History rewriting would break every clone and fork and cannot recall copies
that already exist, so it is not presented here as a fix.

### Rotation, without silently breaking updates

A new key cannot sign an update for an installation that trusts the old one.
Android offers APK Signature Scheme v3 **proof-of-rotation**, which lets a new
key be signed as the successor of the old one -- but the platform only checks
that lineage from **Android 9 (API 28)** onwards. On API 24–27 the update is
rejected, and the user must uninstall and reinstall.

Because of that, rotation is a release decision, not a build detail. The plan
that keeps existing users working:

1. Generate the new key offline (for example
   `keytool -genkeypair -keystore rix-v2.p12 -alias rix -keyalg RSA -keysize 4096 -validity 10000`),
   with a new password that is not written into the repository.
2. Keep the old key available **only** for one transition release, and sign
   that release with a v3 rotation lineage that names the new key as the
   successor. `apksigner` does this with
   `--lineage <lineage.bin> --rotation-min-sdk-version 28`. The lineage file is
   generated once with `apksigner rotate` and must be backed up with the new
   key.
3. Document in the release notes that users on Android 7.0–8.1 must reinstall
   once (and that exporting settings first avoids losing them).
4. After that release, sign only with the new key. The old key and its lineage
   are retired; the old key must never be used for a new distribution channel.
5. Never commit either key. Store the new one in `RIX_KEYSTORE_B64` (and, when
   GitHub Actions secrets are available, prefer the secret over the variable:
   variables are readable by anyone with repository write access).

Until that release happens, CI keeps signing with the disclosed key so that
installed apps can still update in place. That is a deliberate compatibility
choice, not a claim that the key is safe.

## Reporting a problem

Open an issue for anything that is not itself a vulnerability. For a
vulnerability, use GitHub's private vulnerability reporting on this repository
(Security -> Report a vulnerability) rather than a public issue.

## Other keys

- GitHub tokens used for development are not part of this repository and must
  never be written into git config or tracked files.
- The Windows installer is currently unsigned. If Authenticode signing is added,
  its certificate must be stored outside the repository in the same way, and
  the private key must not be a build artifact.