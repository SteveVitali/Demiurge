import type { ScoredInference } from '@/api/types';
import { ConfidenceBar } from '@/components/shared/ConfidenceBar';

interface InferenceTableProps {
  items: ScoredInference[];
  title: string;
  className?: string;
}

export function InferenceTable({ items, title, className }: InferenceTableProps) {
  if (items.length === 0) return null;

  return (
    <div className={className}>
      <h4 className="text-xs font-medium text-muted-foreground mb-2">{title}</h4>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-xs text-muted-foreground">
              <th className="text-left py-1.5 pr-4 font-medium">Value</th>
              <th className="text-left py-1.5 pr-4 font-medium">Confidence</th>
              <th className="text-left py-1.5 font-medium">Source</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, i) => (
              <tr key={i} className="border-b border-border/50">
                <td className="py-1.5 pr-4 text-foreground">{String(item.value)}</td>
                <td className="py-1.5 pr-4">
                  <ConfidenceBar value={item.confidence} />
                </td>
                <td className="py-1.5">
                  <span className="inline-flex items-center rounded bg-zinc-800 px-1.5 py-0.5 text-xs text-muted-foreground">
                    {item.source}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
