import { useState, useCallback, useRef, useEffect, Suspense, lazy } from 'react';
import { FileText, Eye } from 'lucide-react';
import { validateConfig } from '@/api/endpoints';
import type { ConfigValidationIssue } from '@/api/types';
import { cn } from '@/lib/utils';

const MonacoEditor = lazy(() => import('@monaco-editor/react'));

interface RequirementsEditorProps {
  yaml: string;
  onChange: (yaml: string) => void;
  readOnly?: boolean;
}

export function RequirementsEditor({ yaml, onChange, readOnly }: RequirementsEditorProps) {
  const [viewMode, setViewMode] = useState<'yaml' | 'form'>('yaml');
  const [errors, setErrors] = useState<ConfigValidationIssue[]>([]);
  const [warnings, setWarnings] = useState<ConfigValidationIssue[]>([]);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleChange = useCallback((value: string | undefined) => {
    const newYaml = value ?? '';
    onChange(newYaml);

    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      void validateConfig(undefined, newYaml).then((result) => {
        setErrors(result.errors.filter((e) => e.field === 'requirements'));
        setWarnings(result.warnings.filter((w) => w.field === 'requirements'));
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
        <RequirementsFormView yaml={yaml} />
      )}

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

function RequirementsFormView({ yaml }: { yaml: string }) {
  // Parse requirements from YAML text (basic line-based parsing)
  const requirements: { id: string; description: string; priority: string }[] = [];
  let currentReq: { id: string; description: string; priority: string } | null = null;

  for (const line of yaml.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.startsWith('- id:')) {
      if (currentReq) requirements.push(currentReq);
      currentReq = { id: trimmed.replace('- id:', '').trim(), description: '', priority: 'Required' };
    } else if (currentReq && trimmed.startsWith('description:')) {
      currentReq.description = trimmed.replace('description:', '').trim();
    } else if (currentReq && trimmed.startsWith('priority:')) {
      currentReq.priority = trimmed.replace('priority:', '').trim();
    }
  }
  if (currentReq) requirements.push(currentReq);

  if (requirements.length === 0) {
    return (
      <div className="flex h-40 items-center justify-center rounded-md border border-border text-sm text-muted-foreground">
        No requirements found. Switch to YAML View to add requirements.
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {requirements.map((req, i) => (
        <div key={i} className="rounded-md border border-border p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">{req.id}</span>
            <span className={cn(
              'rounded-full px-2 py-0.5 text-xs',
              req.priority === 'Required' ? 'bg-red-500/20 text-red-400' :
              req.priority === 'Important' ? 'bg-yellow-500/20 text-yellow-400' :
              'bg-gray-500/20 text-gray-400',
            )}>
              {req.priority}
            </span>
          </div>
          {req.description && (
            <p className="mt-1 text-xs text-muted-foreground">{req.description}</p>
          )}
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        Switch to YAML View for full editing capabilities.
      </p>
    </div>
  );
}
