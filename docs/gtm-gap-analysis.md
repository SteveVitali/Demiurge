# Go-to-Market Implementation: Gap Analysis & Verification Plan

**Branch:** `review/gtm-gap-analysis`
**Date:** 2026-03-19
**Scope:** Comprehensive audit of Specs 01–05 implementation against `plan-go-to-market.md`

---

## Executive Summary

The 5-phase GTM implementation is **substantially complete**. All major systems are built: cloud backend API routes, license module, CLI auth commands, desktop auth UI, CI/CD pipelines, marketing website, and usage metering. The codebase is well-structured and follows the specs closely.

**Gaps found: 13 total (0 critical, 5 medium, 8 low)**

Most gaps fall into two categories:
1. **Placeholder values** that need real credentials before launch (expected — not code bugs)
2. **Missing tests** for web routes and integration flows
3. **Minor feature gaps** where implementation deviates slightly from spec

---

## Spec-by-Spec Audit

### Spec 01: Cloud Backend & Auth Foundation — ✅ Complete

| Component | Spec Section | Status | Notes |
|-----------|-------------|--------|-------|
| POST /api/webhooks/stripe | §8.2 | ✅ | All 5 events handled: checkout.session.completed, invoice.paid, invoice.payment_failed, subscription.updated, subscription.deleted |
| POST /api/webhooks/clerk | §8.1 | ✅ | user.created → Keygen user + trial license + Clerk metadata |
| POST /api/auth/device-code | §8.3 | ✅ | Returns device_code, user_code, verification_url |
| GET /api/auth/device-poll | §8.4 | ✅ | pending/authorized/expired states |
| POST /api/auth/device-authorize | §5.5 (Spec 04) | ✅ | Named `device-authorize` instead of `device-confirm` — functionally equivalent |
| GET /api/license/validate | §8.5 | ✅ | Supports both Bearer JWT and X-License-Key auth |
| POST /api/license/activate | §8.6 | ✅ | Machine activation with 409 conflict handling |
| GET /api/user/subscription | §8.7 | ✅ | File exists at expected path |
| POST /api/user/portal | §8.8 | ✅ | File exists at expected path |
| lib/keygen.ts | §9 | ✅ | Full Keygen REST client |
| lib/constants.ts | §10 | ✅ | Plan config, price-to-policy maps, price-to-tier maps |
| lib/stripe-helpers.ts | — | ✅ | Webhook construction, Stripe instance |
| lib/clerk-helpers.ts | — | ✅ | User lookup, metadata update, findByStripeCustomerId |
| lib/device-code-store.ts | §8.3 | ✅ | In-memory store with expiry |
| lib/env.ts | §4 | ✅ | Typed env access |
| .env.example | §4 | ✅ | All required vars listed |
| Tests | §13 | 🟡 | `keygen.test.ts`, `device-code-store.test.ts`, `constants.test.ts` exist — **no webhook handler tests** |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 1 | 🟡 Low | No Stripe webhook integration tests | Spec §13.2 calls for Stripe CLI forwarding tests. No test file exists for `webhooks/stripe/route.ts`. |
| 2 | 🟡 Low | No Clerk webhook integration tests | Spec §13.3 calls for test events. No test file for `webhooks/clerk/route.ts`. |

---

### Spec 02: CLI & Desktop License Integration — ✅ Complete

