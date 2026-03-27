# Spec 04: Marketing Website

> **Parent document:** [plan-go-to-market.md](./plan-go-to-market.md)
> **Phase:** 4 of 5 — Depends on Spec 01 (shares the `web/` directory and Clerk/Stripe config).
> **Estimated effort:** 4–5 days.

---

## 1. Overview

This spec defines the public-facing marketing website at `demiurge.dev`. It is a **Next.js 15 App Router** application that shares the `web/` directory with the cloud backend API routes from Spec 01. The website serves as:

- **Landing page** — product marketing, hero, features, demo
- **Pricing page** — tier comparison with Stripe Checkout integration
- **Download page** — platform-specific installers with OS auto-detection
- **Documentation** — rendered from the existing `docs/*.md` files
- **Auth pages** — Clerk sign-in/sign-up, device code activation page
- **Account pages** — billing management, license key display

---

## 2. Sitemap

```
demiurge.dev/
├── /                     # Landing page (hero, features, how it works, CTA)
├── /pricing              # Pricing tiers + Stripe Checkout
├── /download             # Platform-specific download links + install instructions
├── /docs                 # Documentation index
│   ├── /docs/[slug]      # Individual doc pages (architecture, cli-reference, etc.)
├── /sign-in              # Clerk sign-in (catch-all)
├── /sign-up              # Clerk sign-up (catch-all)
├── /activate             # Device code activation page (for CLI login flow)
├── /auth-callback        # OAuth callback → deep link to desktop app
├── /account              # Dashboard: license key, plan, usage (requires auth)
│   └── /account/billing  # Redirect to Stripe Customer Portal
├── /blog                 # (Future — placeholder route)
├── /legal/terms          # Terms of Service
├── /legal/privacy        # Privacy Policy
└── /api/...              # API routes (Spec 01)
```

---

## 3. Project Structure

All files live in the existing `web/` directory (created in Spec 01). The marketing pages extend it:

```
web/src/
├── app/
│   ├── layout.tsx                    # Root layout: ClerkProvider, fonts, analytics
│   ├── page.tsx                      # Landing page
│   ├── pricing/
│   │   └── page.tsx                  # Pricing page
│   ├── download/
│   │   └── page.tsx                  # Download page
│   ├── docs/
│   │   ├── page.tsx                  # Docs index
│   │   └── [slug]/
│   │       └── page.tsx              # Individual doc page (SSG from markdown)
│   ├── activate/
│   │   └── page.tsx                  # Device code entry page (CLI login flow)
│   ├── account/
│   │   ├── page.tsx                  # Account dashboard (license, plan, usage)
│   │   └── billing/
│   │       └── page.tsx              # Redirect to Stripe portal
│   ├── auth-callback/
│   │   └── page.tsx                  # Post-auth redirect (deep link to desktop)
│   ├── sign-in/
│   │   └── [[...sign-in]]/
│   │       └── page.tsx              # Clerk sign-in
│   ├── sign-up/
│   │   └── [[...sign-up]]/
│   │       └── page.tsx              # Clerk sign-up
│   ├── legal/
│   │   ├── terms/page.tsx
│   │   └── privacy/page.tsx
│   ├── api/                          # (Spec 01 — already exists)
│   └── globals.css                   # Tailwind global styles
├── components/
│   ├── layout/
│   │   ├── Navbar.tsx                # Top nav: logo, links, sign-in/download CTA
│   │   ├── Footer.tsx                # Links, legal, social, copyright
│   │   └── MobileMenu.tsx            # Hamburger menu for mobile
│   ├── landing/
│   │   ├── Hero.tsx                  # Hero section with headline + CTA
│   │   ├── DemoVideo.tsx             # Embedded product demo video/GIF
│   │   ├── FeaturesGrid.tsx          # 6-8 feature cards
│   │   ├── HowItWorks.tsx            # 3-step visual explainer
│   │   ├── Testimonials.tsx          # Social proof section
│   │   └── CTABanner.tsx             # Bottom CTA banner
│   ├── pricing/
│   │   ├── PricingTable.tsx          # Tier comparison table
│   │   ├── PricingCard.tsx           # Individual plan card
│   │   ├── BillingToggle.tsx         # Monthly / Annual toggle
│   │   └── CheckoutButton.tsx        # Stripe Checkout trigger
│   ├── download/
│   │   ├── DownloadCards.tsx          # OS-specific download cards
│   │   ├── PlatformDetector.tsx       # Auto-detect user's OS
│   │   └── InstallInstructions.tsx    # CLI install instructions
│   ├── docs/
│   │   ├── DocsSidebar.tsx           # Sidebar navigation
│   │   ├── DocsContent.tsx           # Rendered markdown content
│   │   └── TableOfContents.tsx        # Right-side TOC
│   └── account/
│       ├── LicenseKeyCard.tsx         # Displays + copy license key
│       ├── UsageCard.tsx              # Usage bar (runs used / max)
│       └── PlanCard.tsx               # Current plan + upgrade CTA
├── lib/
│   ├── docs.ts                       # Load + parse markdown docs from repo
│   ├── github-releases.ts            # Fetch latest release info from GitHub API
│   ├── stripe-checkout.ts            # Create Stripe Checkout sessions
│   └── constants.ts                  # (Spec 01 — extended)
└── content/
    └── legal/
        ├── terms.md                  # Terms of Service (markdown)
        └── privacy.md               # Privacy Policy (markdown)
```

