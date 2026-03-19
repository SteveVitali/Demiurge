/**
 * Clerk metadata helpers for reading/writing user public/private metadata.
 */

import { clerkClient } from '@clerk/nextjs/server';

export interface ClerkPublicMetadata {
  plan_tier?: string;
  license_key?: string;
  keygen_license_id?: string;
}

export interface ClerkPrivateMetadata {
  keygen_user_id?: string;
  stripe_customer_id?: string;
  stripe_subscription_id?: string;
}

/**
 * Get a Clerk user by ID with typed metadata.
 */
export async function getClerkUser(userId: string) {
  const client = await clerkClient();
  return client.users.getUser(userId);
}

/**
 * Update a Clerk user's public and/or private metadata.
 */
export async function updateClerkUserMetadata(
  userId: string,
  params: {
    publicMetadata?: Partial<ClerkPublicMetadata>;
    privateMetadata?: Partial<ClerkPrivateMetadata>;
  },
) {
  const client = await clerkClient();
  return client.users.updateUserMetadata(userId, {
    publicMetadata: params.publicMetadata ?? {},
    privateMetadata: params.privateMetadata ?? {},
  });
}

/**
 * Find a Clerk user by searching for a matching stripe_customer_id.
 * Clerk doesn't natively support metadata search, so we look up via
 * email list or iterate. For MVP, we store clerk_user_id in Stripe metadata
 * to avoid this search, but this is a fallback.
 */
export async function findClerkUserByStripeCustomerId(
  stripeCustomerId: string,
) {
  const client = await clerkClient();
  // Clerk doesn't have metadata-based search, so we need to iterate.
  // In practice, we avoid this by storing clerk_user_id in Stripe metadata.
  // This is a safety-net fallback that pages through users.
  let offset = 0;
  const limit = 100;
  while (true) {
    const { data: users } = await client.users.getUserList({ limit, offset });
    if (users.length === 0) break;

    const match = users.find(
      (u) =>
        (u.privateMetadata as ClerkPrivateMetadata)?.stripe_customer_id ===
        stripeCustomerId,
    );
    if (match) return match;
    offset += limit;
  }
  return null;
}