| Component | Spec Section | Status | Notes |
|-----------|-------------|--------|-------|
| modules/license/ module | §3 | ✅ | LicenseManager, CredentialStore, MachineFingerprint, CloudApiClient, LicenseStatus, UsageReporter |
| LicenseStatus ADT | §3.3 | ✅ | All 9 cases: Valid, Expired, Suspended, OverLimit, MachineNotActivated, TooManyMachines, NotFound, NoCredentials, NetworkError |
| CredentialStore | §3.4 | ✅ | credentials.json, config.json, machine-id — all with circe codecs |
| CloudApiClient | §3.5 | ✅ | validateLicense, activateMachine, startDeviceAuth, pollDeviceAuth + statusFromCode helper |
| LicenseManager.validate() | §3.6 | ✅ | Cache check → online validation → auto-activate → offline fallback. `cacheAndReturn` extracted. |
| MachineFingerprint | §2.3 | ✅ | SHA-256 of hostname\|osName\|osArch\|userName. `hostname()` helper extracted. |
| resolveApiKey() | §5.4 | ✅ | ENV → config fallback in CredentialStore |
| `demiurge login` | §4.1 | ✅ | Device code flow + `--license-key` direct entry |
| `demiurge logout` | §4.2 | ✅ | Clears credentials |
| `demiurge config` | §4.3 | ✅ | set/get/list with key masking |
| License gate in CliApp | §5.3 | ✅ | Gates run/build/resume; pre-run usage summary; detailed OverLimit message |
| Desktop AuthScreen | §6.1 | ✅ | Browser sign-in + manual key entry + validation + error states |
| Desktop AuthCallbackScreen | §6.2 | ✅ | Deep link param extraction → setCredentials → navigate to dashboard |
| Desktop auth.store.ts | §6.3 | ✅ | Zustand store with Tauri plugin-store backend, graceful dev-mode fallback |
| Deep link registration | §6.2 | ✅ | `tauri-plugin-deep-link` + `tauri-plugin-single-instance` in Cargo.toml, lib.rs, tauri.conf.json |
| PlanTierBadge in Sidebar | §6.6 | ✅ | Shows plan badge + user email in sidebar footer |
| Tests | §9 | ✅ | CredentialStoreSuite (6 tests), MachineFingerprintSuite (2 tests), LicenseStatusSuite (7 tests), LicenseManagerSuite, UsageReporterSuite (10 tests) |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 3 | 🟡 Medium | `demiurge status` doesn't show license/usage info | Spec §4.4 says `status` with no run ID should show plan, usage, API key info. Current StatusCommand only shows run info. Requires reading CredentialStore + CloudApiClient for usage display. |
| 4 | 🟡 Low | Desktop Settings screen missing account/API key sections | Spec §6.5 calls for Account section (email, plan, billing button), API Keys section (input fields), and Sign Out button on the Settings page. Not verified — may be partially implemented. |

---

### Spec 03: Distribution & CI/CD Pipeline — ✅ Complete

| Component | Spec Section | Status | Notes |
|-----------|-------------|--------|-------|
| release.yml workflow | §4.2 | ✅ | 5-step pipeline: build-sidecar → build-desktop (4 platforms) → build-cli → finalize-release → update-homebrew |
| ci.yml workflow | §5 | ✅ | Bazel build+test, worker tests, desktop TypeScript check |
| macOS code signing | §4.2 | ✅ | Certificate import + notarization env vars |
| Windows code signing | §4.2 | ✅ | Certificate env vars passed to tauri-action |
| Sidecar packaging | §4.2 | ✅ | JAR + shell/batch wrapper per target triple |
| CLI tarballs | §4.2 | ✅ | macos-arm64 + linux-x64 |
| Homebrew update | §6.5 | ✅ | `mislav/bump-homebrew-formula-action` step |
| tauri.conf.json updater | §3.1 | ✅ | Endpoints, pubkey placeholder, createUpdaterArtifacts, deep-link, shell, notification plugins |
| Cargo.toml plugins | §3.2 | ✅ | updater, deep-link, single-instance, process |
| lib.rs plugin registration | §3.3 | ✅ | All plugins registered in correct order |
| useAutoUpdate hook | §3.4 | ✅ | File exists at `desktop/src/hooks/useAutoUpdate.ts` |
| Homebrew formulas | §6.2, §6.3 | ✅ | Both `Formula/demiurge.rb` and `Casks/demiurge.rb` exist in `homebrew/` directory |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 5 | 🟡 Medium | Updater pubkey is placeholder | `tauri.conf.json` line 40: `"pubkey": "<PUBLIC_KEY_FROM_TAURI_SIGNER_GENERATE>"` — must be replaced with real key before first release. |
| 6 | 🟡 Low | No macOS x64 CLI tarball | Release workflow only builds macos-arm64 and linux-x64 CLI tarballs. Spec §8 lists both architectures for macOS. Homebrew formula references macos-x64 but no CI job builds it. |
| ~~7~~ | ✅ | ~~capabilities/default.json not verified~~ | Verified: all required permissions present (`updater:default`, `deep-link:default`, `process:default`) |

