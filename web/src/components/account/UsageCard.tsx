import { BarChart3 } from 'lucide-react';

interface UsageCardProps {
  runsUsed: number;
  runsMax: number;
}

export function UsageCard({ runsUsed, runsMax }: UsageCardProps) {
  const percentage = runsMax > 0 ? Math.min((runsUsed / runsMax) * 100, 100) : 0;
  const isUnlimited = runsMax < 0;

  return (
    <div className="rounded-xl border border-border bg-surface p-6">
      <div className="flex items-center gap-2 mb-4">
        <BarChart3 className="h-5 w-5 text-primary" />
        <h2 className="text-base font-semibold text-text-primary">Usage</h2>
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between text-sm">
          <span className="text-text-muted">Runs this month</span>
          <span className="font-medium text-text-primary">
            {isUnlimited ? `${runsUsed} / ∞` : `${runsUsed} / ${runsMax}`}
          </span>
        </div>

        {!isUnlimited && (
          <div className="w-full h-2 rounded-full bg-bg border border-border overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-500 ${
                percentage > 90
                  ? 'bg-error'
                  : percentage > 70
                    ? 'bg-warning'
                    : 'bg-primary'
              }`}
              style={{ width: `${percentage}%` }}
            />
          </div>
        )}

        {!isUnlimited && percentage > 80 && (
          <p className="text-xs text-warning">
            You&apos;re approaching your monthly limit. Consider upgrading for more runs.
          </p>
        )}
      </div>
    </div>
  );
}
