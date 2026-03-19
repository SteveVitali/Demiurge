import { Suspense, lazy } from 'react';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';

// Desktop Phase 3 — §9.4: File diffs from agent Edit calls.
// Wraps react-diff-viewer-continued for inline display within the transcript stream.

const ReactDiffViewer = lazy(() =>
  import('react-diff-viewer-continued').then((mod) => ({ default: mod.default }))
);

interface AgentDiffViewerProps {
  filePath: string;
  oldContent: string;
  newContent: string;
  className?: string;
}

export function AgentDiffViewer({ filePath, oldContent, newContent, className }: AgentDiffViewerProps) {
  return (
    <div className={className}>
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-mono">{filePath}</span>
      </div>
      <div className="overflow-auto rounded-md border border-border text-xs">
        <Suspense fallback={<LoadingSpinner size="sm" className="p-4" />}>
          <ReactDiffViewer
            oldValue={oldContent}
            newValue={newContent}
            splitView={false}
            useDarkTheme={true}
            hideLineNumbers={false}
            styles={{
              contentText: { fontSize: '11px', lineHeight: '1.4' },
              diffContainer: { background: 'transparent' },
            }}
          />
        </Suspense>
      </div>
    </div>
  );
}
