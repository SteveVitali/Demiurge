#!/usr/bin/env bash
set -euo pipefail

REPO="SteveVitali/Demiurge"
INSTALL_DIR="${DEMIURGE_INSTALL_DIR:-$HOME/.demiurge/bin}"

# Detect OS and arch
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
  darwin) OS_NAME="macos" ;;
  linux)  OS_NAME="linux" ;;
  *)      echo "Unsupported OS: $OS"; exit 1 ;;
esac

case "$ARCH" in
  arm64|aarch64) ARCH_NAME="arm64" ;;
  x86_64)        ARCH_NAME="x64" ;;
  *)             echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

# Fetch latest version
VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
TARBALL="demiurge-cli-${VERSION}-${OS_NAME}-${ARCH_NAME}.tar.gz"
URL="https://github.com/$REPO/releases/download/${VERSION}/${TARBALL}"

echo "Installing Demiurge $VERSION for $OS_NAME-$ARCH_NAME..."

# Download and extract
mkdir -p "$INSTALL_DIR"
curl -fsSL "$URL" | tar xz -C "$INSTALL_DIR" --strip-components=1

echo ""
echo "Demiurge installed to $INSTALL_DIR/demiurge"
echo ""

# Check if in PATH
if ! echo "$PATH" | grep -q "$INSTALL_DIR"; then
  echo "Add this to your shell profile:"
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
  echo ""
fi

echo "Run 'demiurge doctor' to verify your installation."
