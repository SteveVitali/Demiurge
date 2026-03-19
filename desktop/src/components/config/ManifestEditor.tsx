import { useState, useCallback, useRef, useEffect, Suspense, lazy } from 'react';
import { FileText, Eye } from 'lucide-react';
import { validateConfig } from '@/api/endpoints';
import type { ConfigValidationIssue } from '@/api/types';
import { cn } from '@/lib/utils';

const MonacoEditor = lazy(() => import('@monaco-editor/react'));

interface ManifestEditorProps {
  yaml: string;
  onChange: (yaml: string) => void;
  readOnly?: boolean;
}

export function ManifestEditor({ yaml, onChange, readOnly }: ManifestEditorProps) {
  const [viewMode, setViewMode] = useState<'yaml' | 'form'>('yaml');
  const [errors, setErrors] = useState<ConfigValidationIssue[]>([]);
  const [warnings, setWarnings] = useState<ConfigValidationIssue[]>([]);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleChange = useCallback((value: string | undefined) => {
    const newYaml = value ?? '';
    onChange(newYaml);

    // Debounced validation (500ms)
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      void validateConfig(newYaml).then((result) => {
        setErrors(result.errors.filter((e) => e.field === 'manifest'));
        setWarnings(result.warnings.filter((w) => w.field === 'manifest'));
      }).catch(() => {});
    }, 500);
  }, [onChange]);

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  return (
    <div className="flex flex-col gap-3">
      {/* View Mode Toggle */}
      <div className="flex items-center gap-2">
        <button
          onClick={() => setViewMode('yaml')}
          className={cn(
            'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs',
            viewMode === 'yaml' ? 'bg-accent text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <FileText className="h-3.5 w-3.5" />
          YAML View
        </button>
        <button
          onClick={() => setViewMode('form')}
          className={cn(
            'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs',
            viewMode === 'form' ? 'bg-accent text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <Eye className="h-3.5 w-3.5" />
          Form View
        </button>
      </div>

      {/* Editor */}
      {viewMode === 'yaml' ? (
        <div className="overflow-hidden rounded-md border border-border">
          <Suspense fallback={<div className="flex h-96 items-center justify-center text-sm text-muted-foreground">Loading editor...</div>}>
            <MonacoEditor
              height="400px"
              language="yaml"
              theme="vs-dark"
              value={yaml}
              onChange={handleChange}
              options={{
                readOnly,
                minimap: { enabled: false },
                fontSize: 13,
                lineNumbers: 'on',
                scrollBeyondLastLine: false,
                wordWrap: 'on',
                tabSize: 2,
              }}
            />
          </Suspense>
        </div>
      ) : (
        <ManifestFormView yaml={yaml} readOnly={readOnly} />
      )}

      {/* Validation Errors */}
      {errors.length > 0 && (
        <div className="flex flex-col gap-1">
          {errors.map((e, i) => (
            <div key={i} className="rounded-md border border-red-500/30 bg-red-500/10 px-3 py-1.5 text-xs text-red-400">
              {e.line ? `Line ${e.line}: ` : ''}{e.message}
            </div>
          ))}
        </div>
      )}
      {warnings.length > 0 && (
        <div className="flex flex-col gap-1">
          {warnings.map((w, i) => (
            <div key={i} className="rounded-md border border-yellow-500/30 bg-yellow-500/10 px-3 py-1.5 text-xs text-yellow-400">
              {w.line ? `Line ${w.line}: ` : ''}{w.message}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ManifestFormView({ yaml, readOnly }: { yaml: string; readOnly?: boolean }) {
  // Basic form view: extract key sections from YAML and display as editable fields
  const lines = yaml.split('\n');
  const getField = (key: string): string => {
    const line = lines.find((l) => l.trim().startsWith(`${key}:`));
    return line ? line.split(':').slice(1).join(':').trim() : '';
  };

  return (
    <div className="flex flex-col gap-4 rounded-md border border-border p-4">
      <div>
        <label className="mb-1 block text-xs font-medium text-muted-foreground">Type</label>
        <input
          type="text"
          defaultValue={getField('type')}
          readOnly={readOnly}
          className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm"
          placeholder="web-app"
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium text-muted-foreground">Root URL</label>
        <input
          type="text"
          defaultValue={getField('root_url')}
          readOnly={readOnly}
          className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm"
          placeholder="http://localhost:3000"
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium text-muted-foreground">API URL</label>
        <input
          type="text"
          defaultValue={getField('api_url')}
          readOnly={readOnly}
          className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm"
          placeholder="http://localhost:4000"
        />
      </div>
      <p className="text-xs text-muted-foreground">
        Switch to YAML View for full editing capabilities.
      </p>
    </div>
  );
}
