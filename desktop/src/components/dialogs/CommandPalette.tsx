import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { Command } from 'cmdk';
import {
  Play, Hammer, Sparkles, Settings, LayoutDashboard, FileCode,
  Search, XCircle, RotateCcw,
} from 'lucide-react';
import { useAppStore } from '@/stores/app.store';
import { getRuns, cancelRun, resumeRun } from '@/api/endpoints';
import { queryKeys } from '@/lib/query-keys';

export function CommandPalette() {
  const navigate = useNavigate();
  const open = useAppStore((s) => s.commandPaletteOpen);
  const setOpen = useAppStore((s) => s.setCommandPaletteOpen);
  const setNewRunDialogOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const setBuildDialogOpen = useAppStore((s) => s.setBuildDialogOpen);
  const setSmartInitWizardOpen = useAppStore((s) => s.setSmartInitWizardOpen);
  const activeRunId = useAppStore((s) => s.activeRunId);

  const [search, setSearch] = useState('');

  const { data: recentRuns } = useQuery({
    queryKey: queryKeys.runs.list({ limit: 10, offset: 0 }),
    queryFn: () => getRuns({ limit: 10, offset: 0 }),
    enabled: open,
  });

  // Close on escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && open) {
        e.preventDefault();
        setOpen(false);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, setOpen]);

  const runAction = useCallback((action: () => void) => {
    setOpen(false);
    setSearch('');
    action();
  }, [setOpen]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-[20vh] bg-black/50" onClick={() => setOpen(false)}>
      <div onClick={(e) => e.stopPropagation()} className="w-full max-w-lg">
        <Command
          className="rounded-lg border border-border bg-background shadow-xl"
          shouldFilter={true}
        >
          <div className="flex items-center border-b border-border px-3">
            <Search className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
            <Command.Input
              value={search}
              onValueChange={setSearch}
              placeholder="Type a command or search..."
              className="flex h-11 w-full bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>
          <Command.List className="max-h-80 overflow-y-auto p-2">
            <Command.Empty className="py-6 text-center text-sm text-muted-foreground">
              No results found.
            </Command.Empty>

            {/* Actions */}
            <Command.Group heading="Actions" className="px-1 py-1.5 text-xs font-medium text-muted-foreground">
              <CommandItem
                icon={Play}
                label="New Run"
                shortcut="⌘N"
                onSelect={() => runAction(() => setNewRunDialogOpen(true))}
              />
              <CommandItem
                icon={Hammer}
                label="Build Feature"
                shortcut="⌘B"
                onSelect={() => runAction(() => setBuildDialogOpen(true))}
              />
              <CommandItem
                icon={Sparkles}
                label="Smart Init"
                onSelect={() => runAction(() => setSmartInitWizardOpen(true))}
              />
              {activeRunId && (
                <>
                  <CommandItem
                    icon={XCircle}
                    label="Cancel Active Run"
                    shortcut="⌘."
                    onSelect={() => runAction(() => { void cancelRun(activeRunId); })}
                  />
                  <CommandItem
                    icon={RotateCcw}
                    label="Resume Run"
                    shortcut="⌘R"
                    onSelect={() => runAction(() => { void resumeRun(activeRunId); })}
                  />
                </>
              )}
            </Command.Group>

            {/* Navigation */}
            <Command.Group heading="Navigation" className="px-1 py-1.5 text-xs font-medium text-muted-foreground">
              <CommandItem
                icon={LayoutDashboard}
                label="Go to Dashboard"
                onSelect={() => runAction(() => { void navigate({ to: '/' }); })}
              />
              <CommandItem
                icon={FileCode}
                label="Go to Config"
                onSelect={() => runAction(() => { void navigate({ to: '/config' }); })}
              />
              <CommandItem
                icon={Settings}
                label="Open Settings"
                shortcut="⌘,"
                onSelect={() => runAction(() => { void navigate({ to: '/settings' }); })}
              />
            </Command.Group>

            {/* Recent Runs */}
            {recentRuns && recentRuns.items.length > 0 && (
              <Command.Group heading="Recent Runs" className="px-1 py-1.5 text-xs font-medium text-muted-foreground">
                {recentRuns.items.map((run) => (
                  <CommandItem
                    key={run.runId}
                    icon={Play}
                    label={run.taskText || run.runId}
                    description={`${run.status} · ${run.runMode}`}
                    onSelect={() => runAction(() => {
                      void navigate({ to: '/runs/$runId', params: { runId: run.runId } });
                    })}
                  />
                ))}
              </Command.Group>
            )}
          </Command.List>
        </Command>
      </div>
    </div>
  );
}

function CommandItem({
  icon: Icon,
  label,
  description,
  shortcut,
  onSelect,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  description?: string;
  shortcut?: string;
  onSelect: () => void;
}) {
  return (
    <Command.Item
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-3 rounded-md px-2 py-2 text-sm aria-selected:bg-accent"
    >
      <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
      <div className="flex flex-1 flex-col">
        <span>{label}</span>
        {description && <span className="text-xs text-muted-foreground">{description}</span>}
      </div>
      {shortcut && (
        <kbd className="rounded border border-border bg-accent px-1.5 py-0.5 text-[10px] text-muted-foreground">
          {shortcut}
        </kbd>
      )}
    </Command.Item>
  );
}
