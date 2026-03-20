import { Play, Hammer, Wand2 } from 'lucide-react';

export function QuickActions() {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="mb-3 text-sm font-medium text-muted-foreground">Quick Actions</h3>
      <div className="flex flex-col gap-2">
        <ActionButton
          icon={<Play className="h-4 w-4" />}
          label="New Run"
          description="Coming in Phase 4"
          disabled
        />
        <ActionButton
          icon={<Hammer className="h-4 w-4" />}
          label="Build Feature"
          description="Coming in Phase 4"
          disabled
        />
        <ActionButton
          icon={<Wand2 className="h-4 w-4" />}
          label="Smart Init"
          description="Coming in Phase 4"
          disabled
        />
      </div>
    </div>
  );
}

function ActionButton({
  icon,
  label,
  description,
  disabled = false,
}: {
  icon: React.ReactNode;
  label: string;
  description?: string;
  disabled?: boolean;
}) {
  return (
    <button
      disabled={disabled}
      className="flex items-center gap-3 rounded-md border border-border px-3 py-2 text-sm transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
      title={description}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}
