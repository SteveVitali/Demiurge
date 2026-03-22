import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { getArtifactContent } from '@/api/endpoints';
import { formatFileSize } from '@/lib/utils';
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

type RendererCategory = 'json' | 'log' | 'markdown' | 'image' | 'diff';

const rendererMap: Partial<Record<ArtifactType, RendererCategory>> = {
  // JSON
  Plan: 'json', StructuredVerdict: 'json', FailurePacketArtifact: 'json',
  ApiRequestResponse: 'json', NetworkSummary: 'json', DbQueryResult: 'json',
  QueueObservation: 'json', AuthStorageState: 'json', RepoInspectionArtifact: 'json',
  InferenceLog: 'json', PromptPackage: 'json', StartupTimeline: 'json',
  // Log
  ServiceLog: 'log', StdoutExcerpt: 'log', StderrExcerpt: 'log',
  ConsoleLog: 'log', RepairTranscript: 'log',
  // Markdown
  FinalReport: 'markdown', AttemptReport: 'markdown',
  // Image
  Screenshot: 'image',
  // Diff
  PatchDiff: 'diff',
};

function getRendererCategory(artifact: ArtifactRecord): RendererCategory {
  if (artifact.contentType.startsWith('image/')) return 'image';
  return rendererMap[artifact.artifactType] ?? 'log';
}

export function ContentViewer({ artifact }: ContentViewerProps) {
  const category = getRendererCategory(artifact);
  const isImage = category === 'image';

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
          {artifact.artifactType} · {formatFileSize(artifact.sizeBytes)}
          {artifact.attemptNumber !== null && ` · Attempt ${artifact.attemptNumber}`}
        </span>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {isImage && (
          <ScreenshotGallery
            runId={artifact.runId}
            artifactId={artifact.artifactId}
          />
        )}

        {!isImage && isLoading && (
          <LoadingSpinner size="lg" className="flex-1 py-12" />
        )}

        {!isImage && isError && (
          <ErrorState message="Failed to load artifact content" />
        )}

        {!isImage && content !== undefined && (
          <ContentRenderer category={category} content={content} />
        )}
      </div>
    </div>
  );
}

function ContentRenderer({ category, content }: { category: RendererCategory; content: unknown }) {
  switch (category) {
    case 'json': return <JsonViewer data={content} />;
    case 'diff': return <DiffViewer diffContent={String(content)} />;
    case 'markdown': return <MarkdownRenderer content={String(content)} />;
    case 'log': return <LogRenderer content={String(content)} />;
    default: return <LogRenderer content={typeof content === 'string' ? content : JSON.stringify(content, null, 2)} />;
  }
}
