#!/bin/bash
set -e

# =============================================================================
# Rezvan Mesh - Rust Cross-Compilation Script
# Compiles rezvan-core for Android targets (arm64-v8a, armeabi-v7a)
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RUST_DIR="$PROJECT_ROOT/rust"
JNI_LIBS_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs"

echo "=========================================="
echo "  Rezvan Mesh - Rust Build Script"
echo "=========================================="
echo "Project root: $PROJECT_ROOT"
echo "Rust dir:     $RUST_DIR"
echo "JNI libs:     $JNI_LIBS_DIR"
echo ""

# Check if cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Add Android targets
echo "Adding Android Rust targets..."
rustup target add aarch64-linux-android armv7-linux-androideabi

# Clean previous builds
echo "Cleaning previous builds..."
cd "$RUST_DIR"
cargo clean

# Create jniLibs directories
mkdir -p "$JNI_LIBS_DIR/arm64-v8a"
mkdir -p "$JNI_LIBS_DIR/armeabi-v7a"

# Build for arm64-v8a
echo ""
echo "Building for arm64-v8a..."
cargo ndk -t arm64-v8a -o "$JNI_LIBS_DIR" build --release -p rezvan-core

# Build for armeabi-v7a
echo ""
echo "Building for armeabi-v7a..."
cargo ndk -t armeabi-v7a -o "$JNI_LIBS_DIR" build --release -p rezvan-core

echo ""
echo "=========================================="
echo "  Build Complete"
echo "=========================================="
echo ""
echo "Output libraries:"
ls -la "$JNI_LIBS_DIR/arm64-v8a/librezvan_core.so"
ls -la "$JNI_LIBS_DIR/armeabi-v7a/librezvan_core.so"
echo ""
echo "Done."
