import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { getArtifacts } from '@/api/endpoints';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { ArtifactTree } from './ArtifactTree';
import { ContentViewer } from './ContentViewer';
import type { ArtifactRecord } from '@/api/types';

interface ArtifactBrowserProps {
  runId: string;
}

export function ArtifactBrowser({ runId }: ArtifactBrowserProps) {
  const [selected, setSelected] = useState<ArtifactRecord | null>(null);

  const { data, isLoading, isError } = useQuery({
    queryKey: queryKeys.artifacts.list(runId),
    queryFn: () => getArtifacts(runId),
  });

  if (isLoading) {
    return <LoadingSpinner size="lg" className="flex-1 py-12" />;
  }

  if (isError) {
    return <ErrorState message="Failed to load artifacts" />;
  }

  const artifacts = data?.items ?? [];

  if (artifacts.length === 0) {
    return <EmptyState message="No artifacts for this run" />;
  }

  return (
    <div className="flex flex-1 overflow-hidden" style={{ minHeight: 400 }}>
      {/* Left: Tree */}
      <div className="w-64 shrink-0 border-r border-border overflow-y-auto">
        <div className="px-3 py-2 text-xs font-medium text-muted-foreground border-b border-border">
          Artifacts ({artifacts.length})
        </div>
        <ArtifactTree
          artifacts={artifacts}
          selectedId={selected?.artifactId ?? null}
          onSelect={setSelected}
        />
      </div>

      {/* Right: Content Viewer */}
      <div className="flex flex-1 overflow-hidden">
        {selected ? (
          <ContentViewer artifact={selected} />
        ) : (
          <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
            Select an artifact to view its content
          </div>
        )}
      </div>
    </div>
  );
}
