# Spec 01: Cloud Backend & Auth Foundation

> **Parent document:** [plan-go-to-market.md](./plan-go-to-market.md)
> **Phase:** 1 of 5 — Must be completed before Specs 02, 04, and 05.
> **Depends on:** Nothing (foundation layer).
> **Estimated effort:** 3–4 days.

---

## 1. Overview

This spec defines the cloud backend that powers user authentication, subscription billing, and software license management for Demiurge. It is a **Next.js API-route backend** deployed to Vercel that serves as the glue between three managed services:

- **Clerk** — user authentication (sign-up, sign-in, OAuth, JWT)
- **Stripe** — subscription billing and payment processing
- **Keygen.sh** — software license issuance, validation, and usage tracking

The backend has **no database of its own**. All state lives in Clerk (users), Stripe (subscriptions, invoices), and Keygen (licenses, machines, usage). The backend's sole job is to handle webhooks that synchronize these three services and to expose a small API for the Demiurge desktop app and CLI to call.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Demiurge Cloud Backend                  │
│             (Next.js API Routes on Vercel)                │
│                                                           │
│  POST /api/webhooks/stripe    ← Stripe webhook events     │
│  POST /api/webhooks/clerk     ← Clerk webhook events      │
│  POST /api/auth/device-code   → Start device auth flow     │
│  GET  /api/auth/device-poll   → Poll device auth status    │
│  GET  /api/license/validate   → Validate a license key     │
│  POST /api/license/activate   → Activate a machine         │
│  GET  /api/user/subscription  → Get user's plan info       │
│  POST /api/user/portal        → Create Stripe portal link  │
└───────┬──────────────┬──────────────┬────────────────────┘
        │              │              │
   ┌────▼────┐   ┌─────▼─────┐  ┌────▼─────┐
   │  Clerk  │   │  Stripe   │  │ Keygen.sh│
   │  (Auth) │   │ (Billing) │  │(Licensing)│
   └─────────┘   └───────────┘  └──────────┘
```

### Data Flow: New User Sign-Up → Trial License

```
1. User signs up on demiurge.dev (Clerk <SignUp/> component)
2. Clerk fires `user.created` webhook → POST /api/webhooks/clerk
3. Webhook handler:
   a. Creates a Keygen user (email, Clerk user ID as metadata)
   b. Creates a Keygen trial license under the "trial" policy
   c. Stores the Keygen license key in Clerk user metadata
4. User sees their license key on the post-signup page
```

### Data Flow: Trial → Paid Conversion

```
1. User clicks "Upgrade" → Stripe Checkout session (created via /api/user/portal or embedded checkout)
2. Stripe fires `checkout.session.completed` webhook → POST /api/webhooks/stripe
3. Webhook handler:
   a. Looks up Clerk user ID from Stripe customer metadata
   b. Looks up Keygen license from Clerk user metadata
   c. Transfers the Keygen license from "trial" policy to "starter"/"pro" policy
   d. Resets license expiry (Keygen transfer strategy: RESET_EXPIRY)
   e. Sets Keygen license maxUses based on plan tier
   f. Updates Clerk user metadata with new plan tier
4. Demiurge desktop/CLI validates license → sees new policy → unlocks paid features
```

### Data Flow: Subscription Renewal / Cancellation

```
Renewal:
1. Stripe fires `invoice.paid` webhook
2. Handler renews Keygen license (extends expiry by policy duration)
3. Resets monthly usage counter on the Keygen license

