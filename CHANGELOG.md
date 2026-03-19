# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

### Added
- Auto-update support via `tauri-plugin-updater` with GitHub Releases endpoint
- Multi-platform release pipeline (GitHub Actions) for macOS (ARM64 + Intel), Linux, and Windows
- Code signing and notarization for macOS builds
- Windows NSIS installer with optional Authenticode signing
- Standalone CLI tarballs for macOS ARM64 and Linux x64
- Homebrew formula for CLI (`brew install demiurge`) and Cask for desktop app
- `useAutoUpdate` React hook for in-app update checks and installation
- Desktop build check in CI pipeline (TypeScript compilation verification)
- Deep link support (`demiurge://` protocol)
- Single-instance enforcement with deep-link forwarding
- Process plugin for app relaunch after updates
- CHANGELOG.md for tracking release notes

### Changed
- Upgraded version from 0.1.0 to 0.2.0
- Updated bundle descriptions and copyright year
- Switched Windows installer from WiX to NSIS
- macOS signing identity set to `-` for CI-based signing

## [0.1.0] - 2026-03-01

### Added
- Initial Tauri v2 desktop application
- Scala sidecar backend with Bazel build system
- CLI with run, build, serve, init commands
- Real-time SSE event streaming
- Monaco editor integration
- React Flow pipeline visualization
- System tray integration
- License validation and auth flow
