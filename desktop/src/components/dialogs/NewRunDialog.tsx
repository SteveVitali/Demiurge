import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { Play } from 'lucide-react';
import { useAppStore } from '@/stores/app.store';
import { usePreferencesStore } from '@/stores/preferences.store';
import { createRun } from '@/api/endpoints';
import type { RunMode, CreateRunRequest } from '@/api/types';
import { cn } from '@/lib/utils';
import { DialogOverlay } from '@/components/shared/DialogOverlay';
import { RepoPathField } from '@/components/shared/RepoPathField';
import { CollapsibleSection } from '@/components/shared/CollapsibleSection';
import { BudgetFields, GitFields, RunModeSelector } from './run-form-fields';

export function NewRunDialog() {
  const navigate = useNavigate();
  const open = useAppStore((s) => s.newRunDialogOpen);
  const setOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const activeRepoPath = useAppStore((s) => s.activeRepoPath);
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const defaults = usePreferencesStore();

  const [repoPath, setRepoPath] = useState('');
  const [task, setTask] = useState('');
  const [mode, setMode] = useState<RunMode>('Full');
  const [maxAttempts, setMaxAttempts] = useState(5);
  const [runTimeoutMs, setRunTimeoutMs] = useState(1800000);
  const [agentBackend, setAgentBackend] = useState('claude-agent-sdk');
  const [branch, setBranch] = useState('');
  const [openPr, setOpenPr] = useState(false);
  const [skipConfirmation, setSkipConfirmation] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset form state each time dialog opens
  useEffect(() => {
    if (open) {
      setRepoPath(activeRepoPath ?? defaults.defaultRepoPath ?? '');
      setTask('');
      setMode(defaults.defaultRunMode);
      setMaxAttempts(defaults.defaultMaxAttempts);
      setRunTimeoutMs(defaults.defaultRunTimeoutMs);
      setAgentBackend('claude-agent-sdk');
      setBranch('');
      setOpenPr(false);
      setSkipConfirmation(true);
      setSubmitting(false);
      setError(null);
    }
  }, [open, activeRepoPath, defaults.defaultRepoPath, defaults.defaultRunMode, defaults.defaultMaxAttempts, defaults.defaultRunTimeoutMs]);

  const handleClose = useCallback(() => {
    setOpen(false);
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

  if (!open) return null;

  return (
    <DialogOverlay onClose={handleClose}>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">New Run</h2>
        <button onClick={handleClose} className="text-muted-foreground hover:text-foreground">✕</button>
      </div>

      <div className="flex flex-col gap-4">
        <RepoPathField value={repoPath} onChange={setRepoPath} />

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

        <RunModeSelector mode={mode} onChange={setMode} />

        <CollapsibleSection title="Budget Overrides">
          <BudgetFields
            maxAttempts={maxAttempts}
            onMaxAttemptsChange={setMaxAttempts}
            runTimeoutMs={runTimeoutMs}
            onRunTimeoutMsChange={setRunTimeoutMs}
            agentBackend={agentBackend}
            onAgentBackendChange={setAgentBackend}
          />
        </CollapsibleSection>

        <CollapsibleSection title="Git Options">
          <GitFields
            branch={branch}
            onBranchChange={setBranch}
            openPr={openPr}
            onOpenPrChange={setOpenPr}
            skipConfirmation={skipConfirmation}
            onSkipConfirmationChange={setSkipConfirmation}
            branchPlaceholder="fix/my-branch"
          />
        </CollapsibleSection>

        {error && (
          <div className="rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-400">
            {error}
          </div>
        )}

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
    </DialogOverlay>
  );
}