Cancellation:
1. Stripe fires `customer.subscription.deleted` webhook
2. Handler suspends the Keygen license
3. Updates Clerk user metadata (plan: "expired")
```

---

## 3. Project Structure

All cloud backend code lives in a new top-level directory:

```
web/
├── package.json
├── tsconfig.json
├── next.config.ts
├── vercel.json
├── .env.example
├── src/
│   ├── app/
│   │   ├── layout.tsx                  # Root layout (Clerk provider)
│   │   ├── page.tsx                    # Landing page (redirect to marketing site for now)
│   │   ├── api/
│   │   │   ├── webhooks/
│   │   │   │   ├── stripe/route.ts     # Stripe webhook handler
│   │   │   │   └── clerk/route.ts      # Clerk webhook handler
│   │   │   ├── auth/
│   │   │   │   ├── device-code/route.ts  # Device auth flow start
│   │   │   │   └── device-poll/route.ts  # Device auth flow poll
│   │   │   ├── license/
│   │   │   │   ├── validate/route.ts   # License validation proxy
│   │   │   │   └── activate/route.ts   # Machine activation proxy
│   │   │   └── user/
│   │   │       ├── subscription/route.ts # Get subscription info
│   │   │       └── portal/route.ts     # Create Stripe billing portal
│   │   ├── sign-in/
│   │   │   └── [[...sign-in]]/page.tsx # Clerk sign-in page
│   │   ├── sign-up/
│   │   │   └── [[...sign-up]]/page.tsx # Clerk sign-up page
│   │   └── auth-callback/
│   │       └── page.tsx                # OAuth callback → deep link back to desktop app
│   └── lib/
│       ├── keygen.ts                   # Keygen API client
│       ├── stripe-helpers.ts           # Stripe session/portal helpers
│       ├── clerk-helpers.ts            # Clerk metadata helpers
│       ├── constants.ts                # Plan tier definitions, policy IDs
│       └── env.ts                      # Typed env var access
```

---

## 4. Environment Variables

```bash
# .env.example

# Clerk
NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY=pk_live_...
CLERK_SECRET_KEY=sk_live_...
CLERK_WEBHOOK_SECRET=whsec_...

# Stripe
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_STARTER_PRICE_ID=price_...       # Monthly Starter plan
STRIPE_STARTER_ANNUAL_PRICE_ID=price_... # Annual Starter plan
STRIPE_PRO_PRICE_ID=price_...           # Monthly Pro plan
STRIPE_PRO_ANNUAL_PRICE_ID=price_...    # Annual Pro plan
STRIPE_TEAM_PRICE_ID=price_...          # Monthly Team plan (per-seat)
STRIPE_TEAM_ANNUAL_PRICE_ID=price_...   # Annual Team plan (per-seat)

# Keygen
KEYGEN_ACCOUNT_ID=...
KEYGEN_PRODUCT_TOKEN=...                # Admin-level product token
KEYGEN_PRODUCT_ID=...
KEYGEN_TRIAL_POLICY_ID=...              # 14-day trial policy
KEYGEN_STARTER_POLICY_ID=...            # Starter plan policy
KEYGEN_PRO_POLICY_ID=...                # Pro plan policy
KEYGEN_TEAM_POLICY_ID=...               # Team plan policy

# App
NEXT_PUBLIC_APP_URL=https://demiurge.dev
DESKTOP_DEEP_LINK_SCHEME=demiurge       # demiurge:// deep link
```

---

## 5. Keygen.sh Configuration

### 5.1 Product

Create one product in Keygen: **"Demiurge"**.

### 5.2 Policies

| Policy | Duration | maxMachines | maxUses | fingerprintUniqueness | expirationStrategy | transferStrategy |
|--------|----------|-------------|---------|----------------------|-------------------|-----------------|
| `trial` | 1209600 (14 days) | 1 | 5 (5 runs) | UNIQUE_PER_POLICY | RESTRICT_ACCESS | — |
| `starter` | 2629746 (1 month) | 2 | 50 (50 runs/mo) | UNIQUE_PER_LICENSE | RESTRICT_ACCESS | RESET_EXPIRY |
| `pro` | 2629746 (1 month) | 3 | 200 (200 runs/mo) | UNIQUE_PER_LICENSE | RESTRICT_ACCESS | RESET_EXPIRY |
| `team` | 2629746 (1 month) | 5 | 150 (150 runs/user/mo) | UNIQUE_PER_LICENSE | RESTRICT_ACCESS | RESET_EXPIRY |

**Key policy settings:**
- `requireFingerprintScope`: **true** — license validation must include a machine fingerprint
- `protected`: **true** — licenses require a token or key for validation (not unprotected)
- `machineMatchingStrategy`: `MATCH_ANY` — any activated machine fingerprint satisfies
- `maxUses` tracks **run count** per billing period. Reset on each Stripe renewal webhook.

### 5.3 Entitlements (Future Use)

For now, all plans share the same entitlements. Later, entitlements can gate features like:
- `build_mode` — only Pro+ plans
- `browser_verification` — only Pro+ plans
- `agent_repair` — all paid plans
- `managed_credits` — addon

### 5.4 Machine Fingerprint Strategy

The machine fingerprint is a SHA-256 hash of:
```
SHA256(hostname + os.type + os.arch + os.platform + username)
```

This is generated on the Scala CLI side (for CLI usage) and on the Rust side (for desktop app). It does NOT use hardware IDs (too fragile across OS updates) but is stable enough to prevent casual sharing.

---

## 6. Stripe Configuration

### 6.1 Products & Prices

Create one Stripe product: **"Demiurge"** with the following prices:

| Price ID alias | Amount | Interval | Lookup Key |
|----------------|--------|----------|------------|
| `starter_monthly` | $29/mo | monthly | `demiurge_starter_monthly` |
| `starter_annual` | $288/yr ($24/mo) | yearly | `demiurge_starter_annual` |
| `pro_monthly` | $79/mo | monthly | `demiurge_pro_monthly` |
| `pro_annual` | $792/yr ($66/mo) | yearly | `demiurge_pro_annual` |
| `team_monthly` | $49/user/mo | monthly | `demiurge_team_monthly` |
| `team_annual` | $492/user/yr ($41/mo) | yearly | `demiurge_team_annual` |

### 6.2 Stripe Webhook Events to Listen For

Register a webhook endpoint at `https://demiurge.dev/api/webhooks/stripe` for:

