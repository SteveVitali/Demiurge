// Spec 05: Shared usage utilities — color thresholds, formatting, canonical type

/** Canonical usage data shape returned by the sidecar /usage endpoint. */
export interface UsageData {
  runs: { used: number; limit: number; periodEnd: string | null };
  tokens: { used: number; limit: number; periodEnd: string | null };
  account?: { email: string; planTier: string; entitlements: string[] };
  offline?: boolean;
}

/** Tailwind bar-fill color class for a given usage percentage. */
export function usageBarColor(pct: number): string {
  if (pct < 60) return 'bg-emerald-500';
  if (pct < 80) return 'bg-yellow-500';
  return 'bg-red-500';
}

/** Tailwind text color class for a given usage percentage. */
export function usageTextColor(pct: number): string {
  if (pct < 60) return 'text-emerald-400';
  if (pct < 80) return 'text-yellow-400';
  return 'text-red-400';
}

/** Format a token count for display (e.g. 1250000 → "1.3M"). */
export function formatTokenCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(0)}k`;
  return String(n);
}
