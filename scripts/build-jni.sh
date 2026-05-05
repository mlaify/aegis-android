#!/usr/bin/env bash
# Cross-compile the sibling mlaify/aegis-ffi crate for Android, generate
# Kotlin bindings, and stage everything under app/src/main/{jniLibs,java}
# so Gradle picks them up on the next build.
#
# Expected workspace layout:
#   parent/
#   ├── aegis-core/
#   ├── aegis-ffi/
#   ├── aegis-apple/
#   └── aegis-android/    ← invoked from here
#
# Output paths:
#   app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libaegis_ffi.so
#       (gitignored — these are platform binaries, regenerated per build)
#   app/src/main/java/uniffi/aegis_ffi/aegis_ffi.kt
#       (committed — checked in for code-review visibility, mirroring
#        aegis-apple's policy of committing the Swift bindings)
#
# Pre-reqs (one-time):
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   cargo install cargo-ndk
#   Android NDK r27+ installed at $ANDROID_NDK_HOME
#       (e.g. ~/Library/Android/sdk/ndk/27.2.12479018 — set via Android Studio
#       or `sdkmanager 'ndk;27.2.12479018'`)

set -euo pipefail

cd "$(dirname "$0")/.."

FFI_DIR="../aegis-ffi"
if [ ! -d "$FFI_DIR" ]; then
  echo "error: $FFI_DIR does not exist — clone mlaify/aegis-ffi as a sibling of this repo" >&2
  exit 1
fi

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo not on PATH — install rustup (https://rustup.rs)" >&2
  exit 1
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "error: cargo-ndk not on PATH — \`cargo install cargo-ndk\`" >&2
  exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for candidate in "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"/*; do
    if [ -d "$candidate" ]; then
      export ANDROID_NDK_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "error: ANDROID_NDK_HOME is unset and no NDK was found under \$ANDROID_HOME/ndk" >&2
  echo "       install one via: sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi

OUT_JNI="app/src/main/jniLibs"
OUT_BINDINGS="app/src/main/java/uniffi/aegis_ffi"

echo "==> Building aegis-ffi for the host (used by JVM unit tests via JNA)"
(cd "$FFI_DIR" && cargo build --release)

echo "==> Building aegis-ffi for arm64-v8a / armeabi-v7a / x86_64 via cargo-ndk"
echo "    NDK: $ANDROID_NDK_HOME"
rm -rf "$OUT_JNI"
mkdir -p "$OUT_JNI"
(cd "$FFI_DIR" && \
  cargo ndk \
    --target arm64-v8a \
    --target armeabi-v7a \
    --target x86_64 \
    --platform 26 \
    -o "$(pwd)/../aegis-android/$OUT_JNI" \
    build --release)

echo "==> Generating Kotlin bindings"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
(cd "$FFI_DIR" && \
  cargo run --bin uniffi-bindgen -- generate \
    --library "$(pwd)/../aegis-android/$OUT_JNI/arm64-v8a/libaegis_ffi.so" \
    --language kotlin \
    --out-dir "$STAGE")

echo "==> Copying high-level Kotlin bindings"
mkdir -p "$OUT_BINDINGS"
cp "$STAGE/uniffi/aegis_ffi/aegis_ffi.kt" "$OUT_BINDINGS/aegis_ffi.kt"

echo
echo "✓ JNI .so libraries:"
find "$OUT_JNI" -name '*.so' | sed 's/^/  /'
echo "✓ Kotlin bindings:"
echo "  $OUT_BINDINGS/aegis_ffi.kt"
echo
echo "JNI libs are gitignored; the Kotlin bindings file IS committed (commit"
echo "the diff if the FFI surface changed)."
