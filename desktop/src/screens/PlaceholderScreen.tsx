import { Construction } from 'lucide-react';

interface PlaceholderScreenProps {
  title: string;
  phase: number;
}

export function PlaceholderScreen({ title, phase }: PlaceholderScreenProps) {
  return (
    <div className="flex flex-1 items-center justify-center">
      <div className="flex flex-col items-center gap-4 text-muted-foreground">
        <Construction className="h-12 w-12" />
        <h2 className="text-xl font-semibold">{title}</h2>
        <p className="text-sm">Coming in Phase {phase}</p>
      </div>
    </div>
  );
}
