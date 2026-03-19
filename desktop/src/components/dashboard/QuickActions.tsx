import { Play, Hammer, Wand2 } from 'lucide-react';
import { useAppStore } from '@/stores/app.store';

export function QuickActions() {
  const setNewRunDialogOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const setBuildDialogOpen = useAppStore((s) => s.setBuildDialogOpen);
  const setSmartInitWizardOpen = useAppStore((s) => s.setSmartInitWizardOpen);

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="mb-3 text-sm font-medium text-muted-foreground">Quick Actions</h3>
      <div className="flex flex-col gap-2">
        <ActionButton
          icon={<Play className="h-4 w-4" />}
          label="New Run"
          description="Start a new verification run"
          onClick={() => setNewRunDialogOpen(true)}
        />
        <ActionButton
          icon={<Hammer className="h-4 w-4" />}
          label="Build Feature"
          description="Generate code for a feature"
          onClick={() => setBuildDialogOpen(true)}
        />
        <ActionButton
          icon={<Wand2 className="h-4 w-4" />}
          label="Smart Init"
          description="Auto-configure a repository"
          onClick={() => setSmartInitWizardOpen(true)}
        />
      </div>
    </div>
  );
}

function ActionButton({
  icon,
  label,
  description,
  onClick,
  disabled = false,
}: {
  icon: React.ReactNode;
  label: string;
  description?: string;
  onClick?: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      disabled={disabled}
      onClick={onClick}
      className="flex items-center gap-3 rounded-md border border-border px-3 py-2 text-sm transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
      title={description}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}