- `checkout.session.completed` — new subscription created
- `invoice.paid` — recurring payment succeeded (renewal)
- `invoice.payment_failed` — payment failed
- `customer.subscription.updated` — plan change (upgrade/downgrade)
- `customer.subscription.deleted` — cancellation

---

## 7. Clerk Configuration

### 7.1 Application Setup

- **Sign-in methods:** Email + Password, GitHub OAuth, Google OAuth
- **Session token lifetime:** 60 seconds (default JWT), refreshed automatically
- **Custom JWT claims:** Add `license_key`, `plan_tier`, and `keygen_license_id` from user `publicMetadata`

### 7.2 User Metadata Schema

Clerk user `publicMetadata` (readable by frontend):
```json
{
  "plan_tier": "trial" | "starter" | "pro" | "team" | "enterprise",
  "license_key": "DEMI-XXXX-XXXX-XXXX",
  "keygen_license_id": "uuid",
  "stripe_customer_id": "cus_..."
}
```

Clerk user `privateMetadata` (server-only):
```json
{
  "keygen_user_id": "uuid",
  "stripe_subscription_id": "sub_..."
}
```

### 7.3 Webhook Events to Listen For

Register a webhook endpoint at `https://demiurge.dev/api/webhooks/clerk` for:
- `user.created` — trigger trial license creation

---

## 8. API Route Specifications

### 8.1 POST /api/webhooks/clerk

**Purpose:** Handle Clerk user lifecycle events.

**Authentication:** Verified via `svix` signature (Clerk webhook signing).

**Event: `user.created`**

```typescript
async function handleUserCreated(user: WebhookEvent['data']) {
  // 1. Create Keygen user
  const keygenUser = await keygen.createUser({
    email: user.email_addresses[0].email_address,
    metadata: { clerk_user_id: user.id }
  });

  // 2. Create trial license
  const license = await keygen.createLicense({
    policyId: KEYGEN_TRIAL_POLICY_ID,
    userId: keygenUser.id,
  });

  // 3. Store in Clerk metadata
  await clerkClient.users.updateUserMetadata(user.id, {
    publicMetadata: {
      plan_tier: 'trial',
      license_key: license.attributes.key,
      keygen_license_id: license.id,
    },
    privateMetadata: {
      keygen_user_id: keygenUser.id,
    },
  });
}
```

**Response:** 200 OK (empty body). Clerk retries on non-2xx.

### 8.2 POST /api/webhooks/stripe

**Purpose:** Handle Stripe billing events.

**Authentication:** Verified via `stripe.webhooks.constructEvent()` with `STRIPE_WEBHOOK_SECRET`.

**Event: `checkout.session.completed`**

