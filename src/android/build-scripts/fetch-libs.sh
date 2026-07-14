#!/usr/bin/env bash
set -euo pipefail

# fetch-libs.sh — Fetch the native library required for the APK.
#
# Downloads the prebuilt tun2socks.aar from the pinned GitHub Release
# instead of building it from source. Skips the download if the .aar is
# already present at the current version.
#
# Called automatically by build.sh. Can also be run standalone:
#   ./build-scripts/fetch-libs.sh
#
# Environment variables (all optional):
#   TUN2SOCKS_VERSION  – override version/tag (default: contents of tun2socks.version)

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

# Skip download if already fetched at this exact version
if [[ -f "$AAR" && "$(cat "$STAMP" 2>/dev/null)" == "$TUN2SOCKS_VERSION" ]]; then
    echo "[fetch-libs] tun2socks ${TUN2SOCKS_VERSION} already present — skipping."
    exit 0
fi

URL="https://github.com/Stanislav-Povolotsky/tun2socks/releases/download/${TUN2SOCKS_VERSION}/tun2socks-android.aar"
echo "[fetch-libs] Downloading tun2socks AAR ${TUN2SOCKS_VERSION}..."
echo "[fetch-libs]   $URL"

mkdir -p "$LIBS_DIR"
rm -f "$STAMP"
curl -fsSL -o "$AAR" "$URL"

echo "$TUN2SOCKS_VERSION" > "$STAMP"
echo "[fetch-libs] Done: ${AAR} ($(du -sh "$AAR" | cut -f1))"
