import { useCallback } from 'react';
import { validateConfig } from '@/api/endpoints';
import { YamlEditorPanel } from '@/components/shared/YamlEditorPanel';
import { cn } from '@/lib/utils';

interface RequirementsEditorProps {
  yaml: string;
  onChange: (yaml: string) => void;
  readOnly?: boolean;
}

export function RequirementsEditor({ yaml, onChange, readOnly }: RequirementsEditorProps) {
  const validate = useCallback(async (content: string) => {
    const result = await validateConfig(undefined, content);
    return {
      errors: result.errors.filter((e) => e.field === 'requirements'),
      warnings: result.warnings.filter((w) => w.field === 'requirements'),
    };
  }, []);

  return (
    <YamlEditorPanel
      yaml={yaml}
      onChange={onChange}
      readOnly={readOnly}
      validate={validate}
      formView={<RequirementsFormView yaml={yaml} />}
    />
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
