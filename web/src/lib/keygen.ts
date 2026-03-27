/**
 * Thin wrapper around the Keygen.sh REST API.
 * All calls use the product token for auth. No Keygen SDK — raw fetch.
 *
 * @see https://keygen.sh/docs/api/
 */

import { env } from './env';

function getKeygenBase(): string {
  return `https://api.keygen.sh/v1/accounts/${env.KEYGEN_ACCOUNT_ID}`;
}

const getHeaders = (): Record<string, string> => ({
  'Authorization': `Bearer ${env.KEYGEN_PRODUCT_TOKEN}`,
  'Content-Type': 'application/vnd.api+json',
  'Accept': 'application/vnd.api+json',
});

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface KeygenUser {
  id: string;
  type: 'users';
  attributes: {
    email: string;
    metadata: Record<string, string>;
    createdAt: string;
  };
}

export interface KeygenLicense {
  id: string;
  type: 'licenses';
  attributes: {
    key: string;
    status: string;
    uses: number;
    maxUses: number | null;
    expiry: string | null;
    metadata: Record<string, string>;
    createdAt: string;
  };
}

export interface KeygenValidation {
  valid: boolean;
  code: string;
  detail: string;
  metadata: {
    id?: string;
    key?: string;
    uses?: number;
    maxUses?: number | null;
    expiry?: string | null;
    status?: string;
  };
  entitlements?: Array<{ attributes: { code: string } }>;
}

export interface KeygenMachine {
  id: string;
  type: 'machines';
  attributes: {
    fingerprint: string;
    name: string;
    platform: string;
    hostname: string;
    createdAt: string;
  };
}

export class KeygenApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly detail: string,
  ) {
    super(`Keygen API error [${status}] ${code}: ${detail}`);
    this.name = 'KeygenApiError';
  }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

async function keygenFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = `${getKeygenBase()}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      ...getHeaders(),
      ...(options.headers as Record<string, string> ?? {}),
    },
  });

  if (!res.ok) {
    let code = 'UNKNOWN';
    let detail = res.statusText;
    try {
      const body = await res.json();
      const firstError = body.errors?.[0];
      if (firstError) {
        code = firstError.code ?? code;
        detail = firstError.detail ?? detail;
      }
    } catch {
      // ignore parse errors
    }
    throw new KeygenApiError(res.status, code, detail);
  }

  // Some actions return 204 No Content
  if (res.status === 204) {
    return undefined as T;
  }

  return res.json();
}

// ---------------------------------------------------------------------------
// Keygen API client
// ---------------------------------------------------------------------------

export const keygen = {
  // ---- Users ----

  async createUser(params: {
    email: string;
    metadata: Record<string, string>;
  }): Promise<KeygenUser> {
    const body = {
      data: {
        type: 'users',
        attributes: {
          email: params.email,
          metadata: params.metadata,
        },
      },
    };
    const res = await keygenFetch<{ data: KeygenUser }>('/users', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return res.data;
  },

  // ---- Licenses ----

  async createLicense(params: {
    policyId: string;
    userId: string;
  }): Promise<KeygenLicense> {
    const body = {
      data: {
        type: 'licenses',
        relationships: {
          policy: {
            data: { type: 'policies', id: params.policyId },
          },
          user: {
            data: { type: 'users', id: params.userId },
          },
        },
      },
    };
    const res = await keygenFetch<{ data: KeygenLicense }>('/licenses', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return res.data;
  },

  async validateLicenseKey(
    key: string,
    scope?: { fingerprint?: string },
  ): Promise<KeygenValidation> {
    const body: Record<string, unknown> = {
      meta: {
        key,
        ...(scope?.fingerprint ? { scope: { fingerprint: scope.fingerprint } } : {}),
      },
    };
    const res = await keygenFetch<{ meta: KeygenValidation }>(
      '/licenses/actions/validate-key',
      {
        method: 'POST',
        body: JSON.stringify(body),
      },
    );
    return res.meta;
  },

  async transferLicensePolicy(
    licenseId: string,
    policyId: string,
  ): Promise<void> {
    const body = {
      data: { type: 'policies', id: policyId },
    };
    await keygenFetch(`/licenses/${licenseId}/relationships/policy`, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
  },

  async updateLicense(
    licenseId: string,
    attrs: { maxUses?: number },
  ): Promise<void> {
    const body = {
      data: {
        type: 'licenses',
        attributes: attrs,
      },
    };
    await keygenFetch(`/licenses/${licenseId}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    });
  },

  async renewLicense(licenseId: string): Promise<void> {
    await keygenFetch(`/licenses/${licenseId}/actions/renew`, {
      method: 'POST',
    });
  },

  async suspendLicense(licenseId: string): Promise<void> {
    await keygenFetch(`/licenses/${licenseId}/actions/suspend`, {
      method: 'POST',
    });
  },

  async reinstateLicense(licenseId: string): Promise<void> {
    await keygenFetch(`/licenses/${licenseId}/actions/reinstate`, {
      method: 'POST',
    });
  },

  async incrementUsage(
    licenseId: string,
    increment: number = 1,
  ): Promise<{ uses: number; maxUses: number }> {
    const body = {
      meta: { increment },
    };
    const res = await keygenFetch<{
      data: { attributes: { uses: number; maxUses: number } };
    }>(`/licenses/${licenseId}/actions/increment-usage`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return {
      uses: res.data.attributes.uses,
      maxUses: res.data.attributes.maxUses,
    };
  },

  async resetLicenseUsage(licenseId: string): Promise<void> {
    await keygenFetch(`/licenses/${licenseId}/actions/reset-usage`, {
      method: 'POST',
    });
  },

  // ---- Machines ----

  async activateMachine(
    licenseId: string,
    params: {
      fingerprint: string;
      name: string;
      platform: string;
      hostname?: string;
    },
  ): Promise<KeygenMachine> {
    const body = {
      data: {
        type: 'machines',
        attributes: {
          fingerprint: params.fingerprint,
          name: params.name,
          platform: params.platform,
          ...(params.hostname ? { hostname: params.hostname } : {}),
        },
        relationships: {
          license: {
            data: { type: 'licenses', id: licenseId },
          },
        },
      },
    };
    const res = await keygenFetch<{ data: KeygenMachine }>('/machines', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return res.data;
  },

  async deactivateMachine(machineId: string): Promise<void> {
    await keygenFetch(`/machines/${machineId}`, {
      method: 'DELETE',
    });
  },
};
