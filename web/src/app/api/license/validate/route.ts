/**
 * GET /api/license/validate
 *
 * Proxy license validation to Keygen. The CLI/desktop app calls this instead
 * of Keygen directly to keep the Keygen account ID private.
 *
 * Authentication: Bearer token (Clerk JWT) OR license key in X-License-Key header.
 * Requires X-Machine-Fingerprint header for machine-scoped validation.
 */

import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@clerk/nextjs/server';
import { keygen, KeygenApiError } from '@/lib/keygen';
import { getClerkUser } from '@/lib/clerk-helpers';
import { PLAN_CONFIG, type PlanTier } from '@/lib/constants';
import type { ClerkPublicMetadata } from '@/lib/clerk-helpers';

export async function GET(req: NextRequest) {
  const licenseKey = req.headers.get('x-license-key');
  const fingerprint = req.headers.get('x-machine-fingerprint');

  let resolvedLicenseKey = licenseKey;

  // If no license key header, try to get it from the authenticated user's metadata
  if (!resolvedLicenseKey) {
    const { userId } = await auth();
    if (!userId) {
      return NextResponse.json(
        { error: 'Missing X-License-Key header or Bearer token' },
        { status: 401 },
      );
    }

    const user = await getClerkUser(userId);
    resolvedLicenseKey = (user.publicMetadata as ClerkPublicMetadata)
      ?.license_key ?? null;

    if (!resolvedLicenseKey) {
      return NextResponse.json(
        { valid: false, code: 'NOT_FOUND', message: 'No license key found for user' },
        { status: 404 },
      );
    }
  }

  try {
    const validation = await keygen.validateLicenseKey(
      resolvedLicenseKey,
      fingerprint ? { fingerprint } : undefined,
    );

    if (validation.valid) {
      // Determine plan tier from the license metadata or policy
      const planTier = (validation.metadata.status === 'ACTIVE' ? 'starter' : 'trial') as PlanTier;

      // Gather entitlements from Keygen response
      const entitlements = validation.entitlements?.map(
        (e) => e.attributes.code,
      ) ?? [];

      return NextResponse.json({
        valid: true,
        code: 'VALID',
        plan_tier: planTier,
        uses: validation.metadata.uses ?? 0,
        max_uses: validation.metadata.maxUses ?? 0,
        expiry: validation.metadata.expiry ?? null,
        entitlements,
      });
    }

    // Map Keygen validation codes to HTTP responses
    const code = validation.code;

    switch (code) {
      case 'NOT_FOUND':
        return NextResponse.json(
          { valid: false, code: 'NOT_FOUND' },
          { status: 404 },
        );

      case 'EXPIRED':
        return NextResponse.json(
          { valid: false, code: 'EXPIRED' },
          { status: 403 },
        );

      case 'SUSPENDED':
        return NextResponse.json(
          { valid: false, code: 'SUSPENDED' },
          { status: 403 },
        );

      case 'TOO_MANY_MACHINES':
        return NextResponse.json(
          { valid: false, code: 'TOO_MANY_MACHINES' },
          { status: 403 },
        );

      case 'NO_MACHINE':
      case 'NO_MACHINES':
      case 'FINGERPRINT_SCOPE_MISMATCH':
        return NextResponse.json(
          {
            valid: false,
            code: 'NO_MACHINE',
            message: 'Machine not activated. Run `demiurge activate`.',
          },
          { status: 403 },
        );

      default:
        return NextResponse.json(
          { valid: false, code, message: validation.detail },
          { status: 403 },
        );
    }
  } catch (err) {
    if (err instanceof KeygenApiError) {
      console.error(`Keygen validation error: ${err.message}`);
      return NextResponse.json(
        { valid: false, code: err.code, message: err.detail },
        { status: err.status >= 500 ? 502 : err.status },
      );
    }
    console.error('License validation error:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
