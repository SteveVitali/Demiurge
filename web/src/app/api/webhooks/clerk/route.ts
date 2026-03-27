/**
 * POST /api/webhooks/clerk
 *
 * Handles Clerk user lifecycle events. Verified via svix signature.
 * Event: user.created → creates Keygen user + trial license, stores in Clerk metadata.
 */

import { NextRequest, NextResponse } from 'next/server';
import { Webhook } from 'svix';
import { env } from '@/lib/env';
import { keygen } from '@/lib/keygen';
import { updateClerkUserMetadata } from '@/lib/clerk-helpers';

interface ClerkWebhookEvent {
  type: string;
  data: {
    id: string;
    email_addresses: Array<{
      email_address: string;
      id: string;
    }>;
    first_name: string | null;
    last_name: string | null;
  };
}

export async function POST(req: NextRequest) {
  const svixId = req.headers.get('svix-id');
  const svixTimestamp = req.headers.get('svix-timestamp');
  const svixSignature = req.headers.get('svix-signature');

  if (!svixId || !svixTimestamp || !svixSignature) {
    return NextResponse.json(
      { error: 'Missing svix headers' },
      { status: 401 },
    );
  }

  const rawBody = await req.text();

  let event: ClerkWebhookEvent;
  try {
    const wh = new Webhook(env.CLERK_WEBHOOK_SECRET);
    event = wh.verify(rawBody, {
      'svix-id': svixId,
      'svix-timestamp': svixTimestamp,
      'svix-signature': svixSignature,
    }) as ClerkWebhookEvent;
  } catch (err) {
    console.error('Clerk webhook verification failed:', err);
    return NextResponse.json(
      { error: 'Webhook verification failed' },
      { status: 401 },
    );
  }

  try {
    switch (event.type) {
      case 'user.created':
        await handleUserCreated(event.data);
        break;
      default:
        console.log(`Unhandled Clerk webhook event: ${event.type}`);
    }

    return NextResponse.json({ received: true }, { status: 200 });
  } catch (err) {
    console.error(`Error handling Clerk webhook ${event.type}:`, err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}

async function handleUserCreated(user: ClerkWebhookEvent['data']) {
  const email = user.email_addresses[0]?.email_address;
  if (!email) {
    console.error('user.created webhook: no email address found');
    return;
  }

  // 1. Create Keygen user
  const keygenUser = await keygen.createUser({
    email,
    metadata: { clerk_user_id: user.id },
  });

  // 2. Create trial license under the trial policy
  const license = await keygen.createLicense({
    policyId: env.KEYGEN_TRIAL_POLICY_ID,
    userId: keygenUser.id,
  });

  // 3. Store Keygen references in Clerk user metadata
  await updateClerkUserMetadata(user.id, {
    publicMetadata: {
      plan_tier: 'trial',
      license_key: license.attributes.key,
      keygen_license_id: license.id,
    },
    privateMetadata: {
      keygen_user_id: keygenUser.id,
    },
  });

  console.log(
    `Created trial license for user ${user.id} (${email}): ${license.attributes.key}`,
  );
}