---

## 4. Dependencies

Add to `web/package.json`:

```json
{
  "dependencies": {
    "next": "^15.0.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "@clerk/nextjs": "^6.0.0",
    "stripe": "^17.0.0",
    "svix": "^1.40.0",
    "@tailwindcss/typography": "^0.5.0",
    "tailwindcss": "^4.0.0",
    "framer-motion": "^11.15.0",
    "lucide-react": "^0.468.0",
    "react-markdown": "^10.1.0",
    "remark-gfm": "^4.0.1",
    "rehype-highlight": "^7.0.0",
    "gray-matter": "^4.0.3",
    "clsx": "^2.1.1"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "@types/react": "^19.0.0",
    "typescript": "^5.7.0",
    "@tailwindcss/vite": "^4.0.0"
  }
}
```

---

## 5. Page Specifications

### 5.1 Landing Page (`/`)

The landing page follows the standard AI dev tool formula observed across Cursor, Devin, Windsurf, and Copilot sites.

#### Sections (top to bottom)

**1. Hero**
- Large bold headline: **"Ship verified software with AI agents"** (or similar)
- Subheadline: "Demiurge automatically boots, verifies, and repairs your web applications. Stop debugging — start shipping."
- Two CTA buttons: **"Download Free"** (primary, links to /download) and **"View Pricing"** (secondary, links to /pricing)
- Hero visual: Screenshot or short autoplay video of the desktop app showing a successful run pipeline

**2. Logo bar** (future — "Trusted by engineers at...")

**3. Demo Video**
- Embedded video player (or animated GIF sequence) showing:
  1. `demiurge run` in terminal → boot → verify → repair → success
  2. Desktop app with live pipeline stepper
- Approximately 30-60 seconds

**4. Features Grid**
- 6-8 cards in a responsive grid. Each card: icon (Lucide), title, 1-2 sentence description.

| Feature | Description |
|---------|-------------|
| **Verifier-First** | Every task is backed by executable verifiers — HTTP, TCP, browser, API contracts, state assertions. |
| **Agentic Repair** | When verification fails, Claude Code agents autonomously fix your code with multi-turn reasoning. |
| **Browser Automation** | Full Playwright-powered browser verification with visual regression, viewport testing, and screenshot evidence. |
| **Git Isolation** | Every run operates in a dedicated git worktree. Your working directory is never modified. |
| **Desktop + CLI** | Native desktop app with real-time observability, or a powerful CLI for headless environments. |
| **Build Mode** | Describe a feature in natural language → Demiurge generates, verifies, and repairs the code. |
| **BYOK** | Bring your own Anthropic or OpenAI API key. You control costs and model selection. |
| **Smart Init** | Point Demiurge at any repo — an AI agent generates the configuration automatically. |

