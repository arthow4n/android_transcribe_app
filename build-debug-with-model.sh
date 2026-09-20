#!/usr/bin/env bash
set -euo pipefail

# Script to build Debug APK WITH the embedded speech model (~470MB)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Auto-detect toolchain if not set in environment
TOOLCHAIN_BASE="${HOME}/.local/share/android-transcribe-toolchain"

if [[ -z "${JAVA_HOME:-}" && -d "${TOOLCHAIN_BASE}/jdk" ]]; then
    export JAVA_HOME="${TOOLCHAIN_BASE}/jdk"
fi

if [[ -z "${ANDROID_HOME:-}" && -d "${TOOLCHAIN_BASE}/android-sdk" ]]; then
    export ANDROID_HOME="${TOOLCHAIN_BASE}/android-sdk"
elif [[ -z "${ANDROID_HOME:-}" && -d "${HOME}/Android/Sdk" ]]; then
    export ANDROID_HOME="${HOME}/Android/Sdk"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_HOME:-}" ]]; then
    NDK_LATEST=$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)
    if [[ -n "${NDK_LATEST}" ]]; then
        export ANDROID_NDK_HOME="${NDK_LATEST}"
    fi
fi

if [[ -z "${RUSTUP_HOME:-}" && -d "${TOOLCHAIN_BASE}/rustup" ]]; then
    export RUSTUP_HOME="${TOOLCHAIN_BASE}/rustup"
fi

if [[ -z "${CARGO_HOME:-}" && -d "${TOOLCHAIN_BASE}/cargo" ]]; then
    export CARGO_HOME="${TOOLCHAIN_BASE}/cargo"
fi

# Ensure binaries are on PATH
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi
if [[ -n "${CARGO_HOME:-}" ]]; then
    export PATH="${CARGO_HOME}/bin:${PATH}"
fi
if [[ -n "${ANDROID_HOME:-}" ]]; then
    CMAKE_BIN=$(find "${ANDROID_HOME}/cmake" -name "cmake" -type f 2>/dev/null | head -n 1 || true)
    if [[ -n "${CMAKE_BIN}" ]]; then
        export PATH="$(dirname "${CMAKE_BIN}"):${PATH}"
    fi
    export PATH="${ANDROID_HOME}/platform-tools:${PATH}"
fi

echo "=================================================="
echo "Building Debug APK WITH Embedded Speech Model"
echo "=================================================="
echo "JAVA_HOME:        ${JAVA_HOME:-unset}"
echo "ANDROID_HOME:     ${ANDROID_HOME:-unset}"
echo "ANDROID_NDK_HOME: ${ANDROID_NDK_HOME:-unset}"
echo "=================================================="

./gradlew assembleDebug -PembedModel=true

OUTPUT_DIR="app/build/outputs/apk/debug"
SRC_APK="${OUTPUT_DIR}/app-debug.apk"
DEST_APK="${OUTPUT_DIR}/app-debug-with-model.apk"

if [[ -f "${SRC_APK}" ]]; then
    cp -f "${SRC_APK}" "${DEST_APK}"
    SIZE=$(du -h "${DEST_APK}" | cut -f1)
    echo ""
    echo "BUILD SUCCESSFUL!"
    echo "Generated APK: ${DEST_APK} (${SIZE})"
    echo "Default APK:   ${SRC_APK} (${SIZE})"
else
    echo "Error: Output APK not found at ${SRC_APK}" >&2
    exit 1
fi
