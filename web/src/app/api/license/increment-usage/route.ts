/**
 * POST /api/license/increment-usage
 *
 * Spec 05 §4.1: Increment the run counter on a Keygen license.
 * Proxies to Keygen's increment-usage action.
 *
 * Authentication: License key in X-License-Key header.
 */

import { NextRequest, NextResponse } from 'next/server';
import { keygen, KeygenApiError } from '@/lib/keygen';

export async function POST(req: NextRequest) {
  const licenseKey = req.headers.get('x-license-key');
  if (!licenseKey) {
    return NextResponse.json(
      { error: 'Missing X-License-Key header' },
      { status: 401 },
    );
  }

  let increment = 1;
  try {
    const body = await req.json();
    if (typeof body.increment === 'number' && body.increment > 0) {
      increment = body.increment;
    }
  } catch {
    // Default to increment=1 if body is missing or invalid
  }

  try {
    // Validate the license key to get the license ID
    const validation = await keygen.validateLicenseKey(licenseKey);
    if (!validation.valid) {
      return NextResponse.json(
        { error: 'Invalid license', code: validation.code },
        { status: 403 },
      );
    }

    const licenseId = validation.metadata.id;
    if (!licenseId) {
      return NextResponse.json(
        { error: 'License ID not found in validation response' },
        { status: 500 },
      );
    }

    // Increment usage
    const result = await keygen.incrementUsage(licenseId, increment);
    return NextResponse.json({
      uses: result.uses,
      maxUses: result.maxUses,
    });
  } catch (err) {
    if (err instanceof KeygenApiError) {
      // Keygen returns 422 when maxUses exceeded
      if (err.status === 422) {
        return NextResponse.json(
          {
            error: 'Usage limit exceeded',
            uses: -1,
            maxUses: -1,
          },
          { status: 422 },
        );
      }
      console.error(`Keygen increment-usage error: ${err.message}`);
      return NextResponse.json(
        { error: err.detail },
        { status: err.status >= 500 ? 502 : err.status },
      );
    }
    console.error('Increment usage error:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