---

### Spec 04: Marketing Website — ✅ Complete

| Component | Spec Section | Status | Notes |
|-----------|-------------|--------|-------|
| Landing page `/` | §5.1 | ✅ | Hero, FeaturesGrid, HowItWorks, DemoVideo, Testimonials, CTABanner components exist |
| Pricing page `/pricing` | §5.2 | ✅ | PricingTable, PricingCard, CheckoutButton components exist |
| Download page `/download` | §5.3 | ✅ | DownloadCards, PlatformDetector, InstallInstructions |
| Docs `/docs` and `/docs/[slug]` | §5.4 | ✅ | DocsSidebar, TableOfContents, dynamic slug routing |
| Activate `/activate` | §5.5 | ✅ | Device code entry page |
| Account `/account` | §5.6 | ✅ | PlanCard, UsageCard components |
| Account billing `/account/billing` | §5.6 | ✅ | Stripe portal redirect |
| Auth callback `/auth-callback` | §5.7 | ✅ | Deep link construction page |
| Sign-in / Sign-up | — | ✅ | Clerk catch-all routes |
| Legal pages | — | ✅ | `/legal/terms`, `/legal/privacy` |
| Blog placeholder | — | ✅ | `/blog` route exists |
| Navbar / Footer / MobileMenu | §7 | ✅ | Layout components with shared nav-config |
| Clerk middleware | §8 | ✅ | Protects `/account(.*)` routes |
| Install script | §12 | ✅ | `web/public/install.sh` |
| Checkout API route | §5.2 | ✅ | `POST /api/checkout/create-session` exists |
| GitHub releases fetching | §5.3 | ✅ | `lib/github-releases.ts` |
| Docs lib | §5.4 | ✅ | `lib/docs.ts` for markdown loading |
| SEO metadata | §6 | ✅ | OpenGraph, Twitter card in root layout |
| robots.txt | §10 | ✅ | `web/public/robots.txt` |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 8 | 🟡 Low | No `LicenseKeyCard` component for account page | Spec §5.6 calls for a dedicated component showing license key with copy button. Web `components/account/` has PlanCard and UsageCard but no LicenseKeyCard. May be inlined in the account page. |

---

### Spec 05: Usage Metering & Credits — ✅ Complete