```typescript
async function handleCheckoutCompleted(session: Stripe.Checkout.Session) {
  const clerkUserId = session.metadata?.clerk_user_id;
  if (!clerkUserId) throw new Error('Missing clerk_user_id in session metadata');

  // 1. Get the Clerk user
  const user = await clerkClient.users.getUser(clerkUserId);
  const keygenLicenseId = user.publicMetadata.keygen_license_id;

  // 2. Determine target policy from the price ID
  const priceId = session.line_items?.data[0]?.price?.id;
  const targetPolicyId = priceToPolicyMap[priceId];
  const planTier = priceToPlanTierMap[priceId];
  const maxUses = planToMaxUsesMap[planTier];

  // 3. Transfer license to paid policy (resets expiry)
  await keygen.transferLicensePolicy(keygenLicenseId, targetPolicyId);

  // 4. Update license maxUses
  await keygen.updateLicense(keygenLicenseId, { maxUses });

  // 5. Reset usage counter
  await keygen.resetLicenseUsage(keygenLicenseId);

  // 6. Update Clerk metadata
  await clerkClient.users.updateUserMetadata(clerkUserId, {
    publicMetadata: {
      plan_tier: planTier,
    },
    privateMetadata: {
      stripe_customer_id: session.customer,
      stripe_subscription_id: session.subscription,
    },
  });
}
```

**Event: `invoice.paid`** (recurring renewal)

```typescript
async function handleInvoicePaid(invoice: Stripe.Invoice) {
  // Only handle subscription renewals (not first payment)
  if (invoice.billing_reason !== 'subscription_cycle') return;

  const stripeCustomerId = invoice.customer as string;
  // Look up Clerk user by stripe_customer_id (search privateMetadata)
  const users = await clerkClient.users.getUserList({
    // Use Clerk's metadata search or iterate
  });
  const user = users.find(u => u.privateMetadata.stripe_customer_id === stripeCustomerId);
  if (!user) return;

  const keygenLicenseId = user.publicMetadata.keygen_license_id;

  // 1. Renew license (extends expiry by policy duration)
  await keygen.renewLicense(keygenLicenseId);

  // 2. Reset monthly usage counter
  await keygen.resetLicenseUsage(keygenLicenseId);
}
```

**Event: `customer.subscription.deleted`** (cancellation)

```typescript
async function handleSubscriptionDeleted(subscription: Stripe.Subscription) {
  const clerkUserId = subscription.metadata?.clerk_user_id;
  if (!clerkUserId) return;

  const user = await clerkClient.users.getUser(clerkUserId);
  const keygenLicenseId = user.publicMetadata.keygen_license_id;

  // Suspend the license
  await keygen.suspendLicense(keygenLicenseId);

  // Update Clerk metadata
  await clerkClient.users.updateUserMetadata(clerkUserId, {
    publicMetadata: { plan_tier: 'expired' },
  });
}
```

**Event: `customer.subscription.updated`** (plan change)

```typescript
async function handleSubscriptionUpdated(subscription: Stripe.Subscription) {
  // Detect plan change by comparing old vs new price ID
  const priceId = subscription.items.data[0]?.price?.id;
  const targetPolicyId = priceToPolicyMap[priceId];
  const planTier = priceToPlanTierMap[priceId];
  const maxUses = planToMaxUsesMap[planTier];

  const clerkUserId = subscription.metadata?.clerk_user_id;
  if (!clerkUserId) return;

  const user = await clerkClient.users.getUser(clerkUserId);
  const keygenLicenseId = user.publicMetadata.keygen_license_id;

  // Transfer to new policy
  await keygen.transferLicensePolicy(keygenLicenseId, targetPolicyId);
  await keygen.updateLicense(keygenLicenseId, { maxUses });

  await clerkClient.users.updateUserMetadata(clerkUserId, {
    publicMetadata: { plan_tier: planTier },
  });
}
```

### 8.3 POST /api/auth/device-code

**Purpose:** Start a device authorization flow for CLI login. The CLI cannot show a web browser in all environments (SSH, headless servers), so we implement a simple device code flow:

1. CLI calls this endpoint → gets a `device_code` and a `user_code`
2. CLI displays: "Visit https://demiurge.dev/activate and enter code: ABCD-1234"
3. User visits the URL, signs in via Clerk, enters the code
4. CLI polls `/api/auth/device-poll` until the code is confirmed

