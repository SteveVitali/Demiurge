import { Inbox } from 'lucide-react';
import { cn } from '@/lib/utils';

interface EmptyStateProps {
  message: string;
  icon?: React.ReactNode;
  className?: string;
}

export function EmptyState({ message, icon, className }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center gap-2 py-8 text-muted-foreground', className)}>
      {icon ?? <Inbox className="h-8 w-8" />}
      <p className="text-sm">{message}</p>
    </div>
  );
}
