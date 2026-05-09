# Contributing to aegis-android

## Scope

`aegis-android` is the native Android client for Aegis — Kotlin + Jetpack Compose, backed by the `aegis-ffi` UniFFI bridge to the Rust PQ core.

It mirrors `aegis-apple` feature-for-feature; iOS leads, Android follows.

Protocol references:

- `../aegis-spec/docs/protocol-index.md`
- `../aegis-ffi` (UniFFI bindings produce `AegisFFI` Kotlin module)

## Development Workflow

```sh
./gradlew assembleDebug
./gradlew lint
./gradlew testDebugUnitTest
```

The UniFFI bindings are generated from `aegis-ffi` and committed to `app/src/main/java/.../uniffi/` (gitignored — see `aegis-ffi/scripts/`).

## CI Expectations

Lint + unit tests run on every PR via GitHub Actions.

## Design Conventions

- JSON-string FFI boundary (no complex nested UniFFI types)
- In-memory v0 identity material (Keystore-wrapped vault for v0.4+)
- WebAuthn PRF unlock via Credential Manager (RP ID `auth.mlaify.io`)

## Current Status

Round-3 part 1 shipped: passkey unlock, relay HTTP client, Inbox screen.
