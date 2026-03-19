/**
 * GET /api/auth/device-poll?device_code=dc_abc123...
 *
 * CLI polls this endpoint to check if the user has completed auth via the
 * device code flow. Returns pending, authorized, or expired.
 *
 * Rate limiting: max 1 request per 5 seconds per device_code is enforced
 * client-side by the poll_interval. Server-side rate limiting can be added
 * via Vercel middleware or a rate limiter library.
 */

import { NextRequest, NextResponse } from 'next/server';
import { pollDeviceCode } from '@/lib/device-code-store';

export async function GET(req: NextRequest) {
  const deviceCode = req.nextUrl.searchParams.get('device_code');

  if (!deviceCode) {
    return NextResponse.json(
      { error: 'Missing device_code parameter' },
      { status: 400 },
    );
  }

  const result = pollDeviceCode(deviceCode);

  switch (result.status) {
    case 'pending':
      return NextResponse.json({ status: 'pending' });

    case 'authorized':
      return NextResponse.json({
        status: 'authorized',
        license_key: result.licenseKey,
        plan_tier: result.planTier,
        user_email: result.userEmail,
      });

    case 'expired':
      return NextResponse.json({ status: 'expired' });
  }
}