**5. How It Works**
- Three-step horizontal or vertical layout:
  1. **Configure** — "Run `demiurge init --smart` or write a simple YAML manifest"
  2. **Run** — "Demiurge boots your services, runs verifiers, and detects issues"
  3. **Ship** — "AI agents fix failures. You review and merge."
- Each step has an illustration or icon

**6. CTA Banner**
- Dark background, centered text: "Ready to automate your last mile?"
- "Download for Free" button + "View on GitHub" secondary link

#### Design Notes
- Dark theme preferred (matches the desktop app aesthetic and developer tool conventions)
- Use `framer-motion` for scroll-triggered animations (fade-in, slide-up)
- Responsive: mobile-first, breakpoints at sm/md/lg/xl
- Font: Inter or similar clean sans-serif (system font stack fallback)

### 5.2 Pricing Page (`/pricing`)

**Components:**

**BillingToggle** — Monthly / Annual switch. Annual shows discounted price and a "Save 17%" badge.

**PricingTable** — 4 columns (or cards on mobile):

| | Trial | Starter | Pro | Team |
|---|---|---|---|---|
| **Price** | Free | $29/mo | $79/mo | $49/user/mo |
| **Annual** | — | $24/mo | $66/mo | $41/user/mo |
| **Runs** | 5 total | 50/mo | 200/mo | 150/user/mo |
| **Agent tokens** | 50K | 500K/mo | 2M/mo | 1.5M/user/mo |
| **Machines** | 1 | 2 | 3 | 5/user |
| **Agent repair** | ✓ | ✓ | ✓ | ✓ |
| **Build mode** | — | ✓ | ✓ | ✓ |
| **Browser verification** | — | ✓ | ✓ | ✓ |
| **Priority support** | — | — | ✓ | ✓ |
| **CTA** | "Start Free Trial" | "Get Starter" | "Get Pro" | "Get Team" |

**Enterprise callout:** Below the table, a "Need more?" section with "Contact us for custom plans with unlimited runs, SSO, and dedicated support."

**CheckoutButton behavior:**
- If user is signed in (Clerk) → create Stripe Checkout session → redirect to Stripe
- If user is NOT signed in → redirect to `/sign-up?redirect_url=/pricing` → after sign-up, redirect back

**Stripe Checkout session creation:**
```typescript
// POST /api/checkout/create-session (new API route)
import { auth, currentUser } from '@clerk/nextjs/server';
import Stripe from 'stripe';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new Response('Unauthorized', { status: 401 });

  const user = await currentUser();
  const { priceId, billingPeriod } = await req.json();

  const stripe = new Stripe(process.env.STRIPE_SECRET_KEY!);

  const session = await stripe.checkout.sessions.create({
    mode: 'subscription',
    line_items: [{ price: priceId, quantity: 1 }],
    success_url: `${process.env.NEXT_PUBLIC_APP_URL}/account?checkout=success`,
    cancel_url: `${process.env.NEXT_PUBLIC_APP_URL}/pricing?checkout=cancelled`,
    customer_email: user?.emailAddresses[0]?.emailAddress,
    metadata: {
      clerk_user_id: userId,
    },
    subscription_data: {
      metadata: {
        clerk_user_id: userId,
      },
    },
  });

  return Response.json({ url: session.url });
}
```

### 5.3 Download Page (`/download`)

**Platform auto-detection:** On page load, detect the user's OS via `navigator.userAgent` / `navigator.platform` and highlight the appropriate download card.

**Download Cards:**

| Platform | Primary Download | Secondary |
|----------|-----------------|-----------|
| **macOS (Apple Silicon)** | Download .dmg (ARM64) | Intel .dmg, Homebrew |
| **macOS (Intel)** | Download .dmg (x64) | ARM64 .dmg, Homebrew |
| **Windows** | Download Installer (.exe) | — |
| **Linux** | Download AppImage | .deb package |

**CLI Install Section:**
```
# macOS / Linux (Homebrew)
brew tap SteveVitali/demiurge
brew install demiurge

# macOS / Linux (direct)
curl -fsSL https://demiurge.dev/install.sh | bash

# Verify installation
demiurge doctor
```

**Implementation: `lib/github-releases.ts`**

Fetches the latest release from the GitHub API at build time (ISR with 1-hour revalidation):

