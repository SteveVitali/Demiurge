# Spec 03: Distribution & CI/CD Pipeline

> **Parent document:** [plan-go-to-market.md](./plan-go-to-market.md)
> **Phase:** 3 of 5 — Can be implemented in parallel with Specs 01 and 02 (independent).
> **Estimated effort:** 3–4 days.

---

## 1. Overview

This spec defines how Demiurge is built, signed, and distributed to end users across macOS, Windows, and Linux. It covers:

- **Code signing** (Apple notarization + Windows Authenticode)
- **Auto-update** via `tauri-plugin-updater` + GitHub Releases
- **CI/CD** via GitHub Actions for multi-platform builds
- **Homebrew** distribution for macOS CLI
- **Version management** strategy

After this spec, pushing a git tag like `v0.2.0` automatically produces signed, notarized, auto-updating desktop app installers and CLI binaries for all three platforms.

---

## 2. Prerequisites (Manual Setup)

These are one-time setup steps that must be done by a human before the CI/CD pipeline can run.

### 2.1 Apple Developer Program

- **Enroll** at [developer.apple.com](https://developer.apple.com) ($99/year)
- **Create a Developer ID Application certificate** (for signing apps distributed outside the App Store)
- **Create an App-Specific Password** at [appleid.apple.com](https://appleid.apple.com) → Security → App-Specific Passwords (for notarization)
- **Create an API key** in App Store Connect → Users and Access → Integrations → Generate API Key (for CI notarization via `notarytool`)

**GitHub Secrets to set:**
| Secret | Value |
|--------|-------|
| `APPLE_CERTIFICATE` | Base64-encoded `.p12` Developer ID Application certificate |
| `APPLE_CERTIFICATE_PASSWORD` | Password for the `.p12` file |
| `APPLE_ID` | Your Apple ID email |
| `APPLE_TEAM_ID` | 10-character Team ID from Apple Developer portal |
| `APPLE_API_KEY_ID` | App Store Connect API Key ID |
| `APPLE_API_KEY_ISSUER` | App Store Connect API Key Issuer ID |
| `APPLE_API_KEY` | Base64-encoded `.p8` private key file contents |

### 2.2 Windows Code Signing

- **Purchase an EV Code Signing Certificate** from DigiCert, Sectigo, or GlobalSign (~$300-500/year)
- EV certificates eliminate SmartScreen warnings entirely on first download
- For CI, use a cloud-based signing service (e.g., DigiCert KeyLocker, Azure SignTool, or SignPath.io) since EV certs require hardware tokens

**GitHub Secrets to set:**
| Secret | Value |
|--------|-------|
| `WINDOWS_CERTIFICATE` | Base64-encoded `.pfx` code signing certificate (for standard OV cert) |
| `WINDOWS_CERTIFICATE_PASSWORD` | Password for the `.pfx` |
| `AZURE_KEY_VAULT_URI` | (If using Azure Key Vault for EV cert) |
| `AZURE_CLIENT_ID` | (If using Azure Key Vault) |
| `AZURE_CLIENT_SECRET` | (If using Azure Key Vault) |
| `AZURE_TENANT_ID` | (If using Azure Key Vault) |

### 2.3 Tauri Updater Key Pair

Generate a key pair for signing updates:

```bash
npx @tauri-apps/cli signer generate -w ~/.tauri/demiurge.key
```

This produces:
- `~/.tauri/demiurge.key` — private key (keep secret)
- Public key — displayed in stdout (embed in `tauri.conf.json`)

**GitHub Secrets:**
| Secret | Value |
|--------|-------|
| `TAURI_SIGNING_PRIVATE_KEY` | Contents of `~/.tauri/demiurge.key` |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | Password for the key (if set) |

---

## 3. Tauri Configuration Changes

### 3.1 tauri.conf.json Updates

```jsonc
{
  "productName": "Demiurge",
  "version": "0.2.0",
  "identifier": "com.demiurge.desktop",
  "plugins": {
    "updater": {
      "endpoints": [
        "https://github.com/SteveVitali/Demiurge/releases/latest/download/latest.json"
      ],
      "pubkey": "<PUBLIC_KEY_FROM_TAURI_SIGNER_GENERATE>"
    },
    "deep-link": {
      "desktop": {
        "schemes": ["demiurge"]
      }
    },
    "shell": { "open": true },
    "notification": { "all": true }
  },
  "bundle": {
    "active": true,
    "targets": "all",
    "createUpdaterArtifacts": true,
    "icon": [
      "icons/32x32.png",
      "icons/128x128.png",
      "icons/128x128@2x.png",
      "icons/icon.icns",
      "icons/icon.ico"
    ],
    "externalBin": [
      "binaries/demiurge-sidecar"
    ],
    "macOS": {
      "minimumSystemVersion": "10.15",
      "signingIdentity": "-",
      "entitlements": null
    },
    "windows": {
      "nsis": {
        "displayLanguageSelector": false
      }
    },
    "linux": {
      "deb": {
        "depends": ["libwebkit2gtk-4.1-0", "libjavascriptcoregtk-4.1-0"]
      },
      "appimage": {
        "bundleMediaFramework": false
      }
    },
    "category": "DeveloperTool",
    "shortDescription": "AI-powered web development automation platform",
    "longDescription": "Demiurge automates verification, repair, and building of web applications using AI agents.",
    "copyright": "Copyright 2026 Demiurge",
    "licenseFile": "../../LICENSE"
  }
}
```

### 3.2 Cargo.toml Updates

Add the updater plugin:

```toml
[dependencies]
tauri-plugin-updater = "2"
tauri-plugin-deep-link = "2"
tauri-plugin-single-instance = { version = "2", features = ["deep-link"] }
```

### 3.3 lib.rs Plugin Registration

```rust
// In lib.rs run()
tauri::Builder::default()
    .plugin(tauri_plugin_single_instance::init(|_app, argv, _cwd| {
        println!("new instance opened with {argv:?}");
    }))
    .plugin(tauri_plugin_deep_link::init())
    .plugin(tauri_plugin_updater::Builder::new().build())
    .plugin(tauri_plugin_shell::init())
    // ... rest of plugins
```

### 3.4 Frontend Update Check

Add an update check on app startup in the React frontend:

**New file:** `desktop/src/hooks/useAutoUpdate.ts`

```typescript
import { check } from '@tauri-apps/plugin-updater';
import { relaunch } from '@tauri-apps/plugin-process';
import { useState, useEffect } from 'react';

export function useAutoUpdate() {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [updateVersion, setUpdateVersion] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);

  useEffect(() => {
    checkForUpdate();
  }, []);

  async function checkForUpdate() {
    try {
      const update = await check();
      if (update) {
        setUpdateAvailable(true);
        setUpdateVersion(update.version);
      }
    } catch (e) {
      console.warn('Update check failed:', e);
    }
  }

  async function installUpdate() {
    try {
      setIsUpdating(true);
      const update = await check();
      if (update) {
        await update.downloadAndInstall();
        await relaunch();
      }
    } catch (e) {
      console.error('Update failed:', e);
      setIsUpdating(false);
    }
  }

  return { updateAvailable, updateVersion, isUpdating, installUpdate };
}
```

Add `@tauri-apps/plugin-updater` and `@tauri-apps/plugin-process` to `desktop/package.json`.

### 3.5 Capabilities Update

Add to `desktop/src-tauri/capabilities/default.json`:

```json
{
  "permissions": [
    "core:default",
    "shell:default",
    "shell:allow-open",
    "dialog:default",
    "dialog:allow-open",
    "notification:default",
    "notification:allow-notify",
    "store:default",
    "window-state:default",
    "updater:default",
    "deep-link:default",
    "process:default"
  ]
}
```

---

## 4. GitHub Actions: Release Workflow

### 4.1 Workflow Trigger

The release workflow triggers on git tags matching `v*`:

```yaml
on:
  push:
    tags:
      - 'v*'
```

### 4.2 Full Workflow File

**File:** `.github/workflows/release.yml`

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

env:
  TAURI_SIGNING_PRIVATE_KEY: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY }}
  TAURI_SIGNING_PRIVATE_KEY_PASSWORD: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY_PASSWORD }}

