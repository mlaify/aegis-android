#!/usr/bin/env bash
# Build aegis-ffi for Android targets and stage the .so libs + Kotlin
# bindings under app/src/main/jniLibs/ and app/src/main/java/.
#
# Expected layout:
#   parent/
#   ├── aegis-core/
#   ├── aegis-ffi/
#   ├── aegis-apple/
#   └── aegis-android/   ← invoked from here
#
# This script is a placeholder until Phase 4b lands. Flip from `echo`s
# to real commands when the Android Studio project is ready to consume
# the libraries.

set -euo pipefail

cd "$(dirname "$0")/.."
FFI_DIR="../aegis-ffi"

if [ ! -d "$FFI_DIR" ]; then
  echo "error: $FFI_DIR does not exist — clone mlaify/aegis-ffi as a sibling of this repo" >&2
  exit 1
fi

echo "[placeholder] Add Rust Android targets (one-time)"
echo "  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android"
echo
echo "[placeholder] Build aegis-ffi for Android ABIs"
echo "  cargo build --manifest-path $FFI_DIR/Cargo.toml --release --target aarch64-linux-android"
echo "  cargo build --manifest-path $FFI_DIR/Cargo.toml --release --target armv7-linux-androideabi"
echo "  cargo build --manifest-path $FFI_DIR/Cargo.toml --release --target x86_64-linux-android"
echo
echo "[placeholder] Stage .so libraries under jniLibs/"
echo "  cp …/aarch64-linux-android/release/libaegis_ffi.so app/src/main/jniLibs/arm64-v8a/"
echo "  cp …/armv7-linux-androideabi/release/libaegis_ffi.so app/src/main/jniLibs/armeabi-v7a/"
echo "  cp …/x86_64-linux-android/release/libaegis_ffi.so app/src/main/jniLibs/x86_64/"
echo
echo "[placeholder] Generate Kotlin bindings via uniffi-bindgen"
echo "  uniffi-bindgen generate \\"
echo "      --library $FFI_DIR/target/release/libaegis_ffi.so \\"
echo "      --language kotlin \\"
echo "      --out-dir app/src/main/java"
