/**
 * POST /api/webhooks/stripe
 *
 * Handles Stripe billing events. Verified via stripe.webhooks.constructEvent().
 *
 * Events handled:
 * - checkout.session.completed → new subscription (trial → paid conversion)
 * - invoice.paid → recurring renewal (extend license, reset usage)
 * - invoice.payment_failed → log warning (Stripe handles retries)
 * - customer.subscription.updated → plan change (upgrade/downgrade)
 * - customer.subscription.deleted → cancellation (suspend license)
 */

import { NextRequest, NextResponse } from 'next/server';
import type Stripe from 'stripe';
import { constructWebhookEvent, getStripe } from '@/lib/stripe-helpers';
import { keygen } from '@/lib/keygen';
import {
  getClerkUser,
  updateClerkUserMetadata,
  findClerkUserByStripeCustomerId,
} from '@/lib/clerk-helpers';
import {
  getPriceToPolicyMap,
  getPriceToTierMap,
  getPlanMaxUses,
} from '@/lib/constants';
import type { ClerkPublicMetadata, ClerkPrivateMetadata } from '@/lib/clerk-helpers';

export async function POST(req: NextRequest) {
  const signature = req.headers.get('stripe-signature');
  if (!signature) {
    return NextResponse.json(
      { error: 'Missing stripe-signature header' },
      { status: 401 },
    );
  }

  const rawBody = await req.text();

  let event: Stripe.Event;
  try {
    event = constructWebhookEvent(rawBody, signature);
  } catch (err) {
    console.error('Stripe webhook verification failed:', err);
    return NextResponse.json(
      { error: 'Webhook verification failed' },
      { status: 401 },
    );
  }

  try {
    switch (event.type) {
      case 'checkout.session.completed':
        await handleCheckoutCompleted(
          event.data.object as Stripe.Checkout.Session,
        );
        break;

      case 'invoice.paid':
        await handleInvoicePaid(event.data.object as Stripe.Invoice);
        break;

      case 'invoice.payment_failed':
        await handleInvoicePaymentFailed(event.data.object as Stripe.Invoice);
        break;

      case 'customer.subscription.updated':
        await handleSubscriptionUpdated(
          event.data.object as Stripe.Subscription,
        );
        break;

      case 'customer.subscription.deleted':
        await handleSubscriptionDeleted(
          event.data.object as Stripe.Subscription,
        );
        break;

      default:
        console.log(`Unhandled Stripe webhook event: ${event.type}`);
    }

    return NextResponse.json({ received: true }, { status: 200 });
  } catch (err) {
    console.error(`Error handling Stripe webhook ${event.type}:`, err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}

// ---------------------------------------------------------------------------
// checkout.session.completed — new subscription created
// ---------------------------------------------------------------------------

async function handleCheckoutCompleted(session: Stripe.Checkout.Session) {
  const clerkUserId = session.metadata?.clerk_user_id;
  if (!clerkUserId) {
    console.error('checkout.session.completed: missing clerk_user_id in metadata');
    return;
  }

  // Expand line items to get the price ID
  const stripe = getStripe();
  const fullSession = await stripe.checkout.sessions.retrieve(session.id, {
    expand: ['line_items.data.price'],
  });

  const priceId = fullSession.line_items?.data[0]?.price?.id;
  if (!priceId) {
    console.error('checkout.session.completed: no price ID found in line items');
    return;
  }

  const priceToPolicyMap = getPriceToPolicyMap();
  const priceToTierMap = getPriceToTierMap();

  const targetPolicyId = priceToPolicyMap[priceId];
  const planTier = priceToTierMap[priceId];

  if (!targetPolicyId || !planTier) {
    console.error(`checkout.session.completed: unknown price ID ${priceId}`);
    return;
  }

  const maxUses = getPlanMaxUses(planTier);

  // Get the Clerk user's Keygen license ID
  const user = await getClerkUser(clerkUserId);
  const keygenLicenseId = (user.publicMetadata as ClerkPublicMetadata)
    ?.keygen_license_id;

  if (!keygenLicenseId) {
    console.error(
      `checkout.session.completed: no keygen_license_id for user ${clerkUserId}`,
    );
    return;
  }

  // 1. Transfer license to paid policy (resets expiry via RESET_EXPIRY strategy)
  await keygen.transferLicensePolicy(keygenLicenseId, targetPolicyId);

  // 2. Update license maxUses
  await keygen.updateLicense(keygenLicenseId, { maxUses });

  // 3. Reset usage counter
  await keygen.resetLicenseUsage(keygenLicenseId);

  // 4. Update Clerk metadata
  await updateClerkUserMetadata(clerkUserId, {
    publicMetadata: {
      plan_tier: planTier,
    },
    privateMetadata: {
      stripe_customer_id: session.customer as string,
      stripe_subscription_id: session.subscription as string,
    },
  });

  console.log(
    `Upgraded user ${clerkUserId} to ${planTier} (price: ${priceId})`,
  );
}

// ---------------------------------------------------------------------------
// invoice.paid — recurring renewal
// ---------------------------------------------------------------------------

async function handleInvoicePaid(invoice: Stripe.Invoice) {
  // Only handle subscription renewals, not the first payment
  if (invoice.billing_reason !== 'subscription_cycle') return;

  const stripeCustomerId = invoice.customer as string;

  // Try to find the Clerk user via subscription metadata first
  const subscriptionId = invoice.subscription as string;
  let clerkUserId: string | null = null;

  if (subscriptionId) {
    const stripe = getStripe();
    const subscription = await stripe.subscriptions.retrieve(subscriptionId);
    clerkUserId = subscription.metadata?.clerk_user_id ?? null;
  }

  let user;
  if (clerkUserId) {
    user = await getClerkUser(clerkUserId);
  } else {
    // Fallback: search Clerk users by stripe_customer_id
    user = await findClerkUserByStripeCustomerId(stripeCustomerId);
    if (!user) {
      console.error(
        `invoice.paid: could not find Clerk user for Stripe customer ${stripeCustomerId}`,
      );
      return;
    }
  }

  const keygenLicenseId = (user.publicMetadata as ClerkPublicMetadata)
    ?.keygen_license_id;
  if (!keygenLicenseId) {
    console.error(`invoice.paid: no keygen_license_id for user ${user.id}`);
    return;
  }

  // 1. Renew license (extends expiry by policy duration)
  await keygen.renewLicense(keygenLicenseId);

  // 2. Reset monthly usage counter
  await keygen.resetLicenseUsage(keygenLicenseId);

  console.log(`Renewed license for user ${user.id}`);
}

// ---------------------------------------------------------------------------
// invoice.payment_failed
// ---------------------------------------------------------------------------

async function handleInvoicePaymentFailed(invoice: Stripe.Invoice) {
  const stripeCustomerId = invoice.customer as string;
  console.warn(
    `Payment failed for Stripe customer ${stripeCustomerId}, invoice ${invoice.id}. Stripe will retry automatically.`,
  );
}

// ---------------------------------------------------------------------------
// customer.subscription.updated — plan change (upgrade/downgrade)
// ---------------------------------------------------------------------------

async function handleSubscriptionUpdated(subscription: Stripe.Subscription) {
  const priceId = subscription.items.data[0]?.price?.id;
  if (!priceId) return;

  const priceToPolicyMap = getPriceToPolicyMap();
  const priceToTierMap = getPriceToTierMap();

  const targetPolicyId = priceToPolicyMap[priceId];
  const planTier = priceToTierMap[priceId];

  if (!targetPolicyId || !planTier) {
    // Unknown price — might be an unrelated subscription update
    return;
  }

  const maxUses = getPlanMaxUses(planTier);

  const clerkUserId = subscription.metadata?.clerk_user_id;
  if (!clerkUserId) return;

  const user = await getClerkUser(clerkUserId);
  const keygenLicenseId = (user.publicMetadata as ClerkPublicMetadata)
    ?.keygen_license_id;

  if (!keygenLicenseId) {
    console.error(
      `subscription.updated: no keygen_license_id for user ${clerkUserId}`,
    );
    return;
  }

  // Transfer to new policy
  await keygen.transferLicensePolicy(keygenLicenseId, targetPolicyId);
  await keygen.updateLicense(keygenLicenseId, { maxUses });

  // Update Clerk metadata
  await updateClerkUserMetadata(clerkUserId, {
    publicMetadata: { plan_tier: planTier },
  });

  console.log(`Plan changed for user ${clerkUserId} to ${planTier}`);
}

// ---------------------------------------------------------------------------
// customer.subscription.deleted — cancellation
// ---------------------------------------------------------------------------

async function handleSubscriptionDeleted(subscription: Stripe.Subscription) {
  const clerkUserId = subscription.metadata?.clerk_user_id;
  if (!clerkUserId) return;

  const user = await getClerkUser(clerkUserId);
  const keygenLicenseId = (user.publicMetadata as ClerkPublicMetadata)
    ?.keygen_license_id;

  if (!keygenLicenseId) {
    console.error(
      `subscription.deleted: no keygen_license_id for user ${clerkUserId}`,
    );
    return;
  }

  // Suspend the license
  await keygen.suspendLicense(keygenLicenseId);

  // Update Clerk metadata
  await updateClerkUserMetadata(clerkUserId, {
    publicMetadata: { plan_tier: 'expired' },
  });

  console.log(`Subscription cancelled for user ${clerkUserId}, license suspended`);
}