```typescript
const GITHUB_REPO = 'SteveVitali/Demiurge';

export async function getLatestRelease() {
  const res = await fetch(
    `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`,
    { next: { revalidate: 3600 } }
  );
  const release = await res.json();

  return {
    version: release.tag_name,
    publishedAt: release.published_at,
    assets: release.assets.map((a: any) => ({
      name: a.name,
      url: a.browser_download_url,
      size: a.size,
    })),
  };
}
```

### 5.4 Documentation (`/docs` and `/docs/[slug]`)

**Data source:** The existing `docs/*.md` files in the Demiurge repo. At build time, these are read, parsed (front matter + markdown body), and rendered as static pages.

**`lib/docs.ts`:**

```typescript
import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';

const DOCS_DIR = path.join(process.cwd(), '..', 'docs');

// Files to include (exclude internal specs and plans)
const PUBLISHED_DOCS = [
  'architecture.md',
  'cli-reference.md',
  'configuration.md',
  'api-reference.md',
  'getting-started.md',
  'troubleshooting.md',
];

export interface DocPage {
  slug: string;
  title: string;
  content: string;
  order: number;
}

export function getAllDocs(): DocPage[] {
  return PUBLISHED_DOCS.map((filename, index) => {
    const filePath = path.join(DOCS_DIR, filename);
    const raw = fs.readFileSync(filePath, 'utf-8');
    const { content } = matter(raw);

    // Extract title from first # heading
    const titleMatch = content.match(/^#\s+(.+)$/m);
    const title = titleMatch ? titleMatch[1] : filename.replace('.md', '');

    return {
      slug: filename.replace('.md', ''),
      title,
      content,
      order: index,
    };
  });
}

export function getDocBySlug(slug: string): DocPage | undefined {
  return getAllDocs().find(doc => doc.slug === slug);
}
```

**Docs page layout:**
- Left sidebar: doc navigation (list of all doc pages)
- Center: rendered markdown (with `@tailwindcss/typography` prose classes)
- Right sidebar: auto-generated table of contents (from h2/h3 headings)
- Code blocks with syntax highlighting via `rehype-highlight`

**Static generation:**
```typescript
// app/docs/[slug]/page.tsx
export async function generateStaticParams() {
  const docs = getAllDocs();
  return docs.map(doc => ({ slug: doc.slug }));
}
```

### 5.5 Device Code Activation Page (`/activate`)

This page is used during the CLI `demiurge login` device code flow (Spec 02 §4.1).

**Layout:**
1. "Activate Demiurge CLI" heading
2. If not signed in → show Clerk sign-in component inline
3. If signed in → show text input: "Enter the code shown in your terminal"
4. User enters the 8-character code (XXXX-XXXX format)
5. Submit → POST to `/api/auth/device-confirm` (new route) which:
   a. Looks up the device code in the store
   b. Associates it with the signed-in Clerk user's license key
   c. Returns success
6. Show "✓ CLI activated! You can close this page." confirmation

**New API route: `POST /api/auth/device-confirm`**

```typescript
export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new Response('Unauthorized', { status: 401 });

  const { userCode } = await req.json();
  const user = await currentUser();

  // Look up device code by user_code
  const entry = deviceCodeStore.findByUserCode(userCode);
  if (!entry) return Response.json({ error: 'Invalid code' }, { status: 404 });
  if (entry.expiresAt < Date.now()) return Response.json({ error: 'Code expired' }, { status: 410 });

  // Associate with user
  entry.clerkUserId = userId;
  entry.licenseKey = user?.publicMetadata?.license_key;
  entry.planTier = user?.publicMetadata?.plan_tier;
  entry.userEmail = user?.emailAddresses[0]?.emailAddress;
  entry.status = 'authorized';

  return Response.json({ success: true });
}
```

### 5.6 Account Page (`/account`)

**Requires auth** (Clerk middleware protects this route).

**Sections:**

1. **License Key Card** — Displays the license key with a copy button. "Your license key: `DEMI-XXXX-XXXX-XXXX`"
2. **Plan Card** — Shows current plan tier, billing period, next renewal date. "Upgrade" button if on Trial/Starter.
3. **Usage Card** — Progress bar showing runs used vs max. "42 / 200 runs used this month."
4. **Manage Billing** — Button that creates a Stripe Customer Portal session and redirects.

