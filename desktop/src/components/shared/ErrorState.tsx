import { AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ErrorStateProps {
  message: string;
  className?: string;
}

export function ErrorState({ message, className }: ErrorStateProps) {
  return (
    <div className={cn('flex flex-col items-center gap-3 text-red-400', className)}>
      <AlertCircle className="h-8 w-8" />
      <p className="text-sm">{message}</p>
    </div>
  );
}
