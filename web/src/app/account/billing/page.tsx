import { currentUser } from '@clerk/nextjs/server';
import { redirect } from 'next/navigation';
import { createPortalSession } from '@/lib/stripe-helpers';

export const dynamic = 'force-dynamic';

export default async function BillingPage() {
  const user = await currentUser();
  if (!user) redirect('/sign-in?redirect_url=/account/billing');

  const stripeCustomerId = (user.publicMetadata as Record<string, string>)
    ?.stripe_customer_id;

  if (!stripeCustomerId) {
    redirect('/account');
  }

  const session = await createPortalSession({
    stripeCustomerId,
    returnUrl: `${process.env.NEXT_PUBLIC_APP_URL ?? 'https://demiurge.dev'}/account`,
  });

  redirect(session.url);
}
