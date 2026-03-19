/**
 * POST /api/auth/device-code
 *
 * Start a device authorization flow for CLI login.
 * Returns a device_code and user_code. The CLI displays the user_code
 * and polls /api/auth/device-poll until the user completes auth.
 */

import { NextResponse } from 'next/server';
import { createDeviceCode } from '@/lib/device-code-store';
import { env } from '@/lib/env';

export async function POST() {
  const { deviceCode, userCode, expiresIn, pollInterval } = createDeviceCode();

  return NextResponse.json({
    device_code: deviceCode,
    user_code: userCode,
    verification_url: `${env.NEXT_PUBLIC_APP_URL}/activate`,
    expires_in: expiresIn,
    poll_interval: pollInterval,
  });
}