### 5.7 Auth Callback Page (`/auth-callback`)

For desktop app OAuth flow. After Clerk sign-in completes, this page:

1. Reads the user's license key and plan tier from Clerk
2. Constructs a deep link: `demiurge://auth-callback?license_key=DEMI-XXXX&plan_tier=pro&email=user@example.com`
3. Redirects to the deep link (opens the Demiurge desktop app)
4. Shows fallback text: "If the app didn't open, copy your license key: DEMI-XXXX-XXXX-XXXX and paste it in the app."

```typescript
// app/auth-callback/page.tsx
import { currentUser } from '@clerk/nextjs/server';
import { redirect } from 'next/navigation';

export default async function AuthCallbackPage() {
  const user = await currentUser();
  if (!user) redirect('/sign-in?redirect_url=/auth-callback');

  const licenseKey = user.publicMetadata?.license_key as string;
  const planTier = user.publicMetadata?.plan_tier as string;
  const email = user.emailAddresses[0]?.emailAddress;

  const deepLink = `demiurge://auth-callback?license_key=${encodeURIComponent(licenseKey)}&plan_tier=${encodeURIComponent(planTier)}&email=${encodeURIComponent(email || '')}`;

  return (
    <AuthCallbackClient deepLink={deepLink} licenseKey={licenseKey} />
  );
}
```

Client component attempts `window.location.href = deepLink` on mount, with a fallback UI.

---

## 6. Root Layout (`app/layout.tsx`)

```typescript
import { ClerkProvider } from '@clerk/nextjs';
import { Inter } from 'next/font/google';
import { Navbar } from '@/components/layout/Navbar';
import { Footer } from '@/components/layout/Footer';
import './globals.css';

const inter = Inter({ subsets: ['latin'] });

export const metadata = {
  title: 'Demiurge — AI-Powered Web Development Automation',
  description: 'Automatically boot, verify, and repair your web applications with AI agents. Desktop app + CLI.',
  openGraph: {
    title: 'Demiurge',
    description: 'Ship verified software with AI agents',
    url: 'https://demiurge.dev',
    siteName: 'Demiurge',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Demiurge',
    description: 'Ship verified software with AI agents',
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <ClerkProvider>
      <html lang="en" className="dark">
        <body className={inter.className}>
          <Navbar />
          <main>{children}</main>
          <Footer />
        </body>
      </html>
    </ClerkProvider>
  );
}
```

---

## 7. Navbar Component

```
┌──────────────────────────────────────────────────────────────┐
│  🔷 Demiurge     Pricing   Docs   Download   GitHub          │
│                                          [Sign In] [Download]│
└──────────────────────────────────────────────────────────────┘
```

- Logo + wordmark on left
- Navigation links: Pricing, Docs, Download, GitHub (external)
- Right side: If signed in → `<UserButton />` (Clerk). If not → "Sign In" link + "Download" primary button.
- Sticky on scroll, with backdrop blur
- Mobile: hamburger menu with `MobileMenu` slide-out

---

## 8. Clerk Middleware

Protect authenticated routes:

**File:** `web/src/middleware.ts`

```typescript
import { clerkMiddleware, createRouteMatcher } from '@clerk/nextjs/server';

const isProtectedRoute = createRouteMatcher([
  '/account(.*)',
]);

export default clerkMiddleware(async (auth, req) => {
  if (isProtectedRoute(req)) {
    await auth.protect();
  }
});

export const config = {
  matcher: [
    '/((?!_next|[^?]*\\.(?:html?|css|js(?!on)|jpe?g|webp|png|gif|svg|ttf|woff2?|ico|csv|docx?|xlsx?|zip|webmanifest)).*)',
    '/(api|trpc)(.*)',
  ],
};
```

---

## 9. Environment Variables (Additional)

Add to `.env.example` (on top of Spec 01 vars):

```bash
# GitHub (for release fetching)
GITHUB_TOKEN=ghp_...  # Optional, increases API rate limit for release fetching

