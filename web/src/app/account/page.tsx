import type { Metadata } from 'next';
import { currentUser } from '@clerk/nextjs/server';
import { redirect } from 'next/navigation';
import { LicenseKeyCard } from '@/components/account/LicenseKeyCard';
import { PlanCard } from '@/components/account/PlanCard';
import { UsageCard } from '@/components/account/UsageCard';
import Link from 'next/link';
import { Settings } from 'lucide-react';

export const metadata: Metadata = {
  title: 'Account',
  description: 'Manage your Demiurge account, license key, and billing.',
};

export const dynamic = 'force-dynamic';

export default async function AccountPage() {
  const user = await currentUser();
  if (!user) redirect('/sign-in?redirect_url=/account');

  const publicMeta = user.publicMetadata as Record<string, string | number | undefined>;
  const licenseKey = (publicMeta.license_key as string) ?? null;
  const planTier = (publicMeta.plan_tier as string) ?? null;
  const runsUsed = Number(publicMeta.runs_used ?? 0);
  const runsMax = Number(publicMeta.runs_max ?? 0);

  return (
    <div className="py-24">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-text-primary">Account</h1>
          <p className="mt-2 text-text-secondary">
            Manage your subscription, license key, and usage.
          </p>
        </div>

        <div className="space-y-6">
          <LicenseKeyCard licenseKey={licenseKey} />
          <PlanCard planTier={planTier} />
          <UsageCard runsUsed={runsUsed} runsMax={runsMax} />

          <div className="rounded-xl border border-border bg-surface p-6">
            <div className="flex items-center gap-2 mb-4">
              <Settings className="h-5 w-5 text-primary" />
              <h2 className="text-base font-semibold text-text-primary">Billing</h2>
            </div>
            <p className="text-sm text-text-muted mb-4">
              Manage your payment method, view invoices, and update your subscription.
            </p>
            <Link
              href="/account/billing"
              className="inline-flex items-center rounded-lg border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:text-text-primary hover:border-border-light transition-colors"
            >
              Manage Billing
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
