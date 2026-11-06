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

A build attestation is the one check that survives all of this. Official builds
now carry a GitHub build-provenance attestation, which binds the artifact's
digest to this repository and to the workflow that produced it, and which a fork
cannot obtain for its own binary. The README documents the command. The signing
certificate fingerprint is still printed on every build and is still in the
release notes, but read it as "signed by our key, or by whoever else has it" --
which, until the rotation above happens, is everyone.

### What the repository does now

1. The key is **not** in the working tree and is ignored by `.gitignore`
   (`keystore/`, `*.p12`, `*.jks`, `*.keystore`).
2. CI restores it at build time from the **secret** `RIX_KEYSTORE_B64`, and
   falls back to a repository *variable* of the same name only if the secret is
   absent. With neither set the build **fails**; it never falls back to the
   debug keystore, because an APK signed with a different key installs over
   nothing and breaks every future update.

   The fallback is a known, accepted exposure, and it is the one thing in this
   file that is a trade rather than a fix. The runner prints every step's
   `env:` block, and it masks a value only when that value is a registered
   secret -- so a run that takes the variable path writes the base64 of
   `rix.p12`, private key included, into its own job log. Run `41331524238` did
   exactly that. The repository is public, so that run and the log on its page
   are readable by anyone; the REST endpoint that downloads logs answers `403`
   without a token, which narrows the API route but not the page.

   It is tolerated because the key is already public: it was committed to this
   repository once, and the paragraph above is the admission. Printing it again
   does not disclose anything that was not already disclosed, and CI still has
   to sign with it for installed apps to keep updating. Removing the fallback
   only stopped the build. Every run that takes it emits a `::warning::` saying
   so, because the path should disappear with the rotation below.

   The variable itself is *not* anonymously readable: `GET
   /repos/.../actions/variables/RIX_KEYSTORE_B64` answers `401` without a token.
   The exposure is the log, not the storage.
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
5. Never commit either key, and store the new one only as the *secret*
   `RIX_KEYSTORE_B64` -- never as a variable, and never with the password
   written into `app/build.gradle.kts` as it is today (`storePassword` falls back
   to a literal when `RIX_KEYSTORE_PASS` is unset, and that literal is in this
   public repository beside a key whose ciphertext has now been published twice).

Until that release happens, CI keeps signing with the disclosed key so that
installed apps can still update in place. That is a deliberate compatibility
choice, not a claim that the key is safe.

### The run logs that carry it

A workflow run's log can be deleted from the run's page ("Delete run"), and a
log whose step printed the key should be treated that way. Deleting it is not a
remedy: the value was already served, and anything can have copied it. It only
stops the copy that is easiest to find. A log left in place keeps handing the
key to the next person who greps for it.

## Reporting a problem

Open an issue for anything that is not itself a vulnerability. For a
vulnerability, use GitHub's private vulnerability reporting on this repository
(Security -> Report a vulnerability) rather than a public issue.

## Other keys

- GitHub tokens used for development are not part of this repository and must
  never be written into git config or tracked files.
- The Windows release has an optional Authenticode signing stage
  (`windows/scripts/sign-release.ps1`), driven entirely from repository secrets
  and the repository variable `RIX_SIGNING_ENABLED`. It only ever signs with a
  real certificate — a store thumbprint, a PFX supplied as a base64 secret, or
  Azure Artifact Signing — and it never fakes a signature or claims a self-signed
  build is trusted. With no certificate configured the build stays a normal
  unsigned dev build. The private key is never a build artifact and never lives in
  the repository. The launcher verifies the signed core's SHA-256 against an
  `aether.sha256` sidecar shipped beside it, and reports unsigned / corrupted /
  policy-blocked starts precisely instead of as a generic failure. No Windows
  security feature (WDAC, Smart App Control, Defender, Code Integrity) is
  disabled or bypassed by this stage.