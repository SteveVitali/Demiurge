import { useCallback } from 'react';
import { FolderOpen } from 'lucide-react';

interface RepoPathFieldProps {
  value: string;
  onChange: (value: string) => void;
  label?: string;
  placeholder?: string;
}

export function RepoPathField({
  value,
  onChange,
  label = 'Repo',
  placeholder = '/path/to/your/project',
}: RepoPathFieldProps) {
  const handleFolderPick = useCallback(async () => {
    try {
      const { open: openDialog } = await import('@tauri-apps/plugin-dialog');
      const selected = await openDialog({ directory: true, multiple: false });
      if (selected) onChange(selected as string);
    } catch {
      // Tauri not available (dev mode)
    }
  }, [onChange]);

  return (
    <div>
      <label className="mb-1 block text-sm font-medium">{label}</label>
      <div className="flex gap-2">
        <input
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className="flex-1 rounded-md border border-border bg-background px-3 py-2 text-sm"
        />
        <button
          onClick={() => void handleFolderPick()}
          className="rounded-md border border-border px-3 py-2 text-sm hover:bg-accent"
        >
          <FolderOpen className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
