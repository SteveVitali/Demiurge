import { auth, currentUser } from '@clerk/nextjs/server';
import { getStripe } from '@/lib/stripe-helpers';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) {
    return new Response('Unauthorized', { status: 401 });
  }

  let body: { priceId?: unknown };
  try {
    body = await req.json();
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 });
  }

  const { priceId } = body;
  if (!priceId || typeof priceId !== 'string') {
    return Response.json({ error: 'Missing priceId' }, { status: 400 });
  }

  try {
    const user = await currentUser();
    const stripe = getStripe();
    const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? 'https://demiurge.dev';

    const session = await stripe.checkout.sessions.create({
      mode: 'subscription',
      line_items: [{ price: priceId, quantity: 1 }],
      success_url: `${appUrl}/account?checkout=success`,
      cancel_url: `${appUrl}/pricing?checkout=cancelled`,
      customer_email: user?.emailAddresses[0]?.emailAddress,
      metadata: {
        clerk_user_id: userId,
      },
      subscription_data: {
        metadata: {
          clerk_user_id: userId,
        },
      },
    });

    return Response.json({ url: session.url });
  } catch (err) {
    console.error('Stripe checkout session creation failed:', err);
    return Response.json(
      { error: 'Failed to create checkout session' },
      { status: 500 },
    );
  }
}
