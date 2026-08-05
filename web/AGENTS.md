# AGENTS.md — web/ (demiurge.dev)

## Purpose

Next.js 15 App Router site deployed on Vercel: marketing pages, docs/blog, and the
licensing/billing backend the CLI and desktop app depend on — Clerk (auth), Stripe
(subscriptions), Keygen (license keys), Resend (email), PostHog (analytics).

## Key Files

| File | Lines | Purpose |
|---|---|---|
| `src/lib/keygen.ts` | ~300 | Keygen API client: validate/activate licenses, usage counters |
| `src/lib/device-code-store.ts` | ~150 | In-memory device-auth code store (10-min TTL; MVP — swap for KV/Redis in prod) |
| `src/lib/email.ts` | ~150 | Resend transactional emails (logs to console if no API key) |
| `src/lib/docs.ts` | ~50 | Reads `../docs/*.md` at build time into /docs pages |
| `src/middleware.ts` | ~50 | Clerk route protection; no-ops entirely if Clerk key unset |
| `src/lib/env.ts` | ~50 | Typed env access — required vars throw lazily on first read |
| `src/lib/constants.ts` | ~50 | Plan tiers, limits, pricing metadata |
| `src/lib/clerk-helpers.ts` | ~50 | Clerk user metadata helpers |
| `src/lib/stripe-helpers.ts` | ~50 | Stripe client + plan/price mapping |

## Route Surface

- **Pages**: `/`, `/pricing`, `/download`, `/docs/[slug]`, `/blog`, `/legal/*`,
  `/sign-in`, `/sign-up`, `/activate`, `/auth-callback`, `/account`
- **API**: `api/auth/{device-code,device-poll,device-authorize}` (CLI/desktop device
  flow), `api/license/{validate,activate,increment-usage,report-tokens}` (license-key
  auth, called by the Scala `license` module), `api/checkout/create-session`,
  `api/user/{subscription,portal,usage}`, `api/webhooks/{clerk,stripe}` (svix/Stripe
  signature-verified)
- Public vs Clerk-protected routes are enumerated in `src/middleware.ts` — update it
  when adding any route.

## Build & Test

```bash
npm install
npm run dev            # next dev
npm test               # vitest run — 5 suites in src/lib/__tests__/
npm run lint           # next lint
npm run build          # next build (also type-checks)
```

## Code Conventions

- `@/` alias → `src/` (both tsconfig and `vitest.config.ts`).
- Route handlers live at `src/app/api/**/route.ts`; shared logic goes in `src/lib/`
  (which is what gets unit-tested — handlers stay thin).
- Env access only through `src/lib/env.ts`; never `process.env.X` inline.
- Optional integrations degrade gracefully: no Clerk key → middleware passthrough,
  no Resend key → emails logged, no PostHog key → no tracking. Preserve this
  pattern when adding integrations.

## Critical Gotchas

1. **Nothing here runs in CI.** `ci.yml` never touches `web/`. Run `npm test` and
   `npm run build` yourself before merging.
2. **`src/lib/docs.ts` reads the repo's `docs/` directory at build time**
   (`path.join(process.cwd(), '..', 'docs')`). Renaming a file in `docs/` silently
   drops that page; `PUBLISHED_DOCS` also lists getting-started and troubleshooting
   pages whose markdown files don't exist yet.
3. **Auth is silently disabled without Clerk keys** — `middleware.ts` returns
   `NextResponse.next()` if `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY` doesn't start with
   `pk_`. Don't mistake a locally-working protected route for correct auth.
4. **`device-code-store.ts` is per-process memory** — device auth breaks across
   serverless instances/restarts; a code is only valid on the instance that minted it.
5. **Webhook handlers must verify signatures** (svix for Clerk, `stripe.webhooks`
   for Stripe) before reading payloads — follow the existing handlers.
6. **Secrets discipline**: `.env` is gitignored; every needed var is documented in
   `.env.example`. Never commit `sk_`/`whsec_`/`re_` values.

## Source Layout

```
web/
├── src/
│   ├── app/               # App Router pages + api/ route handlers
│   ├── components/        # landing, pricing, download, docs, layout, ui, account
│   ├── content/legal/     # privacy.md, terms.md
│   ├── lib/               # all business logic (11 files)
│   │   └── __tests__/     # 5 vitest suites
│   └── middleware.ts      # Clerk route protection
├── public/                # install.sh, robots.txt
├── vitest.config.ts
└── vercel.json
```
