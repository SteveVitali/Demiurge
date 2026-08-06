# demiurge.dev

The Demiurge marketing site and cloud backend: a Next.js 15 (App Router)
application deployed on Vercel. Besides the public pages (landing, pricing,
download, docs, blog), it hosts the licensing and billing API that the CLI
and desktop app depend on — Clerk (auth), Stripe (subscriptions), Keygen
(license keys), Resend (transactional email), and PostHog (analytics).

## Development

```bash
npm install
npm run dev      # Next.js dev server
npm test         # vitest run — suites in src/lib/__tests__/
npm run lint     # next lint
npm run build    # production build (also type-checks)
```

> **Note:** nothing in `web/` runs in CI. Run `npm test` and `npm run build`
> locally before merging changes here.

## Environment

Copy `.env.example` to `.env` and fill in the keys you need. Optional
integrations degrade gracefully: without a Clerk key auth is disabled,
without a Resend key emails are logged to the console, without a PostHog key
nothing is tracked. Never commit `.env` or any secret key material.

## Structure

- `src/app/` — App Router pages and `api/` route handlers
- `src/lib/` — business logic (license validation, billing helpers, email);
  this is what the vitest suites cover
- `src/components/` — landing, pricing, download, docs, layout, ui, account
- `src/middleware.ts` — Clerk route protection
- `src/content/legal/` — privacy policy and terms markdown

## Docs pipeline

`src/lib/docs.ts` reads the repository's `docs/*.md` files **at build time**
and publishes the pages listed in `PUBLISHED_DOCS` under `/docs/[slug]`.
Renaming or moving a file in `docs/` silently drops that page from the site.
