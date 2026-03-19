import type { RequirementVerdict } from '@/api/types';
import { VerdictCard } from './VerdictCard';

interface VerifierMatrixProps {
  verdicts: RequirementVerdict[];
  className?: string;
}

interface GroupedRequirement {
  requirementId: string;
  priority: string | null;
  verdicts: RequirementVerdict[];
}

// Priority order reserved for future use when graph data provides requirement priority
// const priorityOrder: Record<string, number> = { Required: 0, Important: 1, NiceToHave: 2 };

export function VerifierMatrix({ verdicts, className }: VerifierMatrixProps) {
  // Group verdicts by requirementId
  const grouped = new Map<string, RequirementVerdict[]>();
  for (const v of verdicts) {
    const existing = grouped.get(v.requirementId) ?? [];
    existing.push(v);
    grouped.set(v.requirementId, existing);
  }

  // Build grouped list — we don't have priority from verdict data directly,
  // so we sort alphabetically. Priority grouping will work when graph data is available.
  const requirements: GroupedRequirement[] = Array.from(grouped.entries()).map(
    ([requirementId, reqVerdicts]) => ({
      requirementId,
      priority: null,
      verdicts: reqVerdicts,
    }),
  );

  if (requirements.length === 0) {
    return (
      <div className="text-sm text-muted-foreground py-4 text-center">
        No verdicts available for this attempt.
      </div>
    );
  }

  return (
    <div className={className}>
      <div className="space-y-2">
        {requirements.map((req) =>
          req.verdicts.map((verdict) => (
            <VerdictCard
              key={verdict.verdictId}
              verdict={verdict}
              priority={req.priority ?? undefined}
            />
          )),
        )}
      </div>
    </div>
  );
}
