import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { getInspection } from '@/api/endpoints';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { RepoOverview } from './RepoOverview';
import { InferenceTable } from './InferenceTable';
import { ImpactMap } from './ImpactMap';

interface InspectionPanelProps {
  runId: string;
}

export function InspectionPanel({ runId }: InspectionPanelProps) {
  const { data: report, isLoading, isError } = useQuery({
    queryKey: queryKeys.inspection.report(runId),
    queryFn: () => getInspection(runId),
  });

  if (isLoading) {
    return <LoadingSpinner size="lg" className="flex-1 py-12" />;
  }

  if (isError) {
    return <ErrorState message="Failed to load inspection report" />;
  }

  if (!report) {
    return <EmptyState message="No inspection data available for this run" />;
  }

  return (
    <div className="flex flex-col gap-6 p-4">
      <RepoOverview report={report} />

      <InferenceTable items={report.languages} title="Language Inference" />
      <InferenceTable items={report.frameworks} title="Framework Inference" />

      {report.changedSurfaceMap && report.changedSurfaceMap.length > 0 && (
        <ImpactMap surfaces={report.changedSurfaceMap} />
      )}
    </div>
  );
}
