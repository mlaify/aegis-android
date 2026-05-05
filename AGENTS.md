# AGENTS.md

## Project purpose
Native Android client for the Aegis secure messaging platform. Kotlin
+ Jetpack Compose; backed by the Rust post-quantum core via the
`mlaify/aegis-ffi` UniFFI bridge.

## Rules
- Preserve protocol compatibility with `aegis-spec`.
- Prefer small, reviewable commits.
- Do not invent new wire fields without updating the spec.
- Do not duplicate crypto logic in Kotlin — call into the FFI bridge.
- Reach for Compose first; AndroidX views only where Compose cannot
  express the desired behavior.
- Use `AndroidX Keystore` for any device-bound key wrapping. Do not
  reimplement key storage or AEAD wrapping in Kotlin.
- For UX changes, smoke-test on emulator and ideally a physical device
  before reporting the task as complete.
- When unsure, propose the smallest viable change.

## Build/test expectations
- Run `./gradlew assembleDebug` for affected modules.
- Run `./gradlew test` and `./gradlew connectedAndroidTest` (the latter
  on emulator) for changed code.
- Run ktlint / detekt before finalizing changes.

## Cross-repo dependencies
- This repo consumes `mlaify/aegis-ffi`, which path-deps to
  `mlaify/aegis-core`. The expected workspace layout has all four
  client repos side-by-side under one parent dir; CI mirrors this via
  sibling `git clone` of `aegis-ffi` and `aegis-core`.
