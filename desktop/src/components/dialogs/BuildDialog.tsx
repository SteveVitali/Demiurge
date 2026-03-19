import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { Hammer } from 'lucide-react';
import { useAppStore } from '@/stores/app.store';
import { usePreferencesStore } from '@/stores/preferences.store';
import { createRun } from '@/api/endpoints';
import type { CreateRunRequest } from '@/api/types';
import { cn } from '@/lib/utils';
import { DialogOverlay } from '@/components/shared/DialogOverlay';
import { RepoPathField } from '@/components/shared/RepoPathField';
import { CollapsibleSection } from '@/components/shared/CollapsibleSection';
import { BudgetFields, GitFields } from './run-form-fields';

export function BuildDialog() {
  const navigate = useNavigate();
  const open = useAppStore((s) => s.buildDialogOpen);
  const setOpen = useAppStore((s) => s.setBuildDialogOpen);
  const activeRepoPath = useAppStore((s) => s.activeRepoPath);
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const defaults = usePreferencesStore();

  const [repoPath, setRepoPath] = useState('');
  const [task, setTask] = useState('');
  const [maxAttempts, setMaxAttempts] = useState(8);
  const [runTimeoutMs, setRunTimeoutMs] = useState(7200000);
  const [agentBackend, setAgentBackend] = useState('claude-agent-sdk');
  const [branch, setBranch] = useState('');
  const [openPr, setOpenPr] = useState(false);
  const [skipConfirmation, setSkipConfirmation] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset form state each time dialog opens (build mode defaults differ)
  useEffect(() => {
    if (open) {
      setRepoPath(activeRepoPath ?? defaults.defaultRepoPath ?? '');
      setTask('');
      setMaxAttempts(8);
      setRunTimeoutMs(7200000);
      setAgentBackend('claude-agent-sdk');
      setBranch('');
      setOpenPr(false);
      setSkipConfirmation(false);
      setSubmitting(false);
      setError(null);
    }
  }, [open, activeRepoPath, defaults.defaultRepoPath]);

  const handleClose = useCallback(() => {
    setOpen(false);
  }, [setOpen]);

  const handleSubmit = useCallback(async () => {
    if (!repoPath.trim() || !task.trim()) {
      setError('Repo path and task description are required');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const request: CreateRunRequest = {
        repoPath: repoPath.trim(),
        task: task.trim(),
        mode: 'Build',
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
      setError(err instanceof Error ? err.message : 'Failed to start build');
    } finally {
      setSubmitting(false);
    }
  }, [repoPath, task, maxAttempts, runTimeoutMs, agentBackend, branch, openPr, skipConfirmation, handleClose, setActiveRun, navigate]);

  if (!open) return null;

  return (
    <DialogOverlay onClose={handleClose}>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">Build Feature</h2>
        <button onClick={handleClose} className="text-muted-foreground hover:text-foreground">✕</button>
      </div>

      <div className="flex flex-col gap-4">
        <RepoPathField value={repoPath} onChange={setRepoPath} />

        <div>
          <label className="mb-1 block text-sm font-medium">Feature Description</label>
          <textarea
            value={task}
            onChange={(e) => setTask(e.target.value)}
            placeholder="Describe the feature to build..."
            rows={4}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </div>

        <div className="flex items-center gap-2 rounded-md border border-purple-500/30 bg-purple-500/10 px-3 py-2">
          <Hammer className="h-4 w-4 text-purple-400" />
          <span className="text-sm text-purple-300">Build Mode — generates code, verifies, and repairs</span>
        </div>

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
            branchPlaceholder="feat/my-feature"
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
                ? 'cursor-not-allowed bg-purple-500/30 text-purple-400/50'
                : 'bg-purple-600 text-white hover:bg-purple-700',
            )}
          >
            <Hammer className="h-4 w-4" />
            {submitting ? 'Starting...' : 'Start Build'}
          </button>
        </div>
      </div>
    </DialogOverlay>
  );
}
