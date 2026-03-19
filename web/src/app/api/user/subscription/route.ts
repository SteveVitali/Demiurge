/**
 * GET /api/user/subscription
 *
 * Get the current user's subscription details for display in the desktop app.
 * Authentication: Bearer token (Clerk JWT).
 */

import { NextResponse } from 'next/server';
import { auth } from '@clerk/nextjs/server';
import { getClerkUser } from '@/lib/clerk-helpers';
import { getSubscription } from '@/lib/stripe-helpers';
import { keygen } from '@/lib/keygen';
import { PLAN_CONFIG, type PlanTier } from '@/lib/constants';
import type { ClerkPublicMetadata, ClerkPrivateMetadata } from '@/lib/clerk-helpers';

export async function GET() {
  const { userId } = await auth();
  if (!userId) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const user = await getClerkUser(userId);
    const publicMeta = user.publicMetadata as ClerkPublicMetadata;
    const privateMeta = user.privateMetadata as ClerkPrivateMetadata;

    const planTier = (publicMeta.plan_tier ?? 'trial') as PlanTier;
    const keygenLicenseId = publicMeta.keygen_license_id;
    const stripeSubscriptionId = privateMeta.stripe_subscription_id;

    let status: string = 'active';
    let currentPeriodEnd: string | null = null;
    let cancelAtPeriodEnd = false;

    // Get Stripe subscription details if available
    if (stripeSubscriptionId) {
      try {
        const subscription = await getSubscription(stripeSubscriptionId);
        status = subscription.status;
        currentPeriodEnd = new Date(
          subscription.current_period_end * 1000,
        ).toISOString();
        cancelAtPeriodEnd = subscription.cancel_at_period_end;
      } catch {
        // Stripe subscription not found — might be trial-only user
      }
    }

    // Get usage from Keygen
    let usesThisPeriod = 0;
    const maxUses = PLAN_CONFIG[planTier]?.maxRuns ?? 0;

    if (keygenLicenseId) {
      try {
        const validation = await keygen.validateLicenseKey(
          publicMeta.license_key!,
        );
        usesThisPeriod = validation.metadata.uses ?? 0;
      } catch {
        // License validation failed — use defaults
      }
    }

    return NextResponse.json({
      plan_tier: planTier,
      status,
      current_period_end: currentPeriodEnd,
      uses_this_period: usesThisPeriod,
      max_uses: maxUses,
      cancel_at_period_end: cancelAtPeriodEnd,
    });
  } catch (err) {
    console.error('Error fetching subscription info:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
