import Link from 'next/link';
import { CreditCard } from 'lucide-react';

interface PlanCardProps {
  planTier: string | null;
  billingPeriod?: string | null;
  nextRenewal?: string | null;
}

export function PlanCard({ planTier, billingPeriod, nextRenewal }: PlanCardProps) {
  const displayTier = planTier
    ? planTier.charAt(0).toUpperCase() + planTier.slice(1)
    : 'No plan';

  const showUpgrade = !planTier || planTier === 'trial' || planTier === 'starter';

  return (
    <div className="rounded-xl border border-border bg-surface p-6">
      <div className="flex items-center gap-2 mb-4">
        <CreditCard className="h-5 w-5 text-primary" />
        <h2 className="text-base font-semibold text-text-primary">Plan</h2>
      </div>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm text-text-muted">Current plan</span>
          <span className="text-sm font-medium text-text-primary">{displayTier}</span>
        </div>
        {billingPeriod && (
          <div className="flex items-center justify-between">
            <span className="text-sm text-text-muted">Billing</span>
            <span className="text-sm text-text-secondary capitalize">{billingPeriod}</span>
          </div>
        )}
        {nextRenewal && (
          <div className="flex items-center justify-between">
            <span className="text-sm text-text-muted">Next renewal</span>
            <span className="text-sm text-text-secondary">
              {new Date(nextRenewal).toLocaleDateString()}
            </span>
          </div>
        )}
      </div>

      {showUpgrade && (
        <Link
          href="/pricing"
          className="mt-4 inline-flex w-full items-center justify-center rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
        >
          Upgrade
        </Link>
      )}
    </div>
  );
}