**Request:**
```json
POST /api/auth/device-code
Content-Type: application/json

{}
```

**Response:**
```json
{
  "device_code": "dc_abc123...",
  "user_code": "ABCD-1234",
  "verification_url": "https://demiurge.dev/activate",
  "expires_in": 600,
  "poll_interval": 5
}
```

**Implementation:**
- Generate a random 8-character alphanumeric `user_code` (formatted as XXXX-XXXX for readability)
- Generate a random `device_code` (32-byte hex)
- Store mapping `{ device_code → { user_code, created_at, expires_at, clerk_user_id: null } }` in Vercel KV (or a simple in-memory store for MVP — codes expire in 10 minutes)
- Return both codes to the CLI

### 8.4 GET /api/auth/device-poll

**Purpose:** CLI polls this endpoint to check if the user has completed auth.

**Request:**
```
GET /api/auth/device-poll?device_code=dc_abc123...
```

**Response (pending):**
```json
{ "status": "pending" }
```

**Response (authorized):**
```json
{
  "status": "authorized",
  "license_key": "DEMI-XXXX-XXXX-XXXX",
  "plan_tier": "starter",
  "user_email": "user@example.com"
}
```

**Response (expired):**
```json
{ "status": "expired" }
```

### 8.5 GET /api/license/validate

**Purpose:** Proxy license validation to Keygen. The CLI/desktop app calls this instead of Keygen directly to keep the Keygen account ID private and to add custom logic (e.g., rate limiting).

**Authentication:** Bearer token (Clerk JWT) OR license key in header.

**Request:**
```
GET /api/license/validate
Authorization: Bearer <clerk_jwt>
X-License-Key: DEMI-XXXX-XXXX-XXXX
X-Machine-Fingerprint: sha256_hex_string
```

**Response:**
```json
{
  "valid": true,
  "code": "VALID",
  "plan_tier": "pro",
  "uses": 42,
  "max_uses": 200,
  "expiry": "2026-04-19T00:00:00Z",
  "entitlements": ["agent_repair", "browser_verification", "build_mode"]
}
```

**Error codes forwarded from Keygen:**
- `NOT_FOUND` → 404
- `EXPIRED` → 403 with `{ "valid": false, "code": "EXPIRED" }`
- `SUSPENDED` → 403 with `{ "valid": false, "code": "SUSPENDED" }`
- `TOO_MANY_MACHINES` → 403 with `{ "valid": false, "code": "TOO_MANY_MACHINES" }`
- `NO_MACHINE` → 403 with `{ "valid": false, "code": "NO_MACHINE", "message": "Machine not activated. Run `demiurge activate`." }`

### 8.6 POST /api/license/activate

**Purpose:** Activate a machine for a license.

**Authentication:** License key in header.

**Request:**
```json
POST /api/license/activate
X-License-Key: DEMI-XXXX-XXXX-XXXX

{
  "fingerprint": "sha256_hex_string",
  "name": "Steves-MacBook-Pro",
  "platform": "darwin",
  "hostname": "Steves-MacBook-Pro.local"
}
```

**Response (success):**
```json
{
  "activated": true,
  "machine_id": "uuid"
}
```

**Response (limit reached):**
```json
{
  "activated": false,
  "code": "TOO_MANY_MACHINES",
  "message": "Machine limit reached for your plan. Deactivate an existing machine or upgrade."
}
```

### 8.7 GET /api/user/subscription

**Purpose:** Get the current user's subscription details for display in the desktop app.

**Authentication:** Bearer token (Clerk JWT).

**Response:**
```json
{
  "plan_tier": "pro",
  "status": "active",
  "current_period_end": "2026-04-19T00:00:00Z",
  "uses_this_period": 42,
  "max_uses": 200,
  "cancel_at_period_end": false
}
```

### 8.8 POST /api/user/portal

**Purpose:** Create a Stripe Customer Portal session for managing billing.

**Authentication:** Bearer token (Clerk JWT).

**Response:**
```json
{
  "url": "https://billing.stripe.com/p/session/..."
}
```

---

## 9. Keygen API Client (`src/lib/keygen.ts`)

Thin wrapper around the Keygen REST API. All calls use the product token for auth.

```typescript
const KEYGEN_BASE = `https://api.keygen.sh/v1/accounts/${KEYGEN_ACCOUNT_ID}`;

