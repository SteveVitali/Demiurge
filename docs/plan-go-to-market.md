# Demiurge Go-to-Market Plan

## Research-backed recommendations for packaging, distributing, licensing, pricing, and marketing Demiurge as a commercial product.

---

## Table of Contents

1. [Competitive Landscape & Pricing Models](#1-competitive-landscape--pricing-models)
2. [Recommended Pricing Model for Demiurge](#2-recommended-pricing-model-for-demiurge)
3. [API Key Strategy: BYOK vs Managed](#3-api-key-strategy-byok-vs-managed)
4. [Authentication, Licensing & Trial Gating](#4-authentication-licensing--trial-gating)
5. [Distribution & Delivery](#5-distribution--delivery)
6. [Backend Infrastructure](#6-backend-infrastructure)
7. [Marketing Website](#7-marketing-website)
8. [License & Open Source Considerations](#8-license--open-source-considerations)
9. [Implementation Phases](#9-implementation-phases)
10. [Cost Estimates](#10-cost-estimates)

---

## 1. Competitive Landscape & Pricing Models

### What Comparable Tools Charge (as of early 2026)

| Tool | Free Tier | Individual | Team/Business | Enterprise | Model |
|------|-----------|------------|---------------|------------|-------|
| **Cursor** | Free (2k completions, 50 premium req/mo) | Pro $20/mo, Pro+ $39/mo, Ultra $200/mo | $40/user/mo | Custom | Subscription + credit pool (= plan price in $) that depletes by actual API cost. Overage at API rates. |
| **Windsurf** | Free (limited) | Pro $15/mo | $30/user/mo (+$10 managed) | Custom | Subscription + credits. BYOK supported on Pro+. |
| **GitHub Copilot** | Free (50 premium req/mo) | Pro $10/mo, Pro+ $39/mo | Business $19/user/mo | $39/user/mo | Subscription + premium request allotment. Extra requests $0.04 each. |
| **Devin** | — | Core $20/mo | Team $500/mo (250 ACUs) | Custom | Subscription + Agent Compute Units (ACUs). Extra ACUs $2–$2.25 each. |

### Key Observations

- **Everyone has shifted toward hybrid pricing**: a flat subscription base + usage-based overage. Pure flat-rate is dead for AI tools because heavy users on frontier models are unprofitable.
- **Cursor's June 2025 pivot** is the canary: they replaced fixed "fast requests" with credit pools tied to actual API costs. Community backlash was real but they crossed $1B ARR anyway.
- **Devin's ACU model** is closest to Demiurge's use case — both are agentic tools that run multi-turn, compute-heavy sessions (not just inline completions).
- **Free tiers exist everywhere** but are heavily throttled. They serve as acquisition funnels, not revenue centers.

---

## 2. Recommended Pricing Model for Demiurge

### Recommendation: Subscription Base + Usage Credits (Hybrid)

This is the model that has converged across the industry for good reason — it balances predictable revenue with fair cost allocation.

### Proposed Tiers

| Tier | Price | Included | Target |
|------|-------|----------|--------|
| **Free / Trial** | $0 for 14 days | 5 runs, 50k agent tokens | Evaluation. No credit card required. |
| **Starter** | $29/mo ($24/mo annual) | 50 runs/mo, 500k agent tokens | Solo devs, side projects |
| **Pro** | $79/mo ($66/mo annual) | 200 runs/mo, 2M agent tokens | Professional individual devs |
| **Team** | $49/user/mo ($41/user/mo annual) | 150 runs/user/mo, 1.5M tokens/user, shared workspace | Small teams (2–20) |
| **Enterprise** | Custom | Unlimited runs, custom token pools, SSO, audit logs | Orgs 20+ |

### Overage Pricing
- Extra runs: $0.50/run
- Extra agent tokens: $5/1M tokens (roughly 1.25× API pass-through cost)

### Why This Structure

- **"Runs" are your natural unit of value.** A Demiurge run is a discrete, high-value operation (boot → verify → repair). Users intuitively understand "I get N runs per month."
- **Token budget per run is already tracked** in your codebase (`ExecutionBudgetDefaults`), so metering is nearly free to implement.
- **$29 entry point** is aggressive enough to compete with Cursor Pro ($20) while reflecting the higher per-session value Demiurge delivers (full orchestration, not just completions).
- **Annual discount (17%)** is standard and improves cash flow.

### What NOT to Do

- **Don't charge purely by the minute/hour** like Devin's ACU. It's opaque and anxiety-inducing. Runs are a crisper unit.
- **Don't do perpetual licenses.** The product depends on ongoing LLM API costs — perpetual doesn't work economically.
- **Don't charge only for the "platform" and make users figure out API costs separately** (at least not at launch — see §3).

---

## 3. API Key Strategy: BYOK vs Managed

This is the single most consequential architectural decision. Here's the full analysis:

### Option A: Managed Key (Demiurge Proxies All LLM Calls)

**How it works:** Demiurge holds the Anthropic API key. Users never see it. LLM costs are baked into the subscription/credit price.

| Pros | Cons |
|------|------|
| Frictionless onboarding — no API key setup | **Anthropic ToS risk** — their updated terms restrict "subscription auth for third-party use" and redistribution. Demiurge is NOT a pure wrapper (it has substantial proprietary value: orchestrator, verifiers, worktree isolation, etc.) but it's in the "medium risk" zone. |
| Predictable user experience | You absorb LLM cost volatility and margin risk |
| You can optimize prompts/caching without users seeing raw token costs | Requires Anthropic partnership/reseller agreement at scale |
| Simpler client architecture | Higher price point required to cover costs |

### Option B: BYOK (Bring Your Own Key)

**How it works:** Users paste their own Anthropic API key into Demiurge settings. LLM calls are made with their key. You charge only for the platform.

| Pros | Cons |
|------|------|
| **Zero Anthropic ToS risk** — each user has their own billing relationship | Onboarding friction — users must create Anthropic account, generate key, set up billing |
| Lower price point (no LLM cost markup) attracts price-sensitive users | Users see surprise API bills separately (bad UX) |
| No margin risk on LLM costs | You lose ability to negotiate volume discounts |
| Users can choose providers if you support multi-provider | Key management complexity (rotation, security) |

### Option C: Hybrid — BYOK Default + Optional Managed Credits (★ RECOMMENDED)

**How it works:**
1. **Default: BYOK.** Users provide their own Anthropic (or OpenAI, etc.) API key. Platform subscription covers orchestration, verification, and all non-LLM features.
2. **Optional: Managed credits.** For users who don't want to deal with API keys, offer "Demiurge Credits" that can be purchased and consumed. You proxy the LLM calls using your key, charged at a ~25-40% markup over raw API cost.

| Why this wins |
|--------------|
| Eliminates Anthropic ToS risk for the default path |
| Frictionless option exists for users who want it |
| Lower base subscription attracts more users |
| "Credits" revenue is pure upside, not structural dependency |
| Mirrors what Cursor/Windsurf have converged to — BYOK available on all paid plans, managed credits included |

### Anthropic ToS Reality Check

Demiurge is **not** a wrapper — it's a substantial value-add product (orchestrator, verifiers, worktree isolation, repair loop, desktop app, browser automation). The LLM call is one step in a multi-stage pipeline. This puts you in the "low-to-medium risk" zone under Anthropic's terms. However:

- **BYOK as default eliminates the risk entirely.**
- If you want to offer managed credits, consider reaching out to Anthropic's partnerships team for a formal reseller/distribution agreement once you have meaningful volume (>$10K/mo API spend).
- Supporting multiple providers (OpenAI, Gemini, local models via Ollama) further reduces single-vendor exposure.

---

## 4. Authentication, Licensing & Trial Gating

### Architecture: Cloud Auth + License Validation

The desktop app and CLI need to gate access to paying customers. Here's the recommended stack:

```
User signs up on demiurge.dev (marketing site)
  → Creates account (Clerk auth OR Supabase Auth)
  → Stripe checkout → subscription created
  → License key generated (Keygen.sh OR custom)
  → User pastes license key into CLI / desktop app
  → App validates license on startup (API call to your backend)
  → Offline grace period (72h cached validation)
```

### Recommended Auth Stack

| Component | Recommendation | Why |
|-----------|---------------|-----|
| **User auth** | **Clerk** ($0 up to 10k MAU, then $0.02/MAU) | Best React SDK, works in Tauri via JWT. Handles OAuth, magic links, MFA. Drop-in `<SignIn/>` component. Has a Tauri community plugin. |
| **Billing** | **Stripe** (2.9% + $0.30/txn) OR **Polar** (4% + $0.40/txn, MoR) | Stripe is the standard, but requires you to handle sales tax. Polar acts as Merchant of Record (handles VAT/GST globally) — much less legal overhead for a solo founder. |
| **License gating** | **Keygen.sh** (free up to 100 ALU, $49/mo for 500 ALU) | Purpose-built for desktop app licensing. Supports timed trials, subscription licenses, machine fingerprinting, offline validation. REST API + SDKs for Rust/Java/TS. |

### How the Trial Works

1. User downloads Demiurge (no account required to download).
2. On first launch, prompted to "Start Free Trial" or "Enter License Key."
3. "Start Free Trial" → creates account via Clerk (email or GitHub OAuth) → Keygen issues a trial license (14-day duration, 1 machine fingerprint, 5 runs / 50k tokens limit).
4. App validates license on each `demiurge run` invocation. If expired/exhausted → "Trial expired. Upgrade at demiurge.dev/pricing."
5. Paid conversion → Stripe/Polar checkout → webhook to Keygen → trial license transferred to paid policy → expiry reset.

### Offline / Degraded Mode

- License validation is cached locally for 72 hours (Keygen supports signed license files for offline validation).
- If the user is offline beyond 72h, Demiurge shows a warning but allows read-only operations (status, inspect-run, open-artifact). New runs are blocked.

### CLI-Specific Auth Flow

```bash
$ demiurge login
# Opens browser → Clerk auth page → OAuth callback → JWT stored at ~/.demiurge/credentials
# JWT contains Keygen license claim → validated on each run

$ demiurge logout
# Clears stored credentials

$ demiurge login --license-key <KEY>
# Direct license key entry (no browser needed, for CI/headless environments)
```

### Desktop App Auth Flow

- Tauri app shows a Clerk-powered sign-in webview on first launch.
- JWT stored via `tauri-plugin-store` (encrypted on-disk).
- Sidecar process receives JWT via IPC → validates with Keygen on startup.

---

## 5. Distribution & Delivery

### Desktop App (Tauri)

Demiurge is already a Tauri v2 app. Here's the distribution plan:

#### Code Signing (REQUIRED for trust)

| Platform | Requirement | Cost |
|----------|-------------|------|
| **macOS** | Apple Developer ID + Notarization | $99/year (Apple Developer Program) |
| **Windows** | Authenticode / EV Code Signing Certificate | $200-500/year (DigiCert, Sectigo). EV cert eliminates SmartScreen warnings entirely. |
| **Linux** | Not required (AppImage is unsigned by convention) | $0 |

**Without code signing, macOS Gatekeeper will block installation and Windows SmartScreen will show scary warnings.** This is non-negotiable for a paid product.

#### Auto-Update

Tauri v2 has built-in auto-update via `tauri-plugin-updater`:
- Uses public/private key pair for update signature verification.
- Update manifest (JSON) hosted on your update server.
- **Recommended:** Use **CrabNebula Cloud** (official Tauri partner) for update hosting, or self-host via **GitHub Releases**.
- CrabNebula provides: CDN-backed downloads, release channels (stable/beta/canary), download buttons for your website, analytics.

#### Distribution Channels

| Channel | How | Priority |
|---------|-----|----------|
| **demiurge.dev/download** | Direct download links on marketing site (DMG for macOS, MSI/NSIS for Windows, AppImage for Linux). Powered by CrabNebula or GitHub Releases. | P0 — primary |
| **Homebrew Cask** | `brew install --cask demiurge` — submit to homebrew-cask repo or host your own tap | P1 — macOS devs expect this |
| **GitHub Releases** | Attach binaries to tagged releases | P0 — also serves as update backend |
| **AUR** | Arch Linux user repository | P2 — nice to have |

### CLI Distribution

The CLI is currently a Bazel-built fat JAR (JVM). For distribution:

| Channel | How | Priority |
|---------|-----|----------|
| **Bundled with desktop app** | Already done — sidecar JAR inside Tauri bundle | P0 |
| **Homebrew formula** | `brew install demiurge` — shell launcher + JAR, or compile to native with GraalVM | P1 |
| **Direct download** | Versioned tarballs on GitHub Releases / demiurge.dev | P0 |
| **npm** | `npx demiurge` (thin wrapper that downloads the JAR) | P2 — low priority, adds Node dependency |

#### Future: Native Binary via GraalVM

The fat JAR requires a JVM. For a better CLI experience, consider compiling to a native binary via GraalVM Native Image. This eliminates the JVM dependency and produces a single ~50MB binary. This is a significant effort but dramatically improves CLI distribution and startup time.

---

## 6. Backend Infrastructure

You need a lightweight cloud backend for auth, licensing, billing, and telemetry. Here's the minimal viable stack:

### Option A: Fully Managed (★ RECOMMENDED for Solo Founder)

| Service | Provider | Cost |
|---------|----------|------|
| **Auth** | Clerk | Free up to 10k MAU |
| **Billing** | Polar (MoR) or Stripe | 4% + $0.40/txn (Polar) or 2.9% + $0.30 (Stripe) |
| **Licensing** | Keygen.sh Cloud | Free up to 100 users, $49/mo for 500 |
| **Marketing site** | Vercel (Next.js) | Free for hobby, $20/mo for Pro |
| **Database** (usage tracking, telemetry) | Supabase (Postgres) | Free up to 500MB, $25/mo for Pro |
| **Email** (transactional) | Resend | Free up to 3k/mo |

**Total cost at launch: ~$0-50/month** (most services are free at low volume).

### Option B: Self-Hosted (More Control, More Ops)

| Service | Provider | Cost |
|---------|----------|------|
| **Auth** | Supabase Auth (self-hosted or cloud) | Free tier generous |
| **Billing** | Stripe (direct) | 2.9% + $0.30/txn + you handle tax |
| **Licensing** | Keygen CE (self-hosted, open source) | Free (you host it) |
| **Marketing site** | Vercel or Cloudflare Pages | Free-$20/mo |
| **Database** | Supabase or PlanetScale | Free tier |

### Telemetry / Analytics

Collect (with user consent, opt-out available):
- Run count, duration, success rate
- Repair attempt count, agent token usage
- Verifier types used
- Desktop vs CLI usage split
- Error rates

Use **PostHog** (open source, free up to 1M events/mo) or Supabase directly.

---

## 7. Marketing Website

### What You Need: demiurge.dev

Every competitive AI dev tool (Cursor, Devin, Windsurf, Copilot) follows the same landing page formula:

#### Required Sections

1. **Hero** — Bold headline + one-line value prop + CTA ("Download Free" / "Start Trial") + hero screenshot/video of the desktop app
2. **Product demo** — Embedded video or animated GIF showing a full run: boot → verify → repair → success
3. **Features grid** — 6-8 feature cards (Verifier-first, Agentic repair, Browser automation, Desktop + CLI, Git isolation, Build mode, etc.)
4. **How it works** — 3-step visual (Configure → Run → Ship)
5. **Pricing** — Clean tier comparison table with CTA buttons
6. **Testimonials / Social proof** — Early user quotes, GitHub stars, "trusted by X developers"
7. **Docs link** — /docs powered by your existing markdown docs
8. **Download** — Platform-specific download buttons (auto-detect OS)
9. **Footer** — Links, Discord/GitHub community, legal

#### Tech Stack for the Site

| Choice | Why |
|--------|-----|
| **Next.js 15 + App Router** | Industry standard for marketing sites. SSR for SEO. |
| **Tailwind CSS v4** | Already used in desktop app — consistent DX |
| **Vercel** | Zero-config deployment, edge CDN, analytics |
| **MDX** | For docs pages (render your existing .md docs) |
| **Framer Motion** | Already a desktop dependency — use for page animations |

#### Domain

- Primary: `demiurge.dev` (if available) or `getdemiurge.com` or `usedemiurge.com`
- Docs: `docs.demiurge.dev` or `/docs` subdirectory

---

## 8. License & Open Source Considerations

### Current State

Demiurge is MIT licensed. This is maximally permissive — anyone can fork, modify, and redistribute it commercially.

### The Problem

If you're charging for Demiurge, MIT licensing means anyone can take your code, remove the license checks, and redistribute a free version. This is fine if your moat is the cloud services (auth, updates, managed credits), but it weakens your position.

### Options

| License | Implication |
|---------|-------------|
| **Stay MIT** | Fully open. Revenue comes from cloud services + managed credits + support. Works if you view the product as "open core." |
| **BSL (Business Source License)** | Source-available but not commercially redistributable. Converts to open source after a time delay (e.g., 4 years). Used by MariaDB, Sentry, CockroachDB, HashiCorp. |
| **FCL (Fair Core License)** | Source-available, free for personal/commercial use, but can't compete with the licensor. Used by Keygen itself. |
| **SSPL (Server Side Public License)** | MongoDB-style. Discourages cloud providers from offering your software as a service. |
| **Proprietary + Source Available** | Source on GitHub for transparency, but all rights reserved. |

### Recommendation

**Switch to BSL 1.1 (Business Source License)** before going to market.

- Source code stays publicly visible on GitHub (builds trust, enables contributions).
- Users can use it freely for internal purposes.
- Competitors cannot redistribute or offer it as a service.
- After 4 years, each version converts to Apache 2.0 (rewards patience, signals good faith).
- This is the model HashiCorp, Sentry, CockroachDB, and many other VC-backed infra companies have adopted.

**Action item:** Change LICENSE before any public launch. This is a one-time decision that's much harder to change later.

---

## 9. Implementation Phases

### Phase 1: Foundation (2-3 weeks)

- [ ] Switch license from MIT to BSL 1.1
- [ ] Set up Clerk account + configure auth (email + GitHub OAuth)
- [ ] Set up Stripe or Polar account + create subscription products
- [ ] Set up Keygen.sh account + create trial policy + paid policy
- [ ] Implement `demiurge login` / `demiurge logout` CLI commands
- [ ] Add license validation check to `RunOrchestrator` (before run starts)
- [ ] Add license key storage to desktop app (Tauri plugin-store)
- [ ] Add BYOK API key configuration (`demiurge config set api-key <KEY>`)

### Phase 2: Distribution (1-2 weeks)

- [ ] Enroll in Apple Developer Program ($99/year)
- [ ] Purchase Windows code signing certificate (~$300/year)
- [ ] Set up GitHub Actions CI/CD for multi-platform builds
- [ ] Configure Tauri code signing + notarization in CI
- [ ] Set up auto-update (tauri-plugin-updater + GitHub Releases or CrabNebula)
- [ ] Create Homebrew cask formula
- [ ] Create Homebrew formula for CLI

### Phase 3: Marketing Site (1-2 weeks)

- [ ] Register domain (demiurge.dev or alternative)
- [ ] Build Next.js marketing site (hero, features, pricing, download, docs)
- [ ] Integrate Clerk sign-up flow
- [ ] Integrate Stripe/Polar checkout
- [ ] Deploy to Vercel
- [ ] Set up Resend for transactional email (welcome, trial expiring, receipt)

### Phase 4: Telemetry & Polish (1 week)

- [ ] Add opt-in anonymous telemetry (PostHog or Supabase)
- [ ] Add usage tracking (run count, token consumption per user)
- [ ] Implement trial expiration warnings in CLI + desktop
- [ ] Add "Upgrade" prompts when limits are reached
- [ ] Set up customer support channel (Discord + email)

### Phase 5: Launch

- [ ] Product Hunt launch
- [ ] Hacker News Show HN
- [ ] Twitter/X announcement
- [ ] r/programming, r/webdev posts
- [ ] Dev.to / Hashnode launch article

---

## 10. Cost Estimates

### Fixed Costs (Annual)

| Item | Cost |
|------|------|
| Apple Developer Program | $99/year |
| Windows Code Signing (EV cert) | $300-500/year |
| Domain name | $12-40/year |
| **Total fixed** | **~$450/year** |

### Variable Costs (Monthly, at Scale)

| Item | At 100 users | At 1,000 users | At 10,000 users |
|------|-------------|----------------|-----------------|
| Clerk (auth) | $0 | $0 | $0 (under 10k MAU) |
| Keygen (licensing) | $0 (free tier) | $49/mo | $149/mo |
| Vercel (site) | $0-20/mo | $20/mo | $20/mo |
| Supabase (DB) | $0 | $25/mo | $25/mo |
| Stripe fees (on revenue) | 2.9% + $0.30/txn | same | same |
| PostHog (analytics) | $0 | $0 | $0 (under 1M events) |
| **Total infra** | **~$0-20/mo** | **~$100/mo** | **~$200/mo** |

### Revenue Projections (Conservative)

| Metric | At 100 paying | At 1,000 paying | At 10,000 paying |
|--------|--------------|-----------------|-------------------|
| Avg revenue/user (Starter/Pro mix) | $45/mo | $45/mo | $45/mo |
| Monthly revenue | $4,500 | $45,000 | $450,000 |
| Annual revenue | $54,000 | $540,000 | $5,400,000 |
| Infra cost % | <1% | <0.3% | <0.05% |

**Margins are excellent** because the product runs locally — you're not hosting compute. The cloud backend is just auth + licensing + billing metadata.

---

## Summary: The Critical Path

1. **BYOK default + optional managed credits** — safest API key strategy, best unit economics, Anthropic ToS compliant.
2. **Subscription + usage credits** — industry-standard hybrid pricing that Cursor, Copilot, and Devin have all converged on.
3. **Clerk + Stripe/Polar + Keygen.sh** — the three-service stack that handles auth, billing, and licensing without you building any of it.
4. **Tauri auto-update + code signing + CrabNebula/GitHub Releases** — professional distribution that matches user expectations.
5. **Next.js marketing site on Vercel** — fast to build, free to host, SEO-friendly.
6. **BSL license** — protects commercial value while keeping source visible.

Total time to market: **6-8 weeks** from decision to launch, assuming the product itself is ready.
