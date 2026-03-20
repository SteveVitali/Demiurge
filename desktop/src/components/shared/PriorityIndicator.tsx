import { cn } from '@/lib/utils';
import type { Priority } from '@/api/types';

interface PriorityIndicatorProps {
  priority: Priority;
  className?: string;
}

const config: Record<Priority, { color: string; label: string }> = {
  Required: { color: 'bg-red-500', label: 'Required' },
  Important: { color: 'bg-yellow-500', label: 'Important' },
  NiceToHave: { color: 'bg-zinc-500', label: 'Nice to Have' },
};

export function PriorityIndicator({ priority, className }: PriorityIndicatorProps) {
  const { color, label } = config[priority];

  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs text-muted-foreground', className)}>
      <span className={cn('h-2 w-2 rounded-full', color)} />
      {label}
    </span>
  );
}
