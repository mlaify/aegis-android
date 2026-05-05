# aegis-android

Aegis client for Android. Kotlin + Jetpack Compose, backed by the Rust
post-quantum core via [`mlaify/aegis-ffi`](https://github.com/mlaify/aegis-ffi).

The same Rust crate that powers the relay, gateway, and CLI also drives
this client — no second implementation of ML-KEM-768 / ML-DSA-65 /
hybrid signing.

## Layout

```
aegis-android/
├── README.md
├── AGENTS.md
├── LICENSE
├── app/                    Android Studio project (Kotlin + Jetpack Compose)
│   └── (Gradle layout — coming soon)
└── scripts/
    └── build-jni.sh        (planned — wraps aegis-ffi into JNI .so libraries)
```

## Status

**Phase 4a (the Rust FFI bridge) is shipped** — see
[`mlaify/aegis-ffi`](https://github.com/mlaify/aegis-ffi).

This repo is freshly initialized. Phase 4b's remaining work, in the
order I'd tackle it:

1. Build the FFI for Android targets (`aarch64-linux-android`,
   `armv7-linux-androideabi`, `x86_64-linux-android`) and bundle the
   `.so` libraries plus generated Kotlin bindings into an AAR.
2. Bring up the `app/` Android Studio project — Jetpack Compose UI,
   AndroidX Keystore for passphrase memoization, system notifications,
   Compose Material 3.
3. Implement the same screens the web client has (Inbox, Compose,
   Identity, Setup) using Compose components.
4. Push notifications via FCM, foreground service for inbox refresh,
   share intent registration so other apps can hand off to Aegis.

## Building (planned)

```bash
# Build the FFI libraries from a sibling clone of mlaify/aegis-ffi
./scripts/build-jni.sh

# Open the Android Studio project
open -a "Android Studio" app
```

## Sibling repo dependencies

`aegis-android` consumes the FFI bridge from `mlaify/aegis-ffi`, which
in turn path-deps to `mlaify/aegis-core`. The expected workspace
layout is all four side-by-side:

```
parent/
├── aegis-core/
├── aegis-ffi/
├── aegis-apple/
└── aegis-android/    ← you are here
```

## License

MIT — see [LICENSE](LICENSE).
