import { cn } from '@/lib/utils';

interface AttemptTabsProps {
  attemptCount: number;
  currentAttempt: number;
  onSelectAttempt: (attempt: number) => void;
}

export function AttemptTabs({ attemptCount, currentAttempt, onSelectAttempt }: AttemptTabsProps) {
  if (attemptCount <= 0) return null;

  const attempts = Array.from({ length: attemptCount }, (_, i) => i + 1);

  return (
    <div className="flex items-center gap-1">
      <span className="mr-2 text-xs text-muted-foreground">Attempt:</span>
      {attempts.map((num) => (
        <button
          key={num}
          onClick={() => onSelectAttempt(num)}
          className={cn(
            'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
            num === currentAttempt
              ? 'bg-accent text-accent-foreground'
              : 'text-muted-foreground hover:bg-accent/50',
          )}
        >
          {num}
        </button>
      ))}
    </div>
  );
}
