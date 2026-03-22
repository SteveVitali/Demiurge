import { cn } from '@/lib/utils';

interface CostDisplayProps {
  costUsd: number;
  inputTokens?: number;
  outputTokens?: number;
  className?: string;
}

function formatTokens(n: number): string {
  if (n >= 1000) return `${Math.round(n / 1000)}k`;
  return String(n);
}

function getCostColor(costUsd: number): string {
  if (costUsd > 5) return 'text-red-400';
  if (costUsd > 1) return 'text-yellow-400';
  return 'text-emerald-400';
}

export function CostDisplay({ costUsd, inputTokens, outputTokens, className }: CostDisplayProps) {
  const hasTokens = inputTokens !== undefined && outputTokens !== undefined;

  return (
    <span className={cn('inline-flex items-center gap-1 text-xs', className)}>
      <span className={cn('font-medium', getCostColor(costUsd))}>
        ${costUsd.toFixed(2)}
      </span>
      {hasTokens && (
        <span className="text-muted-foreground">
          ({formatTokens(inputTokens)} in / {formatTokens(outputTokens)} out)
        </span>
      )}
    </span>
  );
}
