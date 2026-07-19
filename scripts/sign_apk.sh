#!/bin/bash
set -e

# =============================================================================
# Rezvan Mesh - APK Signing Script
# Signs the unsigned release APK using apksigner.
# Requires keystore credentials in environment variables.
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
UNSIGNED_APK="${1:-$PROJECT_ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk}"
SIGNED_APK="${UNSIGNED_APK%/*}/rezvan-mesh-release.apk"

# Check for required environment variables
if [ -z "$KEYSTORE_PATH" ]; then
    echo "ERROR: KEYSTORE_PATH environment variable not set"
    echo "Usage: KEYSTORE_PATH=/path/to/keystore.jks KEYSTORE_PASSWORD=xxx KEY_ALIAS=rezvan KEY_PASSWORD=xxx $0"
    exit 1
fi

if [ ! -f "$UNSIGNED_APK" ]; then
    echo "ERROR: Unsigned APK not found at $UNSIGNED_APK"
    echo "Run './gradlew assembleRelease' first."
    exit 1
fi

echo "=========================================="
echo "  Rezvan Mesh - APK Signing"
echo "=========================================="
echo "Unsigned APK: $UNSIGNED_APK"
echo "Signed APK:   $SIGNED_APK"
echo "Keystore:     $KEYSTORE_PATH"
echo "Key Alias:    ${KEY_ALIAS:-rezvan}"
echo ""

# Run apksigner
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass pass:"$KEYSTORE_PASSWORD" \
    --key-pass pass:"$KEY_PASSWORD" \
    --out "$SIGNED_APK" \
    "$UNSIGNED_APK"

# Verify signature
echo ""
echo "Verifying signature..."
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose "$SIGNED_APK"

echo ""
echo "=========================================="
echo "  Signing Complete"
echo "=========================================="
echo "Signed APK: $SIGNED_APK"
echo ""
echo "SHA-256:"
sha256sum "$SIGNED_APK"
echo ""
echo "Done."
