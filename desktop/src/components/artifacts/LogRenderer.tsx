import { cn } from '@/lib/utils';

interface LogRendererProps {
  content: string;
  className?: string;
}

export function LogRenderer({ content, className }: LogRendererProps) {
  return (
    <pre
      className={cn(
        'overflow-auto whitespace-pre-wrap p-4 text-xs font-mono text-foreground bg-zinc-950 rounded',
        className,
      )}
    >
      {content}
    </pre>
  );
}
