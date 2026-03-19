#!/usr/bin/env bash
set -euo pipefail

# Desktop Phase 5 — §13: Package the Demiurge Scala backend as a fat JAR sidecar.
# Builds via Bazel, copies the deploy JAR, and creates a launcher script
# named with the Tauri target triple convention.
#
# Usage: ./package-sidecar.sh [--target-triple <triple>]
# Example: ./package-sidecar.sh --target-triple aarch64-apple-darwin

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DESKTOP_DIR="$SCRIPT_DIR/.."
BINARIES_DIR="$DESKTOP_DIR/src-tauri/binaries"

# Detect target triple
TARGET_TRIPLE="${TARGET_TRIPLE:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-triple) TARGET_TRIPLE="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$TARGET_TRIPLE" ]]; then
  # Auto-detect
  ARCH="$(uname -m)"
  OS="$(uname -s)"
  case "$OS" in
    Darwin)
      case "$ARCH" in
        arm64|aarch64) TARGET_TRIPLE="aarch64-apple-darwin" ;;
        x86_64) TARGET_TRIPLE="x86_64-apple-darwin" ;;
        *) echo "Unsupported arch: $ARCH"; exit 1 ;;
      esac
      ;;
    Linux)
      case "$ARCH" in
        x86_64) TARGET_TRIPLE="x86_64-unknown-linux-gnu" ;;
        aarch64) TARGET_TRIPLE="aarch64-unknown-linux-gnu" ;;
        *) echo "Unsupported arch: $ARCH"; exit 1 ;;
      esac
      ;;
    MINGW*|MSYS*|CYGWIN*)
      TARGET_TRIPLE="x86_64-pc-windows-msvc"
      ;;
    *) echo "Unsupported OS: $OS"; exit 1 ;;
  esac
fi

echo "=== Packaging Demiurge sidecar for $TARGET_TRIPLE ==="

# Step 1: Build the CLI fat JAR via Bazel
echo "Building CLI via Bazel..."
(cd "$REPO_ROOT" && bazel build //modules/cli:demiurge_deploy.jar 2>&1) || {
  echo "ERROR: Bazel build failed"
  exit 1
}

# Locate the deploy JAR
DEPLOY_JAR="$REPO_ROOT/bazel-bin/modules/cli/demiurge_deploy.jar"
if [[ ! -f "$DEPLOY_JAR" ]]; then
  echo "ERROR: Deploy JAR not found at $DEPLOY_JAR"
  exit 1
fi

# Step 2: Create binaries directory
mkdir -p "$BINARIES_DIR"

# Step 3: Copy the JAR
JAR_DEST="$BINARIES_DIR/demiurge-sidecar.jar"
cp "$DEPLOY_JAR" "$JAR_DEST"
echo "Copied JAR to $JAR_DEST ($(du -h "$JAR_DEST" | cut -f1))"

# Step 4: Create launcher script named with target triple
# Tauri expects: binaries/demiurge-sidecar-<target-triple>[.exe]
LAUNCHER="$BINARIES_DIR/demiurge-sidecar-$TARGET_TRIPLE"

if [[ "$TARGET_TRIPLE" == *"windows"* ]]; then
  # Windows batch launcher
  LAUNCHER="$LAUNCHER.exe"
  cat > "$BINARIES_DIR/demiurge-sidecar-$TARGET_TRIPLE.cmd" <<'BATCH'
@echo off
setlocal
set SCRIPT_DIR=%~dp0
java -jar "%SCRIPT_DIR%demiurge-sidecar.jar" %*
BATCH
  echo "Created Windows launcher"
else
  # Unix shell launcher
  cat > "$LAUNCHER" <<'SHELL'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec java -Xmx512m -jar "$SCRIPT_DIR/demiurge-sidecar.jar" "$@"
SHELL
  chmod +x "$LAUNCHER"
  echo "Created launcher at $LAUNCHER"
fi

echo "=== Sidecar packaged successfully ==="
echo "  JAR: $JAR_DEST"
echo "  Launcher: $LAUNCHER"
echo "  Triple: $TARGET_TRIPLE"
