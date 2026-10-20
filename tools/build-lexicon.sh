#!/usr/bin/env bash
# Cross-compile engine/fs_lexicon for the app ABIs and copy libfs_lexicon.so
# into app/src/main/jniLibs. The .so stays out of git.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk/27.2.12479018" ]]; then
  NDK="$ANDROID_HOME/ndk/27.2.12479018"
fi
if [[ -z "$NDK" && -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk/27.2.12479018" ]]; then
  NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME or install ndk;27.2.12479018." >&2
  exit 1
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST="linux-x86_64" ;;
  Darwin-arm64) HOST="darwin-arm64" ;;
  Darwin-x86_64) HOST="darwin-x86_64" ;;
  *)
    echo "Unsupported NDK host $(uname -s)-$(uname -m)" >&2
    exit 1
    ;;
esac

CLANG_BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
if [[ ! -x "$CLANG_BIN/aarch64-linux-android26-clang" ]]; then
  echo "NDK clang is missing under $CLANG_BIN" >&2
  exit 1
fi

rustup target add aarch64-linux-android x86_64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CLANG_BIN/x86_64-linux-android26-clang"

cd "$ROOT/engine/fs_lexicon"
cargo build --release --target aarch64-linux-android
cargo build --release --target x86_64-linux-android

mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/jniLibs/x86_64"
cp "$ROOT/engine/fs_lexicon/target/aarch64-linux-android/release/libfs_lexicon.so" \
  "$ROOT/app/src/main/jniLibs/arm64-v8a/libfs_lexicon.so"
cp "$ROOT/engine/fs_lexicon/target/x86_64-linux-android/release/libfs_lexicon.so" \
  "$ROOT/app/src/main/jniLibs/x86_64/libfs_lexicon.so"
