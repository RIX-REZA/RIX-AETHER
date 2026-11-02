# Name and branding policy

This file is about **names**, not about permission. The code is free software:
the AGPL grants everyone the right to run, study, change and redistribute it,
and nothing here takes that back. What the AGPL does **not** grant is the right
to use this project's name or branding, and that is the whole subject of this
document.

Nothing here applies to the upstream project. "Aether", its logo and confusingly
similar names belong to CluvexStudio and the Aether project; their own
`TRADEMARK.md` governs them, and it binds this project exactly as it binds a fork
of it.

## Covered marks

- The name **RIX** and the repository name **RIX-AETHER**, as the name of a
  program or of a distribution of one.
- The RIX icon and banner (`docs/banner.svg`, the launcher icons under
  `app/src/main/res/mipmap-*`, and the Windows icon).
- The Android application id `com.rix.vpn`.
- The release asset names `RIX-*` (`RIX-1.0.7-debug.apk`, `RIX-Setup-*.exe`,
  `RIX-Portable.zip`, `RIX-<version>.zip`).

No registration is claimed. The rights asserted here come from use of the name
for this project, which is what an unregistered mark consists of. If the mark is
ever registered, this file will say so and the conditions below do not change.

## If you fork, redistribute or rebuild this project

The AGPL lets you do all three. Do them under **your own name**, not this one:

1. Change the display name (`app_name` in the Android strings, and the window
   title in the Windows client) so the program is not called RIX.
2. Change the application id (`applicationId`) and the Windows executable name,
   so a fork's build can neither install over an official one nor be mistaken for
   an update to it.
3. Replace the icons and the banner. Do not ship this project's artwork as your
   own product's artwork.
4. Do not use "RIX" or "RIX-AETHER" in your product's name, package id or
   release file names, and do not describe your builds as official, as a
   continuation of this project, or as endorsed by it.
5. Say plainly, in the app and in your release notes, that your build is an
   unofficial fork and who made it. A line such as "an unofficial fork of
   RIX-AETHER" is enough, and it is allowed.
6. Keep the licence and attribution notices intact: `LICENSE`, `NOTICE` and the
   upstream Aether notice it carries, including in a build you modified. AGPL
   section 5 requires this, and section 7(b) lets this project require it
   explicitly.
7. Do not sign with, or claim, this project's signing identity, and do not
   publish anything as an update to an installed RIX.
8. Do not relicense the combined work under terms more restrictive than
   AGPL-3.0-only. The upstream core is AGPL, so a stricter licence on the whole
   work is not this project's to grant, and any fork's either.

You may say that your work is *based on* or *derived from* RIX-AETHER, and you
may link here. That is a description, not a name.

## How this is enforced

There is no mechanism here that technically prevents a fork -- a public
repository and a permissive licence both mean the opposite, on purpose. What
follows is what this project can actually do, and does:

- The release page is the only distribution channel this project controls. An
  app downloaded from somewhere else is not an official build, whatever it is
  signed with.
- Official builds carry a GitHub build-provenance attestation issued to *this*
  repository. A fork can attest its own binaries in its own repository -- that
  is the normal use of the feature -- but it cannot obtain one that names this
  repository, which is what the README tells a user to check.
- Trademark rights, where they exist, are enforced by asking, and by takedown
  where a store or host requires it -- not by code.

## Reporting impersonation

If you find a build, a listing or a repository passing itself off as this
project, open an issue. If it involves credentials or a security impact rather
than a name, use GitHub's private vulnerability reporting on this repository as
described in [SECURITY.md](SECURITY.md).
