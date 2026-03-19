/**
 * GET /api/user/usage
 *
 * Spec 05 §4.3: Return the current user's usage for the current billing period.
 * Reads `uses` and `maxUses` from Keygen license validation.
 * Token usage comes from the Supabase table (future) or is estimated from Keygen metadata.
 *
 * Authentication: Bearer token (Clerk JWT) OR license key in X-License-Key header.
 */

import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@clerk/nextjs/server';
import { keygen } from '@/lib/keygen';
import { getClerkUser } from '@/lib/clerk-helpers';
import type { ClerkPublicMetadata } from '@/lib/clerk-helpers';

export async function GET(req: NextRequest) {
  const licenseKey = req.headers.get('x-license-key');

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
    const publicMeta = user.publicMetadata as ClerkPublicMetadata;
    resolvedLicenseKey = publicMeta?.license_key ?? null;

    if (!resolvedLicenseKey) {
      return NextResponse.json(
        { error: 'No license key found for user' },
        { status: 404 },
      );
    }
  }

  try {
    const validation = await keygen.validateLicenseKey(resolvedLicenseKey);

    const uses = validation.metadata.uses ?? 0;
    const maxUses = validation.metadata.maxUses ?? 0;
    const expiry = validation.metadata.expiry ?? null;

    // Calculate billing period dates
    // If expiry is set, the period end is the expiry date.
    // The period start is one month before expiry (approximation for monthly billing).
    let periodStart: string | null = null;
    let periodEnd: string | null = null;

    if (expiry) {
      periodEnd = expiry;
      try {
        const endDate = new Date(expiry);
        const startDate = new Date(endDate);
        startDate.setMonth(startDate.getMonth() - 1);
        periodStart = startDate.toISOString();
      } catch {
        // If date parsing fails, leave null
      }
    }

    return NextResponse.json({
      runs: {
        used: uses,
        limit: maxUses,
        period_start: periodStart,
        period_end: periodEnd,
      },
      tokens: {
        // Future: aggregate from Supabase token_usage table
        // For now, return 0 — token tracking is local-only in v1
        used: 0,
        limit: 0,
        period_start: periodStart,
        period_end: periodEnd,
      },
    });
  } catch (err) {
    console.error('Usage fetch error:', err);
    return NextResponse.json(
      { error: 'Failed to fetch usage data' },
      { status: 500 },
    );
  }
}
