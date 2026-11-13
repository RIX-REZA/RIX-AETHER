# RIX-AETHER staging

This is the private validation repository for RIX-AETHER. Untested updates are
built here first; nothing in this repository is published directly.

## Flow

1. Push or open a pull request against `main` in this repository.
2. Run **RIX-AETHER Multi-Platform Release** with `publish=false`.
   It performs the same Android compile, unit tests, native builds, signed APK
   checks, Windows build, installer builds and portable selfchecks as the public
   release pipeline, but uploads nothing to a release.
3. Only when that run is green, run **Promote to Public Repository**.
   Promotion refuses a commit that has no successful multi-platform run in this
   repository. It then:
   - pushes that exact commit to a disposable `staging/promote-<sha>` branch in
     `RIX-REZA/RIX-AETHER` (the public default branch is not touched);
   - dispatches the public `multi-platform-release.yml` on that branch.
4. The public repository rebuilds and verifies everything from the promoted
   commit. Set `publish=true` on the promotion dispatch only when the change is
   meant to replace the public release assets.

A promotion is a reference to tested code, not a bypass: the public pipeline
still rebuilds, signs, verifies and only then updates the release.

## Required secrets

- `RIX_KEYSTORE_B64`: the base64 PKCS#12 project signing key, used by the
  Android APK build. Same value as the public repository.
- `MAIN_REPO_TOKEN`: a token with permission to push a branch to
  `RIX-REZA/RIX-AETHER`, dispatch workflows there, and write release assets.
  Store it only as an Actions secret; never commit it.

## Notes

- Tags and releases are never created here.
- The public release job still updates only the tag passed in `release_tag`,
  removes only that release's stale assets, and leaves every other tag and
  release untouched.
- The disposable promotion branch can be deleted after the public run starts.
