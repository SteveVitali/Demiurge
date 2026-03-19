import { useCallback } from 'react';
import { validateConfig } from '@/api/endpoints';
import { YamlEditorPanel } from '@/components/shared/YamlEditorPanel';

interface ManifestEditorProps {
  yaml: string;
  onChange: (yaml: string) => void;
  readOnly?: boolean;
}

export function ManifestEditor({ yaml, onChange, readOnly }: ManifestEditorProps) {
  const validate = useCallback(async (content: string) => {
    const result = await validateConfig(content);
    return {
      errors: result.errors.filter((e) => e.field === 'manifest'),
      warnings: result.warnings.filter((w) => w.field === 'manifest'),
    };
  }, []);

  return (
    <YamlEditorPanel
      yaml={yaml}
      onChange={onChange}
      readOnly={readOnly}
      validate={validate}
      formView={<ManifestFormView yaml={yaml} readOnly={readOnly} />}
    />
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
