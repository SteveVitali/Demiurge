import { JsonView, darkStyles } from 'react-json-view-lite';
import 'react-json-view-lite/dist/index.css';

interface JsonViewerProps {
  data: unknown;
  className?: string;
}

export function JsonViewer({ data, className }: JsonViewerProps) {
  let parsed = data;
  if (typeof data === 'string') {
    try {
      parsed = JSON.parse(data);
    } catch {
      // If it can't be parsed, render as raw text
      return (
        <pre className="overflow-auto whitespace-pre-wrap p-4 text-xs font-mono text-foreground">
          {data}
        </pre>
      );
    }
  }

  return (
    <div className={className}>
      <JsonView data={parsed as object} style={darkStyles} />
    </div>
  );
}
