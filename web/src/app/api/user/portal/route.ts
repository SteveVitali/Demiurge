/**
 * POST /api/user/portal
 *
 * Create a Stripe Customer Portal session for managing billing.
 * Authentication: Bearer token (Clerk JWT).
 */

import { NextResponse } from 'next/server';
import { auth } from '@clerk/nextjs/server';
import { getClerkUser } from '@/lib/clerk-helpers';
import { createPortalSession } from '@/lib/stripe-helpers';
import { env } from '@/lib/env';
import type { ClerkPrivateMetadata } from '@/lib/clerk-helpers';

export async function POST() {
  const { userId } = await auth();
  if (!userId) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const user = await getClerkUser(userId);
    const privateMeta = user.privateMetadata as ClerkPrivateMetadata;
    const stripeCustomerId = privateMeta.stripe_customer_id;

    if (!stripeCustomerId) {
      return NextResponse.json(
        {
          error: 'No billing account found. Please upgrade to a paid plan first.',
        },
        { status: 404 },
      );
    }

    const session = await createPortalSession({
      stripeCustomerId,
      returnUrl: `${env.NEXT_PUBLIC_APP_URL}/`,
    });

    return NextResponse.json({ url: session.url });
  } catch (err) {
    console.error('Error creating portal session:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
