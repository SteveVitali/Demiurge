/**
 * Tests for the Stripe webhook handler logic.
 * Spec 01 §13.2: Verifies correct Keygen + Clerk interactions for each event type.
 *
 * These tests mock the external service clients (keygen, clerk-helpers, stripe-helpers)
 * and verify the handler orchestrates them correctly.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// --- Mock external modules ---

const mockKeygen = {
  transferLicensePolicy: vi.fn().mockResolvedValue(undefined),
  updateLicense: vi.fn().mockResolvedValue(undefined),
  resetLicenseUsage: vi.fn().mockResolvedValue(undefined),
  renewLicense: vi.fn().mockResolvedValue(undefined),
  suspendLicense: vi.fn().mockResolvedValue(undefined),
};

vi.mock('@/lib/keygen', () => ({
  keygen: mockKeygen,
  KeygenApiError: class KeygenApiError extends Error {
    status: number;
    code: string;
    detail: string;
    constructor(status: number, code: string, detail: string) {
      super(detail);
      this.status = status;
      this.code = code;
      this.detail = detail;
    }
  },
}));

const mockGetClerkUser = vi.fn();
const mockUpdateClerkUserMetadata = vi.fn().mockResolvedValue(undefined);
const mockFindClerkUserByStripeCustomerId = vi.fn();

vi.mock('@/lib/clerk-helpers', () => ({
  getClerkUser: (...args: unknown[]) => mockGetClerkUser(...args),
  updateClerkUserMetadata: (...args: unknown[]) => mockUpdateClerkUserMetadata(...args),
  findClerkUserByStripeCustomerId: (...args: unknown[]) => mockFindClerkUserByStripeCustomerId(...args),
}));

vi.mock('@/lib/stripe-helpers', () => ({
  constructWebhookEvent: vi.fn((body: string) => JSON.parse(body)),
  getStripe: vi.fn(() => ({
    checkout: {
      sessions: {
        retrieve: vi.fn().mockResolvedValue({
          id: 'cs_test',
          line_items: { data: [{ price: { id: 'price_starter_monthly' } }] },
        }),
      },
    },
    subscriptions: {
      retrieve: vi.fn().mockResolvedValue({
        id: 'sub_test',
        metadata: { clerk_user_id: 'user_clerk123' },
      }),
    },
  })),
}));

vi.mock('@/lib/constants', () => ({
  getPriceToPolicyMap: () => ({
    price_starter_monthly: 'policy-starter',
    price_pro_monthly: 'policy-pro',
  }),
  getPriceToTierMap: () => ({
    price_starter_monthly: 'starter',
    price_pro_monthly: 'pro',
  }),
  getPlanMaxUses: (tier: string) => {
    const map: Record<string, number> = { starter: 50, pro: 200, team: 150 };
    return map[tier] ?? 0;
  },
}));

// --- Helpers ---

function clerkUser(overrides: Record<string, unknown> = {}) {
  return {
    id: 'user_clerk123',
    emailAddresses: [{ emailAddress: 'test@example.com' }],
    publicMetadata: {
      plan_tier: 'trial',
      license_key: 'DEMI-TEST-1234',
      keygen_license_id: 'license-abc',
      ...((overrides.publicMetadata as object) ?? {}),
    },
    privateMetadata: {
      keygen_user_id: 'keygen-user-1',
      stripe_customer_id: 'cus_test',
      ...((overrides.privateMetadata as object) ?? {}),
    },
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGetClerkUser.mockResolvedValue(clerkUser());
});

// --- Tests ---

describe('Stripe webhook: checkout.session.completed', () => {
  it('transfers license to paid policy, updates maxUses, resets usage, updates Clerk metadata', async () => {
    // Import the handler dynamically so mocks are in place
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const event = {
      type: 'checkout.session.completed',
      data: {
        object: {
          id: 'cs_test',
          metadata: { clerk_user_id: 'user_clerk123' },
          customer: 'cus_test',
          subscription: 'sub_test',
        },
      },
    };

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      headers: { 'stripe-signature': 'sig_valid' },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    // Verify Keygen calls
    expect(mockKeygen.transferLicensePolicy).toHaveBeenCalledWith('license-abc', 'policy-starter');
    expect(mockKeygen.updateLicense).toHaveBeenCalledWith('license-abc', { maxUses: 50 });
    expect(mockKeygen.resetLicenseUsage).toHaveBeenCalledWith('license-abc');

    // Verify Clerk metadata update
    expect(mockUpdateClerkUserMetadata).toHaveBeenCalledWith('user_clerk123', expect.objectContaining({
      publicMetadata: { plan_tier: 'starter' },
      privateMetadata: expect.objectContaining({
        stripe_customer_id: 'cus_test',
        stripe_subscription_id: 'sub_test',
      }),
    }));
  });
});

describe('Stripe webhook: invoice.paid', () => {
  it('renews license and resets usage on subscription_cycle', async () => {
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const event = {
      type: 'invoice.paid',
      data: {
        object: {
          billing_reason: 'subscription_cycle',
          customer: 'cus_test',
          subscription: 'sub_test',
        },
      },
    };

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      headers: { 'stripe-signature': 'sig_valid' },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    expect(mockKeygen.renewLicense).toHaveBeenCalledWith('license-abc');
    expect(mockKeygen.resetLicenseUsage).toHaveBeenCalledWith('license-abc');
  });

  it('ignores non-renewal invoices', async () => {
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const event = {
      type: 'invoice.paid',
      data: {
        object: {
          billing_reason: 'subscription_create',
          customer: 'cus_test',
          subscription: 'sub_test',
        },
      },
    };

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      headers: { 'stripe-signature': 'sig_valid' },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    expect(mockKeygen.renewLicense).not.toHaveBeenCalled();
    expect(mockKeygen.resetLicenseUsage).not.toHaveBeenCalled();
  });
});

describe('Stripe webhook: customer.subscription.deleted', () => {
  it('suspends license and sets plan to expired', async () => {
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const event = {
      type: 'customer.subscription.deleted',
      data: {
        object: {
          id: 'sub_test',
          metadata: { clerk_user_id: 'user_clerk123' },
        },
      },
    };

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      headers: { 'stripe-signature': 'sig_valid' },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    expect(mockKeygen.suspendLicense).toHaveBeenCalledWith('license-abc');
    expect(mockUpdateClerkUserMetadata).toHaveBeenCalledWith('user_clerk123', {
      publicMetadata: { plan_tier: 'expired' },
    });
  });
});

describe('Stripe webhook: customer.subscription.updated', () => {
  it('transfers license to new policy on plan change', async () => {
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const event = {
      type: 'customer.subscription.updated',
      data: {
        object: {
          id: 'sub_test',
          metadata: { clerk_user_id: 'user_clerk123' },
          items: { data: [{ price: { id: 'price_pro_monthly' } }] },
        },
      },
    };

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      headers: { 'stripe-signature': 'sig_valid' },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    expect(mockKeygen.transferLicensePolicy).toHaveBeenCalledWith('license-abc', 'policy-pro');
    expect(mockKeygen.updateLicense).toHaveBeenCalledWith('license-abc', { maxUses: 200 });
    expect(mockUpdateClerkUserMetadata).toHaveBeenCalledWith('user_clerk123', {
      publicMetadata: { plan_tier: 'pro' },
    });
  });
});

describe('Stripe webhook: missing signature', () => {
  it('returns 401 without stripe-signature header', async () => {
    const { POST } = await import('@/app/api/webhooks/stripe/route');

    const req = new Request('http://localhost/api/webhooks/stripe', {
      method: 'POST',
      body: '{}',
    });

    const res = await POST(req as any);
    expect(res.status).toBe(401);
  });
});
