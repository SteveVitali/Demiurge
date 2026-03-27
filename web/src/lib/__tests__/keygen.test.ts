/**
 * Tests for the Keygen API client.
 * Mocks fetch to verify request shapes, headers, and response parsing.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock env before importing keygen
vi.mock('@/lib/env', () => ({
  env: {
    KEYGEN_ACCOUNT_ID: 'test-account-id',
    KEYGEN_PRODUCT_TOKEN: 'test-product-token',
    KEYGEN_PRODUCT_ID: 'test-product-id',
    KEYGEN_TRIAL_POLICY_ID: 'policy-trial',
    KEYGEN_STARTER_POLICY_ID: 'policy-starter',
    KEYGEN_PRO_POLICY_ID: 'policy-pro',
    KEYGEN_TEAM_POLICY_ID: 'policy-team',
  },
}));

import { keygen, KeygenApiError } from '../keygen';

const KEYGEN_BASE = 'https://api.keygen.sh/v1/accounts/test-account-id';

function mockFetchSuccess(data: unknown, status = 200) {
  return vi.fn().mockResolvedValueOnce({
    ok: true,
    status,
    json: () => Promise.resolve(data),
  });
}

function mockFetchNoContent() {
  return vi.fn().mockResolvedValueOnce({
    ok: true,
    status: 204,
    json: () => Promise.reject(new Error('No content')),
  });
}

function mockFetchError(status: number, code: string, detail: string) {
  return vi.fn().mockResolvedValueOnce({
    ok: false,
    status,
    statusText: 'Error',
    json: () =>
      Promise.resolve({
        errors: [{ code, detail }],
      }),
  });
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('keygen.createUser', () => {
  it('sends correct request body and returns user', async () => {
    const mockUser = {
      id: 'user-123',
      type: 'users',
      attributes: {
        email: 'test@example.com',
        metadata: { clerk_user_id: 'clerk-456' },
        createdAt: '2026-01-01T00:00:00Z',
      },
    };

    global.fetch = mockFetchSuccess({ data: mockUser });

    const result = await keygen.createUser({
      email: 'test@example.com',
      metadata: { clerk_user_id: 'clerk-456' },
    });

    expect(result).toEqual(mockUser);

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`${KEYGEN_BASE}/users`);
    expect(opts.method).toBe('POST');
    expect(opts.headers.Authorization).toBe('Bearer test-product-token');
    expect(opts.headers['Content-Type']).toBe('application/vnd.api+json');

    const body = JSON.parse(opts.body);
    expect(body.data.type).toBe('users');
    expect(body.data.attributes.email).toBe('test@example.com');
    expect(body.data.attributes.metadata.clerk_user_id).toBe('clerk-456');
  });
});

describe('keygen.createLicense', () => {
  it('creates license with correct policy and user relationships', async () => {
    const mockLicense = {
      id: 'license-789',
      type: 'licenses',
      attributes: {
        key: 'DEMI-ABCD-EFGH-IJKL',
        status: 'ACTIVE',
        uses: 0,
        maxUses: 5,
        expiry: '2026-01-15T00:00:00Z',
        metadata: {},
        createdAt: '2026-01-01T00:00:00Z',
      },
    };

    global.fetch = mockFetchSuccess({ data: mockLicense });

    const result = await keygen.createLicense({
      policyId: 'policy-trial',
      userId: 'user-123',
    });

    expect(result.id).toBe('license-789');
    expect(result.attributes.key).toBe('DEMI-ABCD-EFGH-IJKL');

    const body = JSON.parse(
      (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1].body,
    );
    expect(body.data.relationships.policy.data.id).toBe('policy-trial');
    expect(body.data.relationships.user.data.id).toBe('user-123');
  });
});

describe('keygen.validateLicenseKey', () => {
  it('validates a license key successfully', async () => {
    const mockValidation = {
      valid: true,
      code: 'VALID',
      detail: 'License is valid',
      metadata: {
        id: 'license-789',
        key: 'DEMI-ABCD-EFGH-IJKL',
        uses: 3,
        maxUses: 5,
        expiry: '2026-01-15T00:00:00Z',
        status: 'ACTIVE',
      },
    };

    global.fetch = mockFetchSuccess({ meta: mockValidation });

    const result = await keygen.validateLicenseKey('DEMI-ABCD-EFGH-IJKL');

    expect(result.valid).toBe(true);
    expect(result.code).toBe('VALID');
    expect(result.metadata.uses).toBe(3);
  });

  it('validates with fingerprint scope', async () => {
    global.fetch = mockFetchSuccess({
      meta: { valid: true, code: 'VALID', detail: 'ok', metadata: {} },
    });

    await keygen.validateLicenseKey('KEY', { fingerprint: 'fp-hash' });

    const body = JSON.parse(
      (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1].body,
    );
    expect(body.meta.scope.fingerprint).toBe('fp-hash');
  });

  it('returns EXPIRED validation', async () => {
    global.fetch = mockFetchSuccess({
      meta: {
        valid: false,
        code: 'EXPIRED',
        detail: 'License has expired',
        metadata: {},
      },
    });

    const result = await keygen.validateLicenseKey('EXPIRED-KEY');
    expect(result.valid).toBe(false);
    expect(result.code).toBe('EXPIRED');
  });

  it('returns SUSPENDED validation', async () => {
    global.fetch = mockFetchSuccess({
      meta: {
        valid: false,
        code: 'SUSPENDED',
        detail: 'License is suspended',
        metadata: {},
      },
    });

    const result = await keygen.validateLicenseKey('SUSPENDED-KEY');
    expect(result.valid).toBe(false);
    expect(result.code).toBe('SUSPENDED');
  });

  it('returns NOT_FOUND validation', async () => {
    global.fetch = mockFetchSuccess({
      meta: {
        valid: false,
        code: 'NOT_FOUND',
        detail: 'License not found',
        metadata: {},
      },
    });

    const result = await keygen.validateLicenseKey('INVALID-KEY');
    expect(result.valid).toBe(false);
    expect(result.code).toBe('NOT_FOUND');
  });
});

describe('keygen.transferLicensePolicy', () => {
  it('sends correct policy transfer request', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.transferLicensePolicy('license-789', 'policy-starter');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(
      `${KEYGEN_BASE}/licenses/license-789/relationships/policy`,
    );
    expect(opts.method).toBe('PUT');

    const body = JSON.parse(opts.body);
    expect(body.data.type).toBe('policies');
    expect(body.data.id).toBe('policy-starter');
  });
});

describe('keygen.updateLicense', () => {
  it('patches license with maxUses', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.updateLicense('license-789', { maxUses: 50 });

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`${KEYGEN_BASE}/licenses/license-789`);
    expect(opts.method).toBe('PATCH');

    const body = JSON.parse(opts.body);
    expect(body.data.attributes.maxUses).toBe(50);
  });
});

describe('keygen.incrementUsage', () => {
  it('increments usage and returns new count', async () => {
    global.fetch = mockFetchSuccess({
      data: { attributes: { uses: 4, maxUses: 50 } },
    });

    const result = await keygen.incrementUsage('license-789');

    expect(result.uses).toBe(4);
    expect(result.maxUses).toBe(50);

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/actions/increment-usage');
    expect(opts.method).toBe('POST');
  });

  it('handles maxUses exceeded (422)', async () => {
    global.fetch = mockFetchError(422, 'LICENSE_LIMIT_EXCEEDED', 'Usage limit reached');

    await expect(keygen.incrementUsage('license-789')).rejects.toThrow(
      KeygenApiError,
    );
  });
});

describe('keygen.renewLicense', () => {
  it('sends POST to renew action', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.renewLicense('license-789');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`${KEYGEN_BASE}/licenses/license-789/actions/renew`);
    expect(opts.method).toBe('POST');
  });
});

describe('keygen.suspendLicense', () => {
  it('sends POST to suspend action', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.suspendLicense('license-789');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`${KEYGEN_BASE}/licenses/license-789/actions/suspend`);
    expect(opts.method).toBe('POST');
  });
});

describe('keygen.reinstateLicense', () => {
  it('sends POST to reinstate action', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.reinstateLicense('license-789');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(
      `${KEYGEN_BASE}/licenses/license-789/actions/reinstate`,
    );
    expect(opts.method).toBe('POST');
  });
});

describe('keygen.resetLicenseUsage', () => {
  it('sends POST to reset-usage action', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.resetLicenseUsage('license-789');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(
      `${KEYGEN_BASE}/licenses/license-789/actions/reset-usage`,
    );
    expect(opts.method).toBe('POST');
  });
});

describe('keygen.activateMachine', () => {
  it('creates machine with correct fingerprint and license relationship', async () => {
    const mockMachine = {
      id: 'machine-001',
      type: 'machines',
      attributes: {
        fingerprint: 'fp-abc123',
        name: 'Steves-MacBook-Pro',
        platform: 'darwin',
        hostname: 'Steves-MacBook-Pro.local',
        createdAt: '2026-01-01T00:00:00Z',
      },
    };

    global.fetch = mockFetchSuccess({ data: mockMachine });

    const result = await keygen.activateMachine('license-789', {
      fingerprint: 'fp-abc123',
      name: 'Steves-MacBook-Pro',
      platform: 'darwin',
      hostname: 'Steves-MacBook-Pro.local',
    });

    expect(result.id).toBe('machine-001');
    expect(result.attributes.fingerprint).toBe('fp-abc123');

    const body = JSON.parse(
      (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1].body,
    );
    expect(body.data.type).toBe('machines');
    expect(body.data.attributes.fingerprint).toBe('fp-abc123');
    expect(body.data.relationships.license.data.id).toBe('license-789');
  });
});

describe('keygen.deactivateMachine', () => {
  it('sends DELETE for machine', async () => {
    global.fetch = mockFetchNoContent();

    await keygen.deactivateMachine('machine-001');

    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe(`${KEYGEN_BASE}/machines/machine-001`);
    expect(opts.method).toBe('DELETE');
  });
});

describe('KeygenApiError', () => {
  it('throws on non-ok responses with error details', async () => {
    global.fetch = mockFetchError(404, 'NOT_FOUND', 'Resource not found');

    try {
      await keygen.renewLicense('nonexistent');
      expect.fail('Should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(KeygenApiError);
      const apiErr = err as KeygenApiError;
      expect(apiErr.status).toBe(404);
      expect(apiErr.code).toBe('NOT_FOUND');
      expect(apiErr.detail).toBe('Resource not found');
    }
  });
});
