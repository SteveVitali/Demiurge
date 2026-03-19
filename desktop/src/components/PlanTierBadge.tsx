import { cn } from '@/lib/utils';

const TIER_COLORS: Record<string, string> = {
  trial: 'bg-yellow-500/20 text-yellow-400',
  starter: 'bg-green-500/20 text-green-400',
  pro: 'bg-blue-500/20 text-blue-400',
  team: 'bg-purple-500/20 text-purple-400',
  enterprise: 'bg-indigo-500/20 text-indigo-400',
};

const DEFAULT_COLOR = 'bg-green-500/20 text-green-400';

export function planTierColor(tier: string): string {
  return TIER_COLORS[tier] ?? DEFAULT_COLOR;
}

const TIER_TEXT_COLORS: Record<string, string> = {
  trial: 'text-yellow-400',
  starter: 'text-green-400',
  pro: 'text-blue-400',
  team: 'text-purple-400',
  enterprise: 'text-indigo-400',
};

const DEFAULT_TEXT_COLOR = 'text-green-400';

export function planTierTextColor(tier: string): string {
  return TIER_TEXT_COLORS[tier] ?? DEFAULT_TEXT_COLOR;
}

export function PlanTierBadge({ tier, className }: { tier: string; className?: string }) {
  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase',
        planTierColor(tier),
        className,
      )}
    >
      {tier}
    </span>
  );
}