| Component | Spec Section | Status | Notes |
|-----------|-------------|--------|-------|
| UsageReporter.scala | §3.3 | ✅ | incrementRunCount + reportTokenUsage + offline sync |
| UsageResult ADT | §3.3 | ✅ | UsageReport, UsageLimitExceeded, UsageReportError |
| Offline usage tracking | §8.1 | ✅ | `offline-usage.json` + syncOfflineUsage + recordOfflineRun |
| RunOrchestrator integration | §3.2 | ✅ | incrementRunCount after Created state (non-resume only) |
| Token reporting | §5.2 | ✅ | reportTokenUsage at run completion |
| POST /api/license/increment-usage | §4.1 | ✅ | Proxies to Keygen increment-usage |
| POST /api/license/report-tokens | §4.2 | ✅ | Logs + future Supabase insert |
| GET /api/user/usage | §4.3 | ✅ | File exists at expected path |
| Pre-run usage summary | §6.1 | ✅ | "[demiurge] License valid (Pro plan). Usage: 42/200 runs this period." |
| Limit reached error | §6.2 | ✅ | Detailed message with upgrade URLs |
| Local API /usage endpoint | §7.5 | ✅ | UsageRoutes.getUsageHandler() proxies from LicenseManager |
| Desktop useUsage hook | §7.4 | ✅ | react-query with 60s refetch, 30s staleTime |
| Desktop UsageCard | §7.2 | ✅ | Runs + Tokens progress bars with color coding |
| Sidebar usage indicator | §7.1 | ✅ | BarChart3 icon + progress bar in sidebar footer |
| Shared usage utils | — | ✅ | `lib/usage.ts` with usageBarColor, usageTextColor, formatTokenCount |
| Tests | §10.1 | ✅ | UsageReporterSuite (10 tests) |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 9 | 🟡 Medium | Approaching-limit warning not implemented | Spec §6.3: When usage ≥80%, show yellow warning "[demiurge] Warning: 165/200 runs used this period (82%)." The current pre-run message in CliApp doesn't check percentage. |
| 10 | 🟡 Medium | `demiurge status` missing usage section with progress bar | Spec §6.4: Enhanced status command should show account info, usage bars (Unicode blocks), and API keys. Same as gap #3 above. |
| 11 | 🟡 Low | Token usage reported as 0,0 | RunOrchestrator line 942 reports `reportTokenUsage(licenseKey, run.runId, 0, 0)` — hardcoded zeros instead of actual token counts from InferenceService. |

---

### Master Plan (plan-go-to-market.md) Cross-Cutting Items

| Item | Plan Section | Status | Notes |
|------|-------------|--------|-------|
| Pricing model | §2 | ✅ | Tiers match spec: Trial (5 runs), Starter ($29/50 runs), Pro ($79/200 runs), Team ($49/user/150 runs) |
| BYOK + managed credits | §3 | ✅ | BYOK implemented; managed credits documented as future (Spec 05 §11) |
| Auth stack: Clerk + Stripe + Keygen | §4 | ✅ | All three integrated |
| Distribution channels | §5 | ✅ | Desktop (DMG/NSIS/AppImage), CLI (tarballs), Homebrew, GitHub Releases |
| Auto-update | §5 | ✅ | tauri-plugin-updater configured |
| Marketing site | §7 | ✅ | Full Next.js site with all specified sections |
| License change (MIT → BSL) | §8 | 🟡 | See gap #12 |
| Telemetry / Analytics | §6 (Telemetry) | 🟡 | See gap #13 |

**Gaps:**

| # | Severity | Gap | Detail |
|---|----------|-----|--------|
| 12 | 🟡 Medium | LICENSE file still MIT | Plan §8 recommends switching to BSL 1.1 before launch. The LICENSE file is still MIT. This is a policy decision, not a code bug. |
| 13 | 🟡 Low | No telemetry/analytics integration | Plan §6 (Telemetry) calls for PostHog or Supabase analytics. No telemetry SDK is integrated. Spec 05 §1 mentions this is foundation-only. |
| 14 | 🟡 Low | No transactional email (Resend) | Plan Phase 3 calls for Resend for welcome/trial-expiring/receipt emails. Not implemented. |

---

## Summary of All Gaps

