/**
 * POST /api/auth/device-authorize
 *
 * Called by the /activate page after the user signs in and enters their device code.
 * Links the device code to the authenticated Clerk user so the CLI poll succeeds.
 *
 * Authentication: Clerk session (user must be signed in on the web).
 */

import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@clerk/nextjs/server';
import { getClerkUser } from '@/lib/clerk-helpers';
import { authorizeDeviceCode } from '@/lib/device-code-store';
import type { ClerkPublicMetadata } from '@/lib/clerk-helpers';

export async function POST(req: NextRequest) {
  const { userId } = await auth();
  if (!userId) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  let body: { user_code?: string };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 });
  }

  const userCode = body.user_code?.trim().toUpperCase();
  if (!userCode) {
    return NextResponse.json(
      { error: 'Missing user_code' },
      { status: 400 },
    );
  }

  try {
    const user = await getClerkUser(userId);
    const publicMeta = user.publicMetadata as ClerkPublicMetadata;
    const email =
      user.emailAddresses[0]?.emailAddress ?? '';

    const authorized = authorizeDeviceCode(userCode, {
      clerkUserId: userId,
      userEmail: email,
      licenseKey: publicMeta.license_key ?? '',
      planTier: publicMeta.plan_tier ?? 'trial',
    });

    if (!authorized) {
      return NextResponse.json(
        { error: 'Invalid or expired code. Please try again.' },
        { status: 404 },
      );
    }

    return NextResponse.json({ success: true });
  } catch (err) {
    console.error('Error authorizing device code:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
