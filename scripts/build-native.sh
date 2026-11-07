#!/bin/bash
# Build Gradium's libptts_ffi.so for arm64 Android phones, twice:
#   libptts_ffi.so        with ARMv8.2 dotprod + fp16 (fast q8_0 kernels; most phones since 2019)
#   libptts_ffi_compat.so without them (any 64-bit ARM phone, slower)
# The app picks one at run time from /proc/cpuinfo.
#
# Usage: scripts/build-native.sh <xn-ptts checkout> <output jniLibs dir>
# Needs cargo-ndk and ANDROID_NDK_HOME. Mirrors xn-ptts/android/build.sh.
set -euo pipefail
UPSTREAM="$(cd "$1" && pwd)"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
cd "$UPSTREAM"

rustup target add aarch64-linux-android >/dev/null
# Either of these would override the per-target flags below.
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
COMMON="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384 -C target-cpu=generic"

build() {
    local features="$1" name="$2" tmp
    tmp="$(mktemp -d)"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$COMMON${features:+ -C target-feature=$features}"
    # Separate target dirs, so one variant's cached objects never end up in the other.
    CARGO_TARGET_DIR="target/android-${name}" cargo ndk -t arm64-v8a -o "$tmp" build --release -p ptts-ffi
    mkdir -p "$OUT/arm64-v8a"
    cp "$tmp/arm64-v8a/libptts_ffi.so" "$OUT/arm64-v8a/lib${name}.so"
    rm -rf "$tmp"
}

build "+dotprod,+fp16" ptts_ffi
build "" ptts_ffi_compat
ls -la "$OUT/arm64-v8a"
