/**
 * Tests for the Clerk webhook handler logic.
 * Spec 01 §13.3: Verifies user.created → Keygen user + trial license + Clerk metadata update.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// --- Mock external modules ---

const mockKeygen = {
  createUser: vi.fn().mockResolvedValue({
    id: 'keygen-user-1',
    type: 'users',
    attributes: { email: 'test@example.com' },
  }),
  createLicense: vi.fn().mockResolvedValue({
    id: 'license-abc',
    type: 'licenses',
    attributes: {
      key: 'DEMI-TEST-1234-5678',
      status: 'ACTIVE',
      uses: 0,
      maxUses: 5,
    },
  }),
};

vi.mock('@/lib/keygen', () => ({
  keygen: mockKeygen,
}));

const mockUpdateClerkUserMetadata = vi.fn().mockResolvedValue(undefined);

vi.mock('@/lib/clerk-helpers', () => ({
  updateClerkUserMetadata: (...args: unknown[]) => mockUpdateClerkUserMetadata(...args),
}));

vi.mock('@/lib/env', () => ({
  env: {
    CLERK_WEBHOOK_SECRET: 'test-webhook-secret',
    KEYGEN_TRIAL_POLICY_ID: 'policy-trial',
  },
}));

// Mock svix Webhook to skip signature verification in tests
vi.mock('svix', () => ({
  Webhook: vi.fn().mockImplementation(() => ({
    verify: (_body: string, _headers: Record<string, string>) => JSON.parse(_body),
  })),
}));

beforeEach(() => {
  vi.clearAllMocks();
});

// --- Tests ---

describe('Clerk webhook: user.created', () => {
  it('creates Keygen user, trial license, and updates Clerk metadata', async () => {
    const { POST } = await import('@/app/api/webhooks/clerk/route');

    const event = {
      type: 'user.created',
      data: {
        id: 'user_clerk123',
        email_addresses: [{ email_address: 'test@example.com', id: 'email_1' }],
        first_name: 'Test',
        last_name: 'User',
      },
    };

    const req = new Request('http://localhost/api/webhooks/clerk', {
      method: 'POST',
      headers: {
        'svix-id': 'msg_test',
        'svix-timestamp': '1234567890',
        'svix-signature': 'v1,valid_sig',
        'content-type': 'application/json',
      },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    // Verify Keygen user created with correct email and metadata
    expect(mockKeygen.createUser).toHaveBeenCalledWith({
      email: 'test@example.com',
      metadata: { clerk_user_id: 'user_clerk123' },
    });

    // Verify trial license created under trial policy
    expect(mockKeygen.createLicense).toHaveBeenCalledWith({
      policyId: 'policy-trial',
      userId: 'keygen-user-1',
    });

    // Verify Clerk metadata updated with license key and plan tier
    expect(mockUpdateClerkUserMetadata).toHaveBeenCalledWith('user_clerk123', {
      publicMetadata: {
        plan_tier: 'trial',
        license_key: 'DEMI-TEST-1234-5678',
        keygen_license_id: 'license-abc',
      },
      privateMetadata: {
        keygen_user_id: 'keygen-user-1',
      },
    });
  });

  it('handles user with no email gracefully', async () => {
    const { POST } = await import('@/app/api/webhooks/clerk/route');

    const event = {
      type: 'user.created',
      data: {
        id: 'user_no_email',
        email_addresses: [],
        first_name: null,
        last_name: null,
      },
    };

    const req = new Request('http://localhost/api/webhooks/clerk', {
      method: 'POST',
      headers: {
        'svix-id': 'msg_test2',
        'svix-timestamp': '1234567890',
        'svix-signature': 'v1,valid_sig',
        'content-type': 'application/json',
      },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);

    // Should not attempt to create Keygen user without email
    expect(mockKeygen.createUser).not.toHaveBeenCalled();
  });
});

describe('Clerk webhook: unknown event', () => {
  it('returns 200 for unhandled event types', async () => {
    const { POST } = await import('@/app/api/webhooks/clerk/route');

    const event = {
      type: 'user.updated',
      data: { id: 'user_clerk123' },
    };

    const req = new Request('http://localhost/api/webhooks/clerk', {
      method: 'POST',
      headers: {
        'svix-id': 'msg_test3',
        'svix-timestamp': '1234567890',
        'svix-signature': 'v1,valid_sig',
      },
      body: JSON.stringify(event),
    });

    const res = await POST(req as any);
    expect(res.status).toBe(200);
  });
});

describe('Clerk webhook: missing headers', () => {
  it('returns 401 without svix headers', async () => {
    const { POST } = await import('@/app/api/webhooks/clerk/route');

    const req = new Request('http://localhost/api/webhooks/clerk', {
      method: 'POST',
      body: '{}',
    });

    const res = await POST(req as any);
    expect(res.status).toBe(401);
  });
});
