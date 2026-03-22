import ReactDiffViewer, { DiffMethod } from 'react-diff-viewer-continued';

interface DiffViewerProps {
  diffContent: string;
  className?: string;
}

function parseDiff(diff: string): { oldValue: string; newValue: string } {
  const lines = diff.split('\n');
  const oldLines: string[] = [];
  const newLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith('---') || line.startsWith('+++') || line.startsWith('@@')) {
      continue;
    }
    if (line.startsWith('-')) {
      oldLines.push(line.slice(1));
    } else if (line.startsWith('+')) {
      newLines.push(line.slice(1));
    } else {
      const content = line.startsWith(' ') ? line.slice(1) : line;
      oldLines.push(content);
      newLines.push(content);
    }
  }

  return { oldValue: oldLines.join('\n'), newValue: newLines.join('\n') };
}

export function DiffViewer({ diffContent, className }: DiffViewerProps) {
  const { oldValue, newValue } = parseDiff(diffContent);

  return (
    <div className={className}>
      <ReactDiffViewer
        oldValue={oldValue}
        newValue={newValue}
        splitView={false}
        compareMethod={DiffMethod.LINES}
        useDarkTheme
        styles={{
          variables: {
            dark: {
              diffViewerBackground: '#18181b',
              addedBackground: '#052e16',
              removedBackground: '#450a0a',
              addedGutterBackground: '#064e3b',
              removedGutterBackground: '#7f1d1d',
              gutterBackground: '#18181b',
              codeFoldBackground: '#27272a',
              codeFoldGutterBackground: '#27272a',
            },
          },
          line: { fontSize: '12px' },
        }}
      />
    </div>
  );
}
