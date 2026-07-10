#!/usr/bin/env bash
set -euo pipefail

# fetch-libs.sh — Build native libraries required for the APK.
#
# Builds tun2socks.aar via gomobile bind from source at the pinned version.
# Skips the build if the .aar is already present at the current version.
#
# Called automatically by build.sh. Can also be run standalone:
#   ./build-scripts/fetch-libs.sh
#
# Requirements:
#   go, gomobile (golang.org/x/mobile/cmd/gomobile), gobind, Android NDK
#
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   go install golang.org/x/mobile/cmd/gobind@latest
#   gomobile init
#
# Environment variables (all optional):
#   TUN2SOCKS_VERSION  – override version (default: contents of tun2socks.version)
#   ANDROID_NDK_HOME   – path to NDK installation (required by gomobile)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
LIBS_DIR="$PROJECT_DIR/app/libs"
VERSION_FILE="$PROJECT_DIR/tun2socks.version"

# ── tun2socks AAR ─────────────────────────────────────────────────────────────

if [[ -z "${TUN2SOCKS_VERSION:-}" ]]; then
    if [[ ! -f "$VERSION_FILE" ]]; then
        echo "ERROR: $VERSION_FILE not found and TUN2SOCKS_VERSION not set." >&2
        exit 1
    fi
    TUN2SOCKS_VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
fi

AAR="$LIBS_DIR/tun2socks.aar"
STAMP="$LIBS_DIR/.tun2socks.version"

# Skip rebuild if already built at this exact version
if [[ -f "$AAR" && "$(cat "$STAMP" 2>/dev/null)" == "$TUN2SOCKS_VERSION" ]]; then
    echo "[fetch-libs] tun2socks ${TUN2SOCKS_VERSION} already built — skipping."
    exit 0
fi

echo "[fetch-libs] Building tun2socks AAR ${TUN2SOCKS_VERSION}..."

# Check prerequisites
for cmd in go gomobile; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: '$cmd' not found." >&2
        echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
        echo "  go install golang.org/x/mobile/cmd/gobind@latest" >&2
        echo "  gomobile init" >&2
        exit 1
    fi
done

#TMP=$(mktemp -d)
TMP=/tmp/deps
trap 'rm -rf "$TMP"' EXIT

echo "[fetch-libs] Cloning xjasonlyu/tun2socks ${TUN2SOCKS_VERSION}..."
git clone --depth 1 \
    --branch "$TUN2SOCKS_VERSION" \
    https://github.com/Stanislav-Povolotsky/tun2socks.git \
    "$TMP/tun2socks"
echo "[fetch-libs] Cloned to $TMP/tun2socks"

# Read the x/mobile version that tun2socks was built with from its go.sum,
# then install matching gomobile/gobind so there's no tool-directive mismatch.
pushd "$TMP/tun2socks"
if ! go list -m golang.org/x/mobile >/dev/null 2>&1; then
    go get golang.org/x/mobile
fi
XMOBILE_VERSION=$((grep '^golang.org/x/mobile ' "./go.mod" 2>/dev/null \
    | awk '{print $2}' | head -1) || echo "latest")
echo "[fetch-libs] Detected x/mobile version: ${XMOBILE_VERSION:-none}"
popd
if [[ -z "$XMOBILE_VERSION" ]]; then
    # Not a direct dep — find it in go.sum (pick the non-go.mod entry)
    XMOBILE_VERSION=$(grep '^golang.org/x/mobile v' "$TMP/tun2socks/go.sum" 2>/dev/null \
        | grep -v '/go.mod' | awk '{print $2}' | sort -V | tail -1)
fi
if [[ -z "$XMOBILE_VERSION" ]]; then
    echo "WARNING: could not detect x/mobile version, using latest" >&2
    XMOBILE_VERSION=latest
fi
echo "[fetch-libs] Installing gomobile@${XMOBILE_VERSION}..."
go install "golang.org/x/mobile/cmd/gomobile@${XMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${XMOBILE_VERSION}"
gomobile init

echo "[fetch-libs] Running gomobile bind..."
mkdir -p "$LIBS_DIR"

pushd "$TMP/tun2socks" > /dev/null
gomobile bind \
    -o "$AAR" \
    -target=android \
    -androidapi 23 \
    github.com/xjasonlyu/tun2socks/v2/engine
popd > /dev/null

echo "$TUN2SOCKS_VERSION" > "$STAMP"
echo "[fetch-libs] Done: ${AAR} ($(du -sh "$AAR" | cut -f1))"
