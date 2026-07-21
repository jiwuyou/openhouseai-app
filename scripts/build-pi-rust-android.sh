#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NDK_ROOT="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/29.0.14206865}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android"
API_LEVEL="26"
CLANG="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang"
CLANG_RESOURCE_DIR="$($CLANG -print-resource-dir)"
TARGET_DIR="${PI_ANDROID_CARGO_TARGET_DIR:-$ROOT_DIR/rust/pi-android-bridge/target}"
SHARED_JNI_LIBRARY="$ROOT_DIR/operit-feature/src/main/jniLibs/arm64-v8a/libwuxianpi_rescue.so"

export ANDROID_NDK_HOME="$NDK_ROOT"
export ANDROID_NDK_ROOT="$NDK_ROOT"
export LIBCLANG_PATH="$TOOLCHAIN/lib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=$TARGET --sysroot=$TOOLCHAIN/sysroot -resource-dir=$CLANG_RESOURCE_DIR -isystem$CLANG_RESOURCE_DIR/include"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

cargo +nightly-2026-07-05 build \
    --manifest-path "$ROOT_DIR/rust/pi-android-bridge/Cargo.toml" \
    --target-dir "$TARGET_DIR" \
    --target "$TARGET" \
    --release

install -Dm755 \
    "$TARGET_DIR/$TARGET/release/libwuxianpi_rescue.so" \
    "$SHARED_JNI_LIBRARY"
