#!/usr/bin/env bash
set -euo pipefail

# ProxSox Build Script
#
# All parameters are passed via environment variables (all optional):
#
# Build control:
#   PROXSOX_BUILD_TYPE          – "release" | "debug" | "both"  (default: both)
#
# Version overrides:
#   PROXSOX_VERSION_NAME        – e.g. "0.1.42"
#   PROXSOX_VERSION_CODE        – e.g. "42"
#
# Signing (all four must be set to enable signing):
#   PROXSOX_KEY_STORE           – path to .jks file (relative to android project root or absolute)
#   PROXSOX_KEY_ALIAS           – key alias
#   PROXSOX_KEY_STORE_PASSWORD  – keystore password
#   PROXSOX_KEY_PASSWORD        – key password

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
OUTPUT_DIR="$SCRIPT_DIR/../../../build-output"

BUILD_TYPE="${PROXSOX_BUILD_TYPE:-both}"

# Ensure gradlew is executable
chmod +x "$PROJECT_DIR/gradlew" || echo "Warning: failed to chmod +x gradlew"

# Use Java 17 if available (required for Gradle 7.x)
if /usr/libexec/java_home -v 17 &>/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
elif [[ -z "${JAVA_HOME:-}" ]]; then
    echo "Warning: JAVA_HOME not set. Gradle 7.x requires Java 11-17."
fi

cd "$PROJECT_DIR"

# ── Extra Gradle args ─────────────────────────────────────────────────────────
GRADLE_ARGS=()

# Signing – all four vars must be non-empty to activate
if [[ -n "${PROXSOX_KEY_STORE:-}" && -n "${PROXSOX_KEY_ALIAS:-}" && \
      -n "${PROXSOX_KEY_STORE_PASSWORD:-}" && -n "${PROXSOX_KEY_PASSWORD:-}" ]]; then
    GRADLE_ARGS+=(
        "-PproductKeyStore=${PROXSOX_KEY_STORE}"
        "-PproductKeyAlias=${PROXSOX_KEY_ALIAS}"
        "-PproductKeyStorePassword=${PROXSOX_KEY_STORE_PASSWORD}"
        "-PproductKeyAliasPassword=${PROXSOX_KEY_PASSWORD}"
    )
    SIGNING_INFO="${PROXSOX_KEY_STORE}"
else
    SIGNING_INFO="unsigned"
fi

# Version overrides
[[ -n "${PROXSOX_VERSION_NAME:-}" ]] && GRADLE_ARGS+=("-PVERSION_NAME=${PROXSOX_VERSION_NAME}")
[[ -n "${PROXSOX_VERSION_CODE:-}" ]] && GRADLE_ARGS+=("-PVERSION_CODE=${PROXSOX_VERSION_CODE}")

# ── Tasks ─────────────────────────────────────────────────────────────────────
case "$BUILD_TYPE" in
    release) TASKS=(assembleRelease) ;;
    debug)   TASKS=(assembleDebug) ;;
    both)    TASKS=(assembleDebug assembleRelease) ;;
    *)       echo "Unknown PROXSOX_BUILD_TYPE: $BUILD_TYPE"; exit 1 ;;
esac

# ── Native libs ──────────────────────────────────────────────────────────────
"$SCRIPT_DIR/fetch-libs.sh"

# ── Build ─────────────────────────────────────────────────────────────────────
echo "=== ProxSox Build ==="
echo "Project: $PROJECT_DIR"
echo "Java:    ${JAVA_HOME:-system default}"
echo "Type:    $BUILD_TYPE"
echo "Signing: $SIGNING_INFO"
echo ""

echo "[1/3] Cleaning..."
./gradlew clean --quiet

echo "[2/3] Building ${BUILD_TYPE} APK(s)..."
./gradlew "${TASKS[@]}" "${GRADLE_ARGS[@]}" --quiet

echo "[3/3] Collecting APKs..."
mkdir -p "$OUTPUT_DIR"
case "$BUILD_TYPE" in
    debug)   cp app/build/outputs/apk/debug/*.apk   "$OUTPUT_DIR/" 2>/dev/null || true ;;
    release) cp app/build/outputs/apk/release/*.apk "$OUTPUT_DIR/" 2>/dev/null || true ;;
    both)
        cp app/build/outputs/apk/debug/*.apk   "$OUTPUT_DIR/" 2>/dev/null || true
        cp app/build/outputs/apk/release/*.apk "$OUTPUT_DIR/" 2>/dev/null || true
        ;;
esac

echo ""
echo "=== Build Complete ==="
echo "Output: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"/*.apk 2>/dev/null || echo "  No APKs found"