const headers = {
  'Authorization': `Bearer ${KEYGEN_PRODUCT_TOKEN}`,
  'Content-Type': 'application/vnd.api+json',
  'Accept': 'application/vnd.api+json',
};

export const keygen = {
  // Users
  async createUser(params: { email: string; metadata: Record<string, string> }): Promise<KeygenUser>,

  // Licenses
  async createLicense(params: { policyId: string; userId: string }): Promise<KeygenLicense>,
  async validateLicenseKey(key: string, scope?: { fingerprint?: string }): Promise<KeygenValidation>,
  async transferLicensePolicy(licenseId: string, policyId: string): Promise<void>,
  async updateLicense(licenseId: string, attrs: { maxUses?: number }): Promise<void>,
  async renewLicense(licenseId: string): Promise<void>,
  async suspendLicense(licenseId: string): Promise<void>,
  async reinstateLicense(licenseId: string): Promise<void>,
  async incrementUsage(licenseId: string, increment?: number): Promise<{ uses: number; maxUses: number }>,
  async resetLicenseUsage(licenseId: string): Promise<void>,

  // Machines
  async activateMachine(licenseId: string, params: { fingerprint: string; name: string; platform: string }): Promise<KeygenMachine>,
  async deactivateMachine(machineId: string): Promise<void>,
};
```

### Keygen API Endpoints Used

| Operation | Method | URL |
|-----------|--------|-----|
| Create user | POST | `/users` |
| Create license | POST | `/licenses` |
| Validate by key | POST | `/licenses/actions/validate-key` |
| Transfer policy | POST | `/licenses/{id}/relationships/policy` |
| Update license | PATCH | `/licenses/{id}` |
| Renew license | POST | `/licenses/{id}/actions/renew` |
| Suspend license | POST | `/licenses/{id}/actions/suspend` |
| Reinstate license | POST | `/licenses/{id}/actions/reinstate` |
| Increment usage | POST | `/licenses/{id}/actions/increment-usage` |
| Reset usage | POST | `/licenses/{id}/actions/reset-usage` |
| Create machine | POST | `/machines` |
| Delete machine | DELETE | `/machines/{id}` |

---

## 10. Constants & Plan Mapping (`src/lib/constants.ts`)

```typescript
export const PLAN_TIERS = ['trial', 'starter', 'pro', 'team', 'enterprise'] as const;
export type PlanTier = typeof PLAN_TIERS[number];

export const PLAN_CONFIG: Record<PlanTier, {
  keygenPolicyId: string;
  maxRuns: number;
  maxMachines: number;
  maxTokens: number;
}> = {
  trial:      { keygenPolicyId: env.KEYGEN_TRIAL_POLICY_ID,   maxRuns: 5,   maxMachines: 1, maxTokens: 50_000 },
  starter:    { keygenPolicyId: env.KEYGEN_STARTER_POLICY_ID, maxRuns: 50,  maxMachines: 2, maxTokens: 500_000 },
  pro:        { keygenPolicyId: env.KEYGEN_PRO_POLICY_ID,     maxRuns: 200, maxMachines: 3, maxTokens: 2_000_000 },
  team:       { keygenPolicyId: env.KEYGEN_TEAM_POLICY_ID,    maxRuns: 150, maxMachines: 5, maxTokens: 1_500_000 },
  enterprise: { keygenPolicyId: '',                            maxRuns: -1,  maxMachines: -1, maxTokens: -1 },
};

// Stripe price ID → Keygen policy ID mapping
export const PRICE_TO_POLICY: Record<string, string> = {
  [env.STRIPE_STARTER_PRICE_ID]: env.KEYGEN_STARTER_POLICY_ID,
  [env.STRIPE_STARTER_ANNUAL_PRICE_ID]: env.KEYGEN_STARTER_POLICY_ID,
  [env.STRIPE_PRO_PRICE_ID]: env.KEYGEN_PRO_POLICY_ID,
  [env.STRIPE_PRO_ANNUAL_PRICE_ID]: env.KEYGEN_PRO_POLICY_ID,
  [env.STRIPE_TEAM_PRICE_ID]: env.KEYGEN_TEAM_POLICY_ID,
  [env.STRIPE_TEAM_ANNUAL_PRICE_ID]: env.KEYGEN_TEAM_POLICY_ID,
};