jobs:
  # --- Step 1: Build the Scala sidecar JAR ---
  build-sidecar:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4

      - name: Setup Bazel
        uses: bazel-contrib/setup-bazel@0.14.0
        with:
          bazelisk-cache: true
          disk-cache: ${{ github.workflow }}
          repository-cache: true

      - name: Build sidecar JAR
        run: bazel build //modules/cli:demiurge_deploy.jar

      - name: Upload sidecar JAR
        uses: actions/upload-artifact@v4
        with:
          name: sidecar-jar
          path: bazel-bin/modules/cli/demiurge_deploy.jar

  # --- Step 2: Build Tauri desktop app (per platform) ---
  build-desktop:
    needs: build-sidecar
    strategy:
      fail-fast: false
      matrix:
        include:
          - platform: macos-latest
            target: aarch64-apple-darwin
            args: --target aarch64-apple-darwin
          - platform: macos-latest
            target: x86_64-apple-darwin
            args: --target x86_64-apple-darwin
          - platform: ubuntu-22.04
            target: x86_64-unknown-linux-gnu
            args: ""
          - platform: windows-latest
            target: x86_64-pc-windows-msvc
            args: ""

    runs-on: ${{ matrix.platform }}
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - name: Download sidecar JAR
        uses: actions/download-artifact@v4
        with:
          name: sidecar-jar
          path: /tmp/sidecar

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: desktop/package-lock.json

      - name: Install Rust stable
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: ${{ matrix.target }}

      - name: Install Linux dependencies
        if: matrix.platform == 'ubuntu-22.04'
        run: |
          sudo apt-get update
          sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf

      - name: Install frontend dependencies
        run: npm ci
        working-directory: desktop

      # --- Package sidecar for the target triple ---
      - name: Package sidecar
        shell: bash
        run: |
          mkdir -p desktop/src-tauri/binaries
          cp /tmp/sidecar/demiurge_deploy.jar desktop/src-tauri/binaries/demiurge-sidecar.jar

          if [[ "${{ matrix.target }}" == *"windows"* ]]; then
            cat > "desktop/src-tauri/binaries/demiurge-sidecar-${{ matrix.target }}.exe" << 'BATCH'
          @echo off
          setlocal
          set SCRIPT_DIR=%~dp0
          java -Xmx512m -jar "%SCRIPT_DIR%demiurge-sidecar.jar" %*
          BATCH
          else
            cat > "desktop/src-tauri/binaries/demiurge-sidecar-${{ matrix.target }}" << 'SHELL'
          #!/usr/bin/env bash
          set -euo pipefail
          SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
          exec java -Xmx512m -jar "$SCRIPT_DIR/demiurge-sidecar.jar" "$@"
          SHELL
            chmod +x "desktop/src-tauri/binaries/demiurge-sidecar-${{ matrix.target }}"
          fi

      # --- macOS Code Signing ---
      - name: Import Apple certificate
        if: startsWith(matrix.platform, 'macos')
        env:
          APPLE_CERTIFICATE: ${{ secrets.APPLE_CERTIFICATE }}
          APPLE_CERTIFICATE_PASSWORD: ${{ secrets.APPLE_CERTIFICATE_PASSWORD }}
        run: |
          echo "$APPLE_CERTIFICATE" | base64 --decode > certificate.p12
          security create-keychain -p actions build.keychain
          security default-keychain -s build.keychain
          security unlock-keychain -p actions build.keychain
          security import certificate.p12 -k build.keychain -P "$APPLE_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
          security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k actions build.keychain
          rm certificate.p12

      # --- Build Tauri app ---
      - name: Build Tauri app
        uses: tauri-apps/tauri-action@v0
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          # macOS notarization
          APPLE_ID: ${{ secrets.APPLE_ID }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          APPLE_API_KEY_ID: ${{ secrets.APPLE_API_KEY_ID }}
          APPLE_API_KEY_ISSUER: ${{ secrets.APPLE_API_KEY_ISSUER }}
          APPLE_API_KEY: ${{ secrets.APPLE_API_KEY }}
          # Windows signing (if using pfx)
          WINDOWS_CERTIFICATE: ${{ secrets.WINDOWS_CERTIFICATE }}
          WINDOWS_CERTIFICATE_PASSWORD: ${{ secrets.WINDOWS_CERTIFICATE_PASSWORD }}
        with:
          projectPath: desktop
          tauriScript: npx tauri
          args: ${{ matrix.args }}
          tagName: ${{ github.ref_name }}
          releaseName: 'Demiurge ${{ github.ref_name }}'
          releaseBody: 'See the release notes in the [changelog](https://github.com/SteveVitali/Demiurge/blob/main/CHANGELOG.md).'
          releaseDraft: true
          prerelease: false
          includeUpdaterJson: true

  # --- Step 3: Build standalone CLI packages ---
  build-cli:
    needs: build-sidecar
    strategy:
      matrix:
        include:
          - platform: macos-latest
            os_name: macos-arm64
          - platform: ubuntu-latest
            os_name: linux-x64
    runs-on: ${{ matrix.platform }}
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4

      - name: Download sidecar JAR
        uses: actions/download-artifact@v4
        with:
          name: sidecar-jar
          path: /tmp/sidecar

      - name: Package CLI tarball
        run: |
          VERSION="${{ github.ref_name }}"
          mkdir -p "demiurge-${VERSION}-${{ matrix.os_name }}"
          cp /tmp/sidecar/demiurge_deploy.jar "demiurge-${VERSION}-${{ matrix.os_name }}/demiurge.jar"

          cat > "demiurge-${VERSION}-${{ matrix.os_name }}/demiurge" << 'SHELL'
          #!/usr/bin/env bash
          set -euo pipefail
          SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
          exec java -Xmx512m -jar "$SCRIPT_DIR/demiurge.jar" "$@"
          SHELL
          chmod +x "demiurge-${VERSION}-${{ matrix.os_name }}/demiurge"

          tar czf "demiurge-cli-${VERSION}-${{ matrix.os_name }}.tar.gz" "demiurge-${VERSION}-${{ matrix.os_name }}"

      - name: Upload CLI tarball to release
        uses: softprops/action-gh-release@v2
        with:
          draft: true
          files: demiurge-cli-*.tar.gz

  # --- Step 4: Finalize release (undraft) ---
  finalize-release:
    needs: [build-desktop, build-cli]
    runs-on: ubuntu-latest
    steps:
      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          draft: false
          tag_name: ${{ github.ref_name }}
```

---

## 5. Update CI Workflow

Modify the existing `.github/workflows/ci.yml` to also run Bazel tests and verify the desktop build compiles (without signing):

Add a desktop build check job:

```yaml
  check-desktop-build:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install dependencies
        run: npm ci
        working-directory: desktop

      - name: TypeScript check
        run: npx tsc --noEmit
        working-directory: desktop
```

---

## 6. Homebrew Distribution

### 6.1 Homebrew Tap Repository

Create a separate GitHub repository: `SteveVitali/homebrew-demiurge`

This repository contains Homebrew formula files that point to the release assets.

### 6.2 CLI Formula

**File:** `Formula/demiurge.rb` (in the homebrew-demiurge repo)

```ruby
class Demiurge < Formula
  desc "AI-powered web development automation platform"
  homepage "https://demiurge.dev"
  version "0.2.0"
  license "BSL-1.1"

  depends_on "openjdk@17"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-macos-arm64.tar.gz"
      sha256 "PLACEHOLDER_SHA256"
    else
      url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-macos-x64.tar.gz"
      sha256 "PLACEHOLDER_SHA256"
    end
  end

  on_linux do
    url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/demiurge-cli-v#{version}-linux-x64.tar.gz"
    sha256 "PLACEHOLDER_SHA256"
  end

  def install
    libexec.install "demiurge.jar"

    (bin/"demiurge").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@17"].opt_bin}/java" -Xmx512m -jar "#{libexec}/demiurge.jar" "$@"
    EOS
  end

  test do
    assert_match "demiurge", shell_output("#{bin}/demiurge --help")
  end
end
```

### 6.3 Cask Formula (Desktop App)

**File:** `Casks/demiurge.rb`

```ruby
cask "demiurge" do
  version "0.2.0"
  sha256 "PLACEHOLDER_SHA256"

  url "https://github.com/SteveVitali/Demiurge/releases/download/v#{version}/Demiurge_#{version}_aarch64.dmg",
      verified: "github.com/SteveVitali/Demiurge/"
  name "Demiurge"
  desc "AI-powered web development automation platform"
  homepage "https://demiurge.dev"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "Demiurge.app"

  zap trash: [
    "~/.demiurge",
    "~/Library/Application Support/com.demiurge.desktop",
  ]
end
```

### 6.4 Installation Commands

```bash
# Install CLI
brew tap SteveVitali/demiurge
brew install demiurge

# Install Desktop App
brew tap SteveVitali/demiurge
brew install --cask demiurge
```

### 6.5 Formula Update Automation

Add a GitHub Action step in the release workflow (or a separate workflow) that updates the Homebrew formula after a release is published:

```yaml
  update-homebrew:
    needs: finalize-release
    runs-on: ubuntu-latest
    steps:
      - name: Update Homebrew formula
        uses: mislav/bump-homebrew-formula-action@v3
        with:
          formula-name: demiurge
          homebrew-tap: SteveVitali/homebrew-demiurge
          download-url: https://github.com/SteveVitali/Demiurge/releases/download/${{ github.ref_name }}/demiurge-cli-${{ github.ref_name }}-macos-arm64.tar.gz
        env:
          COMMITTER_TOKEN: ${{ secrets.HOMEBREW_TAP_TOKEN }}
```

---

## 7. Version Management

### 7.1 Single Source of Truth

The version is defined in `desktop/src-tauri/tauri.conf.json` → `"version"` field. All other version references derive from this or from the git tag.

### 7.2 Release Process

```bash
# 1. Update version in tauri.conf.json
# 2. Update CHANGELOG.md
# 3. Commit and tag
git add -A
git commit -m "release: v0.2.0"
git tag v0.2.0
git push origin main --tags

# GitHub Actions takes over:
# - Builds sidecar JAR
# - Builds signed desktop apps for all platforms
# - Builds CLI tarballs
# - Creates GitHub Release with all artifacts + update manifest (latest.json)
# - Updates Homebrew formula
```

### 7.3 Update Manifest (latest.json)

The `tauri-action` automatically generates `latest.json` and attaches it to the release. It looks like:

```json
{
  "version": "0.2.0",
  "notes": "See the release notes...",
  "pub_date": "2026-03-20T00:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "signature": "...",
      "url": "https://github.com/.../Demiurge_0.2.0_aarch64.app.tar.gz"
    },
    "darwin-x86_64": {
      "signature": "...",
      "url": "https://github.com/.../Demiurge_0.2.0_x64.app.tar.gz"
    },
    "linux-x86_64": {
      "signature": "...",
      "url": "https://github.com/.../Demiurge_0.2.0_amd64.AppImage.tar.gz"
    },
    "windows-x86_64": {
      "signature": "...",
      "url": "https://github.com/.../Demiurge_0.2.0_x64-setup.nsis.zip"
    }
  }
}
```

---

## 8. Release Artifacts Summary

After a successful release build, the GitHub Release contains:

| Artifact | Platform | Type |
|----------|----------|------|
| `Demiurge_X.Y.Z_aarch64.dmg` | macOS ARM64 | Desktop installer |
| `Demiurge_X.Y.Z_x64.dmg` | macOS Intel | Desktop installer |
| `Demiurge_X.Y.Z_amd64.deb` | Linux x64 | Desktop installer |
| `Demiurge_X.Y.Z_amd64.AppImage` | Linux x64 | Desktop portable |
| `Demiurge_X.Y.Z_x64-setup.exe` | Windows x64 | Desktop installer (NSIS) |
| `demiurge-cli-vX.Y.Z-macos-arm64.tar.gz` | macOS ARM64 | CLI standalone |
| `demiurge-cli-vX.Y.Z-linux-x64.tar.gz` | Linux x64 | CLI standalone |
| `latest.json` | All | Auto-update manifest |

---

## 9. Changes to Existing Files

| File | Change |
|------|--------|
| `desktop/src-tauri/tauri.conf.json` | Add `plugins.updater` config with endpoint + pubkey; add `createUpdaterArtifacts: true`; add `plugins.deep-link` |
| `desktop/src-tauri/Cargo.toml` | Add `tauri-plugin-updater`, `tauri-plugin-deep-link`, `tauri-plugin-single-instance`, `tauri-plugin-process` |
| `desktop/src-tauri/src/lib.rs` | Register updater, deep-link, single-instance, process plugins |
| `desktop/src-tauri/capabilities/default.json` | Add `updater:default`, `deep-link:default`, `process:default` |
| `desktop/package.json` | Add `@tauri-apps/plugin-updater`, `@tauri-apps/plugin-process`, `@tauri-apps/plugin-deep-link` |
| `desktop/src/hooks/useAutoUpdate.ts` | New file — update check + install |
| `.github/workflows/ci.yml` | Add `check-desktop-build` job |
| `.github/workflows/release.yml` | New file — full release pipeline |

### New Repositories

| Repository | Purpose |
|------------|---------|
| `SteveVitali/homebrew-demiurge` | Homebrew tap with CLI formula + desktop cask |

---

## 10. Testing Plan

### 10.1 CI Pipeline Tests

- Push to a feature branch → CI workflow runs Bazel build, Bazel tests, worker tests, desktop TypeScript check
- Push a tag like `v0.0.1-test` → Release workflow triggers, builds all artifacts (initially without real code signing certs — use self-signed for testing)

### 10.2 Code Signing Verification

- **macOS:** Download DMG → open → verify no Gatekeeper warning. Run `codesign -dv --verbose=4 /Applications/Demiurge.app` to verify.
- **Windows:** Download NSIS installer → run → verify no SmartScreen warning.
- **Linux:** Download AppImage → `chmod +x` → run.

### 10.3 Auto-Update Test

1. Build and release `v0.0.1-test` with a test update endpoint
2. Install `v0.0.1-test`
3. Build and release `v0.0.2-test`
4. Open the app → verify update notification appears
5. Click install → verify app restarts with new version

### 10.4 Homebrew Test

```bash
brew tap SteveVitali/demiurge
brew install demiurge
demiurge --help  # Should print help text
demiurge doctor  # Should check system prerequisites
```

---

## 11. Out of Scope

- GraalVM native image compilation (future optimization)
- Mac App Store distribution (not needed for dev tools)
- Windows Store distribution (not needed)
- Flatpak/Snap distribution (low priority, future)
- Release notes automation / changelog generation
- Download page on marketing website (Spec 04)
