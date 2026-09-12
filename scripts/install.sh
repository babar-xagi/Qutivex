#!/usr/bin/env bash
# Qutivex POSIX Installer for Linux and macOS
# Usage: curl -fsSL https://raw.githubusercontent.com/babar-xagi/Qutivex/main/scripts/install.sh | bash

set -euo pipefail

QUTIVEX_VERSION="${QUTIVEX_VERSION:-0.3.5}"
INSTALL_DIR="${QUTIVEX_INSTALL_DIR:-$HOME/.qutivex}"
BIN_DIR="$INSTALL_DIR/bin"
REPO="babar-xagi/Qutivex"

echo "✨ Installing Qutivex v${QUTIVEX_VERSION}..."

# Check Java runtime (JDK 21+ required)
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
        echo "⚠️  Warning: Java version $JAVA_VER detected. Qutivex requires JDK 21 or higher."
    fi
else
    echo "⚠️  Warning: Java 21+ was not found on PATH. Qutivex requires a Java 21+ runtime."
fi

# Detect OS and Architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Linux)  PLATFORM="linux" ;;
    Darwin) PLATFORM="macos" ;;
    *)      echo "❌ Unsupported operating system: $OS"; exit 1 ;;
esac

TAR_URL="https://github.com/${REPO}/releases/download/v${QUTIVEX_VERSION}/qutivex-${QUTIVEX_VERSION}.tar.gz"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "📥 Downloading Qutivex archive from GitHub Releases..."
if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$TAR_URL" -o "$TMP_DIR/qutivex.tar.gz" || {
        echo "❌ Download failed. Please check version and network connection."
        exit 1
    }
elif command -v wget >/dev/null 2>&1; then
    wget -qO "$TMP_DIR/qutivex.tar.gz" "$TAR_URL" || {
        echo "❌ Download failed. Please check version and network connection."
        exit 1
    }
else
    echo "❌ Neither curl nor wget was found on PATH."
    exit 1
fi

echo "📦 Unpacking to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
tar -xzf "$TMP_DIR/qutivex.tar.gz" -C "$INSTALL_DIR" --strip-components=1
chmod +x "$BIN_DIR/qutivex"

echo "✅ Qutivex v${QUTIVEX_VERSION} installed successfully to $BIN_DIR/qutivex!"
echo ""

# Shell configuration check
case "$SHELL" in
    */zsh)  PROFILE="$HOME/.zshrc" ;;
    */bash) PROFILE="$HOME/.bashrc" ;;
    *)      PROFILE="$HOME/.profile" ;;
esac

if ! echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
    echo "To add Qutivex to your PATH, run:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    echo ""
    echo "Or append it to your shell configuration:"
    echo "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> $PROFILE"
    echo ""
fi

echo "Run 'qutivex --help' to get started."