// Stripe price ID → plan tier
export const PRICE_TO_TIER: Record<string, PlanTier> = {
  [env.STRIPE_STARTER_PRICE_ID]: 'starter',
  [env.STRIPE_STARTER_ANNUAL_PRICE_ID]: 'starter',
  [env.STRIPE_PRO_PRICE_ID]: 'pro',
  [env.STRIPE_PRO_ANNUAL_PRICE_ID]: 'pro',
  [env.STRIPE_TEAM_PRICE_ID]: 'team',
  [env.STRIPE_TEAM_ANNUAL_PRICE_ID]: 'team',
};
```

---

## 11. Dependencies (`web/package.json`)

```json
{
  "name": "demiurge-web",
  "private": true,
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start"
  },
  "dependencies": {
    "next": "^15.0.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "@clerk/nextjs": "^6.0.0",
    "stripe": "^17.0.0",
    "svix": "^1.40.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "@types/react": "^19.0.0",
    "typescript": "^5.7.0"
  }
}
```

Note: **No Keygen SDK** — use raw `fetch()` calls to the Keygen REST API. The Keygen Node SDK is minimal and the REST API is simple enough that a thin client (§9) is cleaner.

---

## 12. Deployment

- **Platform:** Vercel (free Hobby plan initially, Pro $20/mo when needed)
- **Build command:** `next build`
- **Root directory:** `web/`
- **Environment variables:** Set in Vercel dashboard (not committed to git)
- **Domain:** `demiurge.dev` (or chosen domain) pointed to Vercel via CNAME

---

## 13. Testing Plan

### 13.1 Unit Tests

Test the Keygen client functions with mocked HTTP responses:
- `createUser` → verify request body shape and header auth
- `validateLicenseKey` → test VALID, EXPIRED, SUSPENDED, NOT_FOUND responses
- `transferLicensePolicy` → verify correct policy ID in request
- `incrementUsage` → test success and maxUses exceeded (422)

### 13.2 Integration Tests (Stripe Webhook)

Use Stripe CLI (`stripe listen --forward-to localhost:3000/api/webhooks/stripe`) to:
- Trigger `checkout.session.completed` → verify Keygen license policy transfer
- Trigger `invoice.paid` → verify Keygen license renewal + usage reset
- Trigger `customer.subscription.deleted` → verify license suspension

### 13.3 Integration Tests (Clerk Webhook)

Use Clerk dashboard test events or ngrok:
- Trigger `user.created` → verify Keygen user + trial license created
- Verify Clerk user metadata updated with license key

### 13.4 Manual E2E Smoke Test

1. Sign up on demiurge.dev → verify trial license key appears
2. Run `demiurge login` in CLI → verify device code flow works
3. Run `demiurge run --task "test"` → verify license validation + usage increment
4. Click "Upgrade" → complete Stripe checkout → verify license policy transfer
5. Run more runs → verify new limits apply
6. Cancel subscription → verify license suspension

---

## 14. Security Considerations

- **Webhook verification:** All webhook endpoints MUST verify signatures (Stripe: `stripe.webhooks.constructEvent`, Clerk: `svix.verify`). Reject unverified payloads with 401.
- **Keygen product token:** Never exposed to the client. All Keygen API calls go through the server-side API routes.
- **License keys:** Stored in Clerk `publicMetadata` (visible to frontend) but are not secret — they're validated server-side with machine fingerprinting.
- **Device codes:** Expire after 10 minutes. Stored in ephemeral storage (Vercel KV or in-memory Map for MVP).
- **Rate limiting:** Apply rate limits to `/api/auth/device-poll` (max 1 req/5s per device_code) to prevent brute-force polling.
- **CORS:** API routes should only accept requests from `demiurge.dev` and `tauri://localhost` (Tauri WebView origin).

---

## 15. Out of Scope for This Spec

- Marketing site pages (landing, pricing, docs) → Spec 04
- CLI login/logout command implementation → Spec 02
- Desktop app auth UI → Spec 02
- Usage metering and credit system → Spec 05
- Stripe Checkout page UI / pricing page → Spec 04
- Team management (invites, seat management) → future
- Enterprise SSO/SAML → future
