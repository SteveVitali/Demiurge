import { useState, useCallback } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { Play, FolderOpen, ChevronDown, ChevronRight } from 'lucide-react';
import { useAppStore } from '@/stores/app.store';
import { usePreferencesStore } from '@/stores/preferences.store';
import { createRun } from '@/api/endpoints';
import type { RunMode, CreateRunRequest } from '@/api/types';
import { cn } from '@/lib/utils';

const RUN_MODES: { value: RunMode; label: string; description: string }[] = [
  { value: 'Full', label: 'Full', description: 'Verify → Analyze → Repair loop' },
  { value: 'Build', label: 'Build', description: 'Generate code → Verify → Repair' },
  { value: 'PlanOnly', label: 'Plan Only', description: 'Inspect + plan, no execution' },
  { value: 'VerifyOnly', label: 'Verify Only', description: 'Run verifiers only' },
  { value: 'InspectOnly', label: 'Inspect Only', description: 'Inspect repo only' },
];

const AGENT_BACKENDS = [
  { value: 'claude-agent-sdk', label: 'Claude Agent SDK' },
  { value: 'legacy', label: 'Legacy (ClaudeClient)' },
];

export function NewRunDialog() {
  const navigate = useNavigate();
  const open = useAppStore((s) => s.newRunDialogOpen);
  const setOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const activeRepoPath = useAppStore((s) => s.activeRepoPath);
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const defaults = usePreferencesStore();

  const [repoPath, setRepoPath] = useState(activeRepoPath ?? defaults.defaultRepoPath ?? '');
  const [task, setTask] = useState('');
  const [mode, setMode] = useState<RunMode>(defaults.defaultRunMode);
  const [showBudget, setShowBudget] = useState(false);
  const [showGit, setShowGit] = useState(false);
  const [maxAttempts, setMaxAttempts] = useState(defaults.defaultMaxAttempts);
  const [runTimeoutMs, setRunTimeoutMs] = useState(defaults.defaultRunTimeoutMs);
  const [agentBackend, setAgentBackend] = useState('claude-agent-sdk');
  const [branch, setBranch] = useState('');
  const [openPr, setOpenPr] = useState(false);
  const [skipConfirmation, setSkipConfirmation] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleClose = useCallback(() => {
    setOpen(false);
    setError(null);
  }, [setOpen]);

  const handleSubmit = useCallback(async () => {
    if (!repoPath.trim() || !task.trim()) {
      setError('Repo path and task are required');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const request: CreateRunRequest = {
        repoPath: repoPath.trim(),
        task: task.trim(),
        mode,
        maxAttempts,
        runTimeoutMs,
        agentBackend,
        ...(branch.trim() ? { branch: branch.trim() } : {}),
        openPr,
        skipConfirmation,
      };
      const result = await createRun(request);
      setActiveRun(result.runId);
      handleClose();
      void navigate({ to: '/runs/$runId', params: { runId: result.runId } });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start run');
    } finally {
      setSubmitting(false);
    }
  }, [repoPath, task, mode, maxAttempts, runTimeoutMs, agentBackend, branch, openPr, skipConfirmation, handleClose, setActiveRun, navigate]);

  const handleFolderPick = useCallback(async () => {
    try {
      const { open: openDialog } = await import('@tauri-apps/plugin-dialog');
      const selected = await openDialog({ directory: true, multiple: false });
      if (selected) setRepoPath(selected as string);
    } catch {
      // Tauri not available (dev mode) — ignore
    }
  }, []);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={handleClose}>
      <div
        className="w-full max-w-lg rounded-lg border border-border bg-background p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">New Run</h2>
          <button onClick={handleClose} className="text-muted-foreground hover:text-foreground">✕</button>
        </div>

        <div className="flex flex-col gap-4">
          {/* Repo Path */}
          <div>
            <label className="mb-1 block text-sm font-medium">Repo</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={repoPath}
                onChange={(e) => setRepoPath(e.target.value)}
                placeholder="/path/to/your/project"
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

          {/* Task */}
          <div>
            <label className="mb-1 block text-sm font-medium">Task</label>
            <textarea
              value={task}
              onChange={(e) => setTask(e.target.value)}
              placeholder="Describe what you want Demiurge to fix or verify..."
              rows={3}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>

          {/* Mode */}
          <div>
            <label className="mb-1 block text-sm font-medium">Mode</label>
            <div className="flex flex-wrap gap-2">
              {RUN_MODES.map((m) => (
                <button
                  key={m.value}
                  onClick={() => setMode(m.value)}
                  className={cn(
                    'rounded-md border px-3 py-1.5 text-xs transition-colors',
                    mode === m.value
                      ? 'border-blue-500 bg-blue-500/10 text-blue-400'
                      : 'border-border text-muted-foreground hover:bg-accent',
                  )}
                  title={m.description}
                >
                  {m.label}
                </button>
              ))}
            </div>
          </div>

          {/* Budget Overrides */}
          <div>
            <button
              onClick={() => setShowBudget(!showBudget)}
              className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
            >
              {showBudget ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
              Budget Overrides
            </button>
            {showBudget && (
              <div className="mt-2 flex flex-col gap-3 rounded-md border border-border p-3">
                <div className="flex items-center gap-3">
                  <label className="w-32 text-xs text-muted-foreground">Max Attempts</label>
                  <input
                    type="number"
                    min={1}
                    max={10}
                    value={maxAttempts}
                    onChange={(e) => setMaxAttempts(Number(e.target.value))}
                    className="w-20 rounded-md border border-border bg-background px-2 py-1 text-sm"
                  />
                </div>
                <div className="flex items-center gap-3">
                  <label className="w-32 text-xs text-muted-foreground">Run Timeout</label>
                  <input
                    type="text"
                    value={`${Math.round(runTimeoutMs / 60000)}m`}
                    onChange={(e) => {
                      const m = parseInt(e.target.value);
                      if (!isNaN(m)) setRunTimeoutMs(m * 60000);
                    }}
                    className="w-20 rounded-md border border-border bg-background px-2 py-1 text-sm"
                  />
                </div>
                <div className="flex items-center gap-3">
                  <label className="w-32 text-xs text-muted-foreground">Agent Backend</label>
                  <select
                    value={agentBackend}
                    onChange={(e) => setAgentBackend(e.target.value)}
                    className="rounded-md border border-border bg-background px-2 py-1 text-sm"
                  >
                    {AGENT_BACKENDS.map((b) => (
                      <option key={b.value} value={b.value}>{b.label}</option>
                    ))}
                  </select>
                </div>
              </div>
            )}
          </div>

          {/* Git Options */}
          <div>
            <button
              onClick={() => setShowGit(!showGit)}
              className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
            >
              {showGit ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
              Git Options
            </button>
            {showGit && (
              <div className="mt-2 flex flex-col gap-3 rounded-md border border-border p-3">
                <div className="flex items-center gap-3">
                  <label className="w-32 text-xs text-muted-foreground">Branch name</label>
                  <input
                    type="text"
                    value={branch}
                    onChange={(e) => setBranch(e.target.value)}
                    placeholder="fix/my-branch"
                    className="flex-1 rounded-md border border-border bg-background px-2 py-1 text-sm"
                  />
                </div>
                <label className="flex items-center gap-2 text-xs text-muted-foreground">
                  <input type="checkbox" checked={openPr} onChange={(e) => setOpenPr(e.target.checked)} />
                  Open PR after success
                </label>
                <label className="flex items-center gap-2 text-xs text-muted-foreground">
                  <input type="checkbox" checked={skipConfirmation} onChange={(e) => setSkipConfirmation(e.target.checked)} />
                  Skip confirmation
                </label>
              </div>
            )}
          </div>

          {/* Error */}
          {error && (
            <div className="rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-400">
              {error}
            </div>
          )}

          {/* Actions */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={handleClose}
              className="rounded-md border border-border px-4 py-2 text-sm hover:bg-accent"
            >
              Cancel
            </button>
            <button
              onClick={() => void handleSubmit()}
              disabled={submitting || !task.trim() || !repoPath.trim()}
              className={cn(
                'flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium',
                submitting || !task.trim() || !repoPath.trim()
                  ? 'cursor-not-allowed bg-blue-500/30 text-blue-400/50'
                  : 'bg-blue-600 text-white hover:bg-blue-700',
              )}
            >
              <Play className="h-4 w-4" />
              {submitting ? 'Starting...' : 'Start Run'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
