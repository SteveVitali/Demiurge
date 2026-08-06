# Homebrew Packaging

Templates for distributing Demiurge via Homebrew. These files are **not**
consumed directly by `brew` from this repository — the published tap lives at
[`SteveVitali/homebrew-demiurge`](https://github.com/SteveVitali/homebrew-demiurge).

## Contents

- **`Formula/demiurge.rb`** — the CLI. Installs the `demiurge.jar` fat JAR
  from a GitHub release tarball and wraps it in a launcher script; depends on
  `openjdk@17`.
- **`Casks/demiurge.rb`** — the desktop app. Installs `Demiurge.app` from the
  release DMG (Apple Silicon).

## Release flow

The `sha256` values here are placeholders. On a tagged release, the
`update-homebrew` job in `.github/workflows/release.yml` bumps the **formula**
in the tap with the real version and checksum
(via `mislav/bump-homebrew-formula-action`). The cask has no automated bump —
update it in the tap manually when releasing a new desktop build.

## Installing (end users)

```bash
brew tap SteveVitali/demiurge
brew install demiurge          # CLI
brew install --cask demiurge   # Desktop app
```
