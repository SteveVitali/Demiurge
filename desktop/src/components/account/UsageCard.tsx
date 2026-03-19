import { useUsage } from '@/hooks/useUsage';
import { cn } from '@/lib/utils';

// Spec 05 §7.2: Usage display card for the account/settings page
// Two progress bars: Runs and Tokens
// Color coding: green (<60%), yellow (60-80%), red (>80%)
export function UsageCard() {
  const { data: usage, isLoading, isError } = useUsage();

  if (isLoading) {
    return (
      <div className="rounded-md border border-border p-4">
        <div className="animate-pulse space-y-3">
          <div className="h-4 w-24 rounded bg-muted" />
          <div className="h-2 w-full rounded bg-muted" />
          <div className="h-2 w-full rounded bg-muted" />
        </div>
      </div>
    );
  }

  if (isError || !usage) {
    return (
      <div className="rounded-md border border-border p-4 text-sm text-muted-foreground">
        Unable to load usage data.
      </div>
    );
  }

  const runPct = usage.runs.limit > 0
    ? Math.round((usage.runs.used / usage.runs.limit) * 100)
    : 0;

  const tokenPct = usage.tokens.limit > 0
    ? Math.round((usage.tokens.used / usage.tokens.limit) * 100)
    : 0;

  const periodEnd = usage.runs.periodEnd
    ? new Date(usage.runs.periodEnd).toLocaleDateString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric',
      })
    : null;

  return (
    <div className="flex flex-col gap-4 rounded-md border border-border p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Usage This Period</h3>
        {periodEnd && (
          <span className="text-xs text-muted-foreground">Resets {periodEnd}</span>
        )}
      </div>

      {usage.offline && (
        <div className="rounded-md bg-yellow-500/10 px-3 py-1.5 text-xs text-yellow-400">
          Showing cached data (offline)
        </div>
      )}

      {/* Runs */}
      <UsageBar
        label="Runs"
        used={usage.runs.used}
        limit={usage.runs.limit}
        pct={runPct}
      />

      {/* Tokens — only show if limit > 0 (future feature) */}
      {usage.tokens.limit > 0 && (
        <UsageBar
          label="Tokens"
          used={usage.tokens.used}
          limit={usage.tokens.limit}
          pct={tokenPct}
          formatValue={formatTokenCount}
        />
      )}
    </div>
  );
}

function UsageBar({
  label,
  used,
  limit,
  pct,
  formatValue,
}: {
  label: string;
  used: number;
  limit: number;
  pct: number;
  formatValue?: (n: number) => string;
}) {
  const fmt = formatValue ?? String;
  const barColor = pct < 60 ? 'bg-emerald-500' : pct < 80 ? 'bg-yellow-500' : 'bg-red-500';
  const textColor = pct < 60 ? 'text-emerald-400' : pct < 80 ? 'text-yellow-400' : 'text-red-400';

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className={cn('font-medium', textColor)}>
          {fmt(used)} / {fmt(limit)} ({pct}%)
        </span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={cn('h-full rounded-full transition-all duration-300', barColor)}
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
    </div>
  );
}

function formatTokenCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(0)}k`;
  return String(n);
}