# Site
NEXT_PUBLIC_APP_URL=https://demiurge.dev
NEXT_PUBLIC_GITHUB_REPO=SteveVitali/Demiurge
```

---

## 10. SEO & Performance

- **Static generation** for landing, pricing, docs, legal pages (fastest possible load)
- **ISR (Incremental Static Regeneration)** for download page (revalidate every hour to pick up new releases)
- **Dynamic** for account pages, API routes, auth pages
- **OpenGraph images:** Generate OG images for each page using `next/og` or static images
- **Sitemap:** Auto-generate `sitemap.xml` using `next-sitemap` or manual generation
- **robots.txt:** Allow all public pages, disallow `/api/`, `/account/`

---

## 11. Design System

To maintain consistency with the desktop app:

| Token | Value |
|-------|-------|
| **Primary color** | `#6366f1` (Indigo 500) — matches a developer tool aesthetic |
| **Background** | `#0a0a0a` (near-black) — dark theme |
| **Surface** | `#171717` (neutral-900) |
| **Border** | `#262626` (neutral-800) |
| **Text primary** | `#fafafa` (neutral-50) |
| **Text secondary** | `#a3a3a3` (neutral-400) |
| **Success** | `#22c55e` (green-500) |
| **Warning** | `#eab308` (yellow-500) |
| **Error** | `#ef4444` (red-500) |

Use Tailwind CSS v4 (same version as the desktop app). Dark theme only for v1 — no light mode toggle needed.

---

## 12. Install Script (`public/install.sh`)

A convenience script hosted at `demiurge.dev/install.sh` for CLI-only installation:

```bash
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
```

---

## 13. Changes to Existing Files

| File | Change |
|------|--------|
| `web/package.json` | Add Tailwind, framer-motion, lucide-react, react-markdown, gray-matter, rehype-highlight |
| `web/src/app/layout.tsx` | Full rewrite with ClerkProvider, Navbar, Footer, dark theme |
| `web/src/app/page.tsx` | Full rewrite: landing page with Hero, Features, HowItWorks, CTA |
| `web/next.config.ts` | Add MDX support, image domains, redirects |

### New Files (All)

All files in §3 that don't overlap with Spec 01 are new.

### New API Route

| Route | Purpose |
|-------|---------|
| `POST /api/checkout/create-session` | Create Stripe Checkout session for pricing page |
| `POST /api/auth/device-confirm` | Confirm device code (activation page) |

---

## 14. Testing Plan

### 14.1 Visual Review

- Landing page renders correctly on desktop (1440px), tablet (768px), mobile (375px)
- Dark theme is consistent
- All links work (pricing, docs, download, sign-in)
- Animations are smooth (framer-motion)

### 14.2 Auth Flow Tests

- Sign up → verify trial license key appears on account page
- Sign in → redirected to previous page
- Device code activation: sign in on `/activate`, enter valid code → success
- Auth callback: after sign-in, deep link to `demiurge://` is triggered

### 14.3 Checkout Flow Tests

- Click "Get Starter" on pricing page (not signed in) → redirected to sign-up → after sign-up, redirected back to pricing
- Click "Get Starter" (signed in) → Stripe Checkout opens → complete test payment → redirected to `/account?checkout=success`
- Stripe webhook fires → license upgraded (verify in Keygen dashboard)

### 14.4 Download Page Tests

- Auto-detects correct OS on macOS, Windows, Linux
- Download links point to real GitHub Release assets
- Homebrew install command works: `brew tap SteveVitali/demiurge && brew install demiurge`

### 14.5 Docs Tests

- All published doc pages render correctly
- Code blocks have syntax highlighting
- Table of contents links scroll to correct headings
- Sidebar navigation works

### 14.6 Lighthouse Audit

Target scores:
- Performance: >90
- Accessibility: >95
- Best Practices: >95
- SEO: >95

---

## 15. Out of Scope

- Blog system (future)
- Team management pages (future)
- Enterprise contact form (future — use email link for now)
- Changelog page (link to GitHub releases for now)
- Usage metering display in account page detail (Spec 05)
- A/B testing, analytics deep integration
- i18n / localization
