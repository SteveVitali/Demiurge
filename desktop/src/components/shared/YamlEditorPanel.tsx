import { useState, useCallback, useRef, useEffect, Suspense, lazy, type ReactNode } from 'react';
import { FileText, Eye } from 'lucide-react';
import type { ConfigValidationIssue } from '@/api/types';
import { cn } from '@/lib/utils';

const MonacoEditor = lazy(() => import('@monaco-editor/react'));

interface YamlEditorPanelProps {
  yaml: string;
  onChange: (yaml: string) => void;
  readOnly?: boolean;
  validate: (yaml: string) => Promise<{ errors: ConfigValidationIssue[]; warnings: ConfigValidationIssue[] }>;
  formView?: ReactNode;
  height?: string;
}

export function YamlEditorPanel({
  yaml,
  onChange,
  readOnly,
  validate,
  formView,
  height = '400px',
}: YamlEditorPanelProps) {
  const [viewMode, setViewMode] = useState<'yaml' | 'form'>('yaml');
  const [errors, setErrors] = useState<ConfigValidationIssue[]>([]);
  const [warnings, setWarnings] = useState<ConfigValidationIssue[]>([]);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleChange = useCallback((value: string | undefined) => {
    const newYaml = value ?? '';
    onChange(newYaml);

    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      void validate(newYaml).then((result) => {
        setErrors(result.errors);
        setWarnings(result.warnings);
      }).catch(() => {});
    }, 500);
  }, [onChange, validate]);

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  return (
    <div className="flex flex-col gap-3">
      {/* View Mode Toggle */}
      {formView && (
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
      )}

      {/* Editor */}
      {viewMode === 'yaml' || !formView ? (
        <div className="overflow-hidden rounded-md border border-border">
          <Suspense fallback={<div className="flex h-96 items-center justify-center text-sm text-muted-foreground">Loading editor...</div>}>
            <MonacoEditor
              height={height}
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
        formView
      )}

      {/* Validation Issues */}
      <ValidationIssues errors={errors} warnings={warnings} />
    </div>
  );
}

function ValidationIssues({ errors, warnings }: { errors: ConfigValidationIssue[]; warnings: ConfigValidationIssue[] }) {
  return (
    <>
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
    </>
  );
}
