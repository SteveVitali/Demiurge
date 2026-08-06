# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

### Added
- Marketing website at demiurge.dev (`web/`) — landing, pricing, download, docs, and blog pages (Next.js 15, deployed on Vercel)
- Usage metering and credits — plan-based run and token limits enforced via the cloud API, with detailed over-limit upgrade prompts
- Auto-update support via `tauri-plugin-updater` with GitHub Releases endpoint
- Multi-platform release pipeline (GitHub Actions) for macOS (ARM64 + Intel), Linux, and Windows
- Code signing and notarization for macOS builds
- Windows NSIS installer with optional Authenticode signing
- Standalone CLI tarballs for macOS ARM64 and Linux x64
- Homebrew formula for CLI (`brew install demiurge`) and Cask for desktop app
- `useAutoUpdate` React hook for in-app update checks and installation
- Desktop build check in CI pipeline (TypeScript compilation verification)
- Process plugin for app relaunch after updates
- CHANGELOG.md for tracking release notes

### Changed
- Upgraded version from 0.1.0 to 0.2.0
- Updated bundle descriptions and copyright year
- Switched Windows installer from WiX to NSIS
- macOS signing identity set to `-` for CI-based signing
- Overhauled public-facing documentation with a second-pass accuracy audit

### Fixed
- Desktop backend auto-starts on launch, with health checks via Tauri invoke and visible startup diagnostics
- Desktop dev mode launches the backend through a Bazel wrapper instead of the sidecar placeholder
- Desktop UI fixes: pipeline stepper, failure tab, artifact loading, API key persistence
- `demiurge serve` now creates the worktree and run lock and performs auto-init, matching `demiurge run`
- Readiness checks and HTTP verifiers fall back between IPv4 and IPv6 when `localhost` resolution does not match the bound interface
- `agent_browser` requirement type is now accepted by requirements validation
- Service boot failures log the process output tail for easier diagnosis
- XSS in email templates and duplicate analytics pageviews on the marketing site

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
