import type { RequirementVerdict } from '@/api/types';
import { VerdictCard } from './VerdictCard';

interface VerifierMatrixProps {
  verdicts: RequirementVerdict[];
  className?: string;
}

export function VerifierMatrix({ verdicts, className }: VerifierMatrixProps) {
  if (verdicts.length === 0) {
    return (
      <div className="text-sm text-muted-foreground py-4 text-center">
        No verdicts available for this attempt.
      </div>
    );
  }

  return (
    <div className={className}>
      <div className="space-y-2">
        {verdicts.map((verdict) => (
          <VerdictCard
            key={verdict.verdictId}
            verdict={verdict}
          />
        ))}
      </div>
    </div>
  );
}
