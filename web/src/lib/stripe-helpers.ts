/**
 * Stripe session and portal helpers.
 */

import Stripe from 'stripe';
import { env } from './env';

let _stripe: Stripe | null = null;

export function getStripe(): Stripe {
  if (!_stripe) {
    _stripe = new Stripe(env.STRIPE_SECRET_KEY, {
      apiVersion: '2025-02-24.acacia',
      typescript: true,
    });
  }
  return _stripe;
}

/**
 * Create a Stripe Checkout session for upgrading to a paid plan.
 */
export async function createCheckoutSession(params: {
  priceId: string;
  clerkUserId: string;
  customerEmail: string;
  stripeCustomerId?: string;
  successUrl: string;
  cancelUrl: string;
}): Promise<Stripe.Checkout.Session> {
  const stripe = getStripe();

  const sessionParams: Stripe.Checkout.SessionCreateParams = {
    mode: 'subscription',
    line_items: [{ price: params.priceId, quantity: 1 }],
    success_url: params.successUrl,
    cancel_url: params.cancelUrl,
    metadata: {
      clerk_user_id: params.clerkUserId,
    },
    subscription_data: {
      metadata: {
        clerk_user_id: params.clerkUserId,
      },
    },
  };

  if (params.stripeCustomerId) {
    sessionParams.customer = params.stripeCustomerId;
  } else {
    sessionParams.customer_email = params.customerEmail;
  }

  return stripe.checkout.sessions.create(sessionParams);
}

/**
 * Create a Stripe Customer Portal session for managing billing.
 */
export async function createPortalSession(params: {
  stripeCustomerId: string;
  returnUrl: string;
}): Promise<Stripe.BillingPortal.Session> {
  const stripe = getStripe();
  return stripe.billingPortal.sessions.create({
    customer: params.stripeCustomerId,
    return_url: params.returnUrl,
  });
}

/**
 * Retrieve a Stripe subscription by ID.
 */
export async function getSubscription(
  subscriptionId: string,
): Promise<Stripe.Subscription> {
  const stripe = getStripe();
  return stripe.subscriptions.retrieve(subscriptionId);
}

/**
 * Construct and verify a Stripe webhook event from raw body + signature.
 */
export function constructWebhookEvent(
  rawBody: string | Buffer,
  signature: string,
): Stripe.Event {
  const stripe = getStripe();
  return stripe.webhooks.constructEvent(
    rawBody,
    signature,
    env.STRIPE_WEBHOOK_SECRET,
  );
}