| # | Severity | Area | Gap | Fix Effort |
|---|----------|------|-----|------------|
| 1 | 🟡 Low | Spec 01 | No Stripe webhook tests | 2h |
| 2 | 🟡 Low | Spec 01 | No Clerk webhook tests | 1h |
| 3 | 🟡 Medium | Spec 02/05 | `demiurge status` missing license/usage info | 2h |
| 4 | 🟡 Low | Spec 02 | Desktop Settings missing account/API key sections | 3h |
| 5 | 🟡 Medium | Spec 03 | Updater pubkey is placeholder | 15min (manual step) |
| 6 | 🟡 Low | Spec 03 | No macOS x64 CLI tarball in release | 30min |
| ~~7~~ | ~~Low~~ | ~~Spec 03~~ | ~~Capabilities not verified~~ | ✅ Verified — all permissions present |
| 8 | 🟡 Low | Spec 04 | No LicenseKeyCard component | 1h |
| 9 | 🟡 Medium | Spec 05 | No approaching-limit warning (≥80%) | 30min |
| 10 | 🟡 Medium | Spec 05 | Status command missing usage section | Same as #3 |
| 11 | 🟡 Low | Spec 05 | Token usage reported as 0,0 | 30min |
| 12 | 🟡 Medium | Master Plan | LICENSE still MIT (policy decision) | 15min |
| 13 | 🟡 Low | Master Plan | No telemetry integration | 2h |
| 14 | 🟡 Low | Master Plan | No transactional email (Resend) | 3h |

---

## Fix Plan

### Phase A: Quick Wins (< 1 hour total)

