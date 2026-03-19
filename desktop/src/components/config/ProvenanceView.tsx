import type { ConfigProvenance } from '@/api/types';
import { cn } from '@/lib/utils';

interface ProvenanceViewProps {
  provenance: ConfigProvenance;
}

const PROVENANCE_COLORS: Record<string, { bg: string; text: string; label: string }> = {
  explicit: { bg: 'bg-blue-500/20', text: 'text-blue-400', label: 'Explicit' },
  cached: { bg: 'bg-purple-500/20', text: 'text-purple-400', label: 'Cached' },
  inferred: { bg: 'bg-orange-500/20', text: 'text-orange-400', label: 'Inferred' },
  missing: { bg: 'bg-gray-500/20', text: 'text-gray-400', label: 'Missing' },
};

export function ProvenanceView({ provenance }: ProvenanceViewProps) {
  return (
    <div className="flex items-center gap-4">
      <span className="text-xs text-muted-foreground">Provenance:</span>
      <ProvenanceBadge label="Manifest" source={provenance.manifest} />
      <ProvenanceBadge label="Requirements" source={provenance.requirements} />
    </div>
  );
}

function ProvenanceBadge({ label, source }: { label: string; source: string }) {
  const fallback = { bg: 'bg-gray-500/20', text: 'text-gray-400', label: 'Missing' };
  const style = PROVENANCE_COLORS[source] ?? fallback;
  return (
    <div className="flex items-center gap-1.5">
      <span className="text-xs text-muted-foreground">{label}:</span>
      <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', style.bg, style.text)}>
        {style.label}
      </span>
    </div>
  );
}
