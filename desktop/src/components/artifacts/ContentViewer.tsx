import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { getArtifactContent } from '@/api/endpoints';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { ArtifactTypeIcon } from '@/components/shared/ArtifactTypeIcon';
import { JsonViewer } from './JsonViewer';
import { DiffViewer } from './DiffViewer';
import { ScreenshotGallery } from './ScreenshotGallery';
import { LogRenderer } from './LogRenderer';
import { MarkdownRenderer } from './MarkdownRenderer';
import type { ArtifactRecord, ArtifactType } from '@/api/types';

interface ContentViewerProps {
  artifact: ArtifactRecord;
}

const jsonTypes: Set<ArtifactType> = new Set([
  'Plan', 'StructuredVerdict', 'FailurePacketArtifact',
  'ApiRequestResponse', 'NetworkSummary', 'DbQueryResult',
  'QueueObservation', 'AuthStorageState', 'RepoInspectionArtifact',
  'InferenceLog', 'PromptPackage', 'StartupTimeline',
]);

const logTypes: Set<ArtifactType> = new Set([
  'ServiceLog', 'StdoutExcerpt', 'StderrExcerpt', 'ConsoleLog',
  'RepairTranscript',
]);

const markdownTypes: Set<ArtifactType> = new Set([
  'FinalReport', 'AttemptReport',
]);

const imageTypes: Set<ArtifactType> = new Set([
  'Screenshot',
]);

const diffTypes: Set<ArtifactType> = new Set([
  'PatchDiff',
]);

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function ContentViewer({ artifact }: ContentViewerProps) {
  const isImage = imageTypes.has(artifact.artifactType) || artifact.contentType.startsWith('image/');

  // Don't fetch content for images — they're rendered directly via URL
  const { data: content, isLoading, isError } = useQuery({
    queryKey: queryKeys.artifacts.content(artifact.runId, artifact.artifactId),
    queryFn: () => getArtifactContent(artifact.runId, artifact.artifactId),
    enabled: !isImage,
  });

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-2 border-b border-border px-4 py-2">
        <ArtifactTypeIcon type={artifact.artifactType} size={16} />
        <span className="text-sm font-medium truncate">
          {artifact.relativePath.split('/').pop()}
        </span>
        <span className="text-xs text-muted-foreground ml-auto">
          {artifact.artifactType} · {formatSize(artifact.sizeBytes)}
          {artifact.attemptNumber !== null && ` · Attempt ${artifact.attemptNumber}`}
        </span>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {isImage && (
          <ScreenshotGallery
            runId={artifact.runId}
            artifactId={artifact.artifactId}
            contentType={artifact.contentType}
          />
        )}

        {!isImage && isLoading && (
          <LoadingSpinner size="lg" className="flex-1 py-12" />
        )}

        {!isImage && isError && (
          <ErrorState message="Failed to load artifact content" />
        )}

        {!isImage && content !== undefined && (
          <>
            {jsonTypes.has(artifact.artifactType) && <JsonViewer data={content} />}
            {logTypes.has(artifact.artifactType) && <LogRenderer content={String(content)} />}
            {markdownTypes.has(artifact.artifactType) && <MarkdownRenderer content={String(content)} />}
            {diffTypes.has(artifact.artifactType) && <DiffViewer diffContent={String(content)} />}
            {/* Fallback for types not explicitly handled */}
            {!jsonTypes.has(artifact.artifactType) &&
              !logTypes.has(artifact.artifactType) &&
              !markdownTypes.has(artifact.artifactType) &&
              !diffTypes.has(artifact.artifactType) && (
                <LogRenderer content={typeof content === 'string' ? content : JSON.stringify(content, null, 2)} />
              )}
          </>
        )}
      </div>
    </div>
  );
}