**A1. Approaching-limit warning (Gap #9)**
In `CliApp.scala` line 38–42, after the `LicenseStatus.Valid` match, add:
```scala
if (maxUses > 0) {
  val pct = (uses.toDouble / maxUses * 100).toInt
  if (pct >= 80) {
    System.err.println(s"[demiurge] Warning: $uses/$maxUses runs used this period ($pct%). Period resets soon.")
  }
}
```

**A2. Fix token usage reporting (Gap #11)**
In `RunOrchestrator.scala` line 942, replace `0, 0` with actual token counts from the inference service or usage tracker.

**A3. Verify capabilities (Gap #7)**
Check `desktop/src-tauri/capabilities/default.json` includes `updater:default`, `deep-link:default`, `process:default`.

**A4. Add macOS x64 CLI tarball (Gap #6)**
Add a `macos-x64` entry to the `build-cli` matrix in `release.yml`.

### Phase B: Status Command Enhancement (~ 2 hours)

**B1. Enhanced `demiurge status` (Gaps #3 and #10)**
Modify `StatusCommand.execute()` to check if no run ID is provided, and if so, show:
- Account: email, plan tier, expiry
- Usage: runs used/max with ASCII progress bar, tokens used/max
- API Keys: masked Anthropic/OpenAI keys
- Recent Runs (existing functionality)

This requires `StatusCommand` to import from the `license` module and call `LicenseManager.validate()` + `CredentialStore.loadConfig()`.

### Phase C: Test Coverage (~ 3 hours)

**C1. Stripe webhook handler tests (Gap #1)**
Create `web/src/lib/__tests__/stripe-webhook.test.ts` testing:
- `checkout.session.completed` → license transfer + metadata update
- `invoice.paid` → license renewal + usage reset
- `subscription.deleted` → license suspension
Mock Keygen + Clerk clients.

**C2. Clerk webhook handler tests (Gap #2)**
Create `web/src/lib/__tests__/clerk-webhook.test.ts` testing:
- `user.created` → Keygen user + trial license created

### Phase D: Desktop Polish (~ 4 hours)

**D1. Settings screen account section (Gap #4)**
Add to SettingsScreen: Account info display, API key input fields (synced to sidecar config), Manage Billing button, Sign Out button.

**D2. LicenseKeyCard for web account page (Gap #8)**
Create `web/src/components/account/LicenseKeyCard.tsx` with copy-to-clipboard functionality.

### Phase E: Pre-Launch Decisions (Policy, not code)

**E1. License change (Gap #12)** — Decide whether to switch to BSL 1.1. If yes, replace LICENSE file contents and update any references.

**E2. Telemetry (Gap #13)** — Decide on PostHog vs Supabase. Add SDK + consent banner.

**E3. Transactional email (Gap #14)** — Set up Resend account, create templates for welcome/trial-expiring/receipt.

**E4. Updater pubkey (Gap #5)** — Run `npx @tauri-apps/cli signer generate` and set the real pubkey in tauri.conf.json + GitHub secrets.

---

## Verification Strategy

### Level 1: Build Verification (Automated)

```bash
# Bazel — all Scala modules including new license module
bazel build //...
bazel test //... --test_output=errors

# Worker — TypeScript tests
cd worker && npm test

# Desktop — TypeScript compilation
cd desktop && npx tsc --noEmit

# Web — TypeScript compilation + lint
cd web && npx tsc --noEmit
```

### Level 2: Unit Test Coverage

| Module | Test File | What It Covers |
|--------|-----------|---------------|
| license | CredentialStoreSuite | JSON round-trip, resolveApiKey |
| license | MachineFingerprintSuite | SHA-256 format, determinism |
| license | LicenseStatusSuite | ADT completeness, CloudApiClient decoder tests |
| license | LicenseManagerSuite | Validation flow (would need mock server for full coverage) |
| license | UsageReporterSuite | Empty key fast-path, network error handling, fire-and-forget |
| web/lib | keygen.test.ts | Keygen client functions |
| web/lib | device-code-store.test.ts | Create, poll, authorize, expiry |
| web/lib | constants.test.ts | Plan config, price mappings |

### Level 3: Integration Smoke Tests (Manual)

**3a. Full Auth Flow**
1. Start web dev server: `cd web && npm run dev`
2. Sign up via Clerk → verify trial license created in Keygen dashboard
3. Copy license key from account page
4. `demiurge login --license-key <KEY>` → verify success
5. `demiurge run --task "test"` → verify license gate passes + usage increments
6. `demiurge logout` → verify credentials cleared
7. `demiurge run` → verify "Not logged in" error

**3b. Device Code Flow**
1. `demiurge login` (no key) → verify code displayed
2. Visit `/activate`, sign in, enter code → verify CLI receives auth

**3c. Desktop App Auth**
1. Launch desktop app → verify AuthScreen appears
2. Enter license key → verify dashboard loads
3. Check sidebar → verify plan badge + usage indicator
4. Settings → Sign Out → verify returns to auth screen

**3d. Billing Flow**
1. Click "Get Starter" on pricing page → verify Stripe Checkout
2. Complete test payment → verify webhook fires → verify license upgraded
3. Cancel subscription → verify license suspended

**3e. CI/CD Pipeline**
1. Push a test tag `v0.0.1-test` → verify release workflow triggers
2. Verify all 4 platform builds succeed
3. Verify CLI tarballs are attached to release
4. Verify `latest.json` is generated

**3f. Marketing Website**
1. Visit landing page → verify all sections render
2. Visit /pricing → verify tier cards, billing toggle
3. Visit /download → verify OS auto-detection
4. Visit /docs → verify sidebar navigation, content rendering
5. Run Lighthouse audit → target scores per spec §14.6

### Level 4: End-to-End Flow

Full user journey: Sign up → get trial → login CLI → run → hit limit → upgrade → run again → cancel

This requires real Clerk + Stripe + Keygen accounts in test mode.

---

## What To Do Next

### Immediate (Before First Release)

1. **Fix the 4 quick wins** (Phase A) — 1 hour
2. **Enhance status command** (Phase B) — 2 hours
3. **Generate Tauri updater keypair** and set real pubkey — 15 min
4. **Set up Clerk/Stripe/Keygen test accounts** and configure `.env` — 1 hour
5. **Run Level 1 build verification** to confirm everything compiles clean

### Before Public Launch

6. **Decide on license** (MIT vs BSL 1.1) — policy decision
7. **Write webhook tests** (Phase C) — 3 hours
8. **Desktop settings polish** (Phase D) — 4 hours
9. **Set up real Apple Developer + Windows code signing** — external purchases
10. **Register domain** (demiurge.dev) and deploy web to Vercel
11. **Run full Level 3 integration smoke tests** with real services

### Post-Launch

12. Add telemetry (PostHog)
13. Add transactional email (Resend)
14. Add team management
15. Add managed credits system
