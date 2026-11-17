# RIX-AETHER staging notes

This document describes the optional private validation flow. The public
repository is the source of releases; a staging copy is used to build and test
an update before the same commit is promoted.

## Flow

1. Push or open a pull request against `main` in the staging repository.
2. Run **RIX-AETHER Multi-Platform Release** with `publish=false`.
   It performs the same Android compile, unit tests, native builds, signed APK
   checks, Windows build, installer builds and portable selfchecks as the public
   release pipeline, but uploads nothing to a release.
3. Only when that run is green, promote the exact commit to the public
   repository. The public repository rebuilds and verifies everything from the
   promoted commit.
4. Run the public **RIX-AETHER Multi-Platform Release** with the new release tag
   and `publish=true` only when the artifacts are meant to replace the public
   assets.

A promotion is a reference to tested code, not a bypass: the public pipeline
still rebuilds, signs, verifies and only then uploads to the release.

## Release tags

- Public releases use annotated tags such as `v1.0.8`, created on the final
  commit and pushed one at a time. Never run `git push --tags`.
- The staging repository may keep its own preview releases under a separate
  `rix-<version>` tag. Those are not public releases.
- A release job updates only the tag passed in `release_tag`, removes only that
  release's stale assets, and leaves every other tag and release untouched.

## Required secrets

- `RIX_KEYSTORE_B64`: the base64 PKCS#12 project signing key, used by the
  Android APK build. Same value in both repositories.
- `MAIN_REPO_TOKEN` (staging only): a token with permission to push a branch to
  the public repository and dispatch workflows there. Store it only as an
  Actions secret; never commit it.
