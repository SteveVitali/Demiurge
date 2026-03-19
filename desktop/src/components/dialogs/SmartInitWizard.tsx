import { useState, useCallback, useEffect, useRef, Suspense, lazy } from 'react';
import { Sparkles, CheckCircle, Loader2, ArrowRight, ArrowLeft } from 'lucide-react';
import { useAppStore } from '@/stores/app.store';
import { smartInit, getConfig } from '@/api/endpoints';
import { cn } from '@/lib/utils';
import { DialogOverlay } from '@/components/shared/DialogOverlay';
import { RepoPathField } from '@/components/shared/RepoPathField';

const MonacoEditor = lazy(() => import('@monaco-editor/react'));

type WizardStep = 'select-repo' | 'task-hint' | 'running' | 'review' | 'done';

export function SmartInitWizard() {
  const open = useAppStore((s) => s.smartInitWizardOpen);
  const setOpen = useAppStore((s) => s.setSmartInitWizardOpen);
  const activeRepoPath = useAppStore((s) => s.activeRepoPath);
  const setActiveRepo = useAppStore((s) => s.setActiveRepo);

  const [step, setStep] = useState<WizardStep>('select-repo');
  const [repoPath, setRepoPath] = useState('');
  const [taskHint, setTaskHint] = useState('');
  const [progress, setProgress] = useState<string[]>([]);
  const [generatedManifest, setGeneratedManifest] = useState('');
  const [generatedRequirements, setGeneratedRequirements] = useState('');
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Clear polling interval on unmount or close
  const clearPoll = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setStep('select-repo');
      setRepoPath(activeRepoPath ?? '');
      setTaskHint('');
      setProgress([]);
      setGeneratedManifest('');
      setGeneratedRequirements('');
      setError(null);
    } else {
      clearPoll();
    }
  }, [open, activeRepoPath, clearPoll]);

  useEffect(() => clearPoll, [clearPoll]);

  const handleClose = useCallback(() => {
    clearPoll();
    setOpen(false);
  }, [setOpen, clearPoll]);

  const handleStartInit = useCallback(async () => {
    if (!repoPath.trim()) {
      setError('Please select a repo path');
      return;
    }
    setStep('running');
    setError(null);
    setProgress(['Starting smart init...']);

    try {
      await smartInit(repoPath.trim(), taskHint.trim() || undefined);
      setProgress((p) => [...p, 'Agent analyzing repository...']);

      // Poll for config to appear (agent writes it asynchronously)
      let pollCount = 0;
      const maxPollCount = 60; // 60 seconds
      pollRef.current = setInterval(async () => {
        pollCount++;
        try {
          const config = await getConfig(repoPath.trim());
          if (config.manifestExists) {
            clearPoll();
            setGeneratedManifest(config.manifestYaml ?? '');
            setGeneratedRequirements(config.requirementsYaml ?? '');
            setProgress((p) => [...p, 'Configuration generated successfully!']);
            setStep('review');
          } else if (pollCount >= maxPollCount) {
            clearPoll();
            setProgress((p) => [...p, 'Timed out waiting for config generation.']);
            setStep('review');
          } else if (pollCount % 5 === 0) {
            setProgress((p) => [...p, `Still running... (${pollCount}s)`]);
          }
        } catch {
          if (pollCount >= maxPollCount) {
            clearPoll();
            setError('Timed out waiting for configuration');
            setStep('select-repo');
          }
        }
      }, 1000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Smart init failed');
      setStep('select-repo');
    }
  }, [repoPath, taskHint, clearPoll]);

  const handleConfirm = useCallback(() => {
    setActiveRepo(repoPath.trim());
    setStep('done');
    setTimeout(handleClose, 1500);
  }, [repoPath, setActiveRepo, handleClose]);

  if (!open) return null;

  return (
    <DialogOverlay onClose={handleClose} maxWidth="max-w-2xl">
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-yellow-400" />
            <h2 className="text-lg font-semibold">Smart Init</h2>
          </div>
          <button onClick={handleClose} className="text-muted-foreground hover:text-foreground">✕</button>
        </div>

        {/* Step Indicator */}
        <div className="mb-6 flex items-center gap-2">
          {(['select-repo', 'task-hint', 'running', 'review', 'done'] as WizardStep[]).map((s, i) => (
            <div key={s} className="flex items-center gap-2">
              <div className={cn(
                'flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium',
                step === s ? 'bg-blue-600 text-white' :
                (['select-repo', 'task-hint', 'running', 'review', 'done'].indexOf(step) > i)
                  ? 'bg-green-600 text-white' : 'bg-accent text-muted-foreground',
              )}>
                {i + 1}
              </div>
              {i < 4 && <div className="h-px w-8 bg-border" />}
            </div>
          ))}
        </div>

        {/* Step Content */}
        {step === 'select-repo' && (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Select a repository to auto-configure. The agent will analyze the project structure
              and generate <code className="text-foreground">demiurge.yaml</code> and <code className="text-foreground">requirements.yaml</code>.
            </p>
            <RepoPathField value={repoPath} onChange={setRepoPath} label="Repository Path" />
            {error && (
              <div className="rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-400">
                {error}
              </div>
            )}
            <div className="flex justify-end">
              <button
                onClick={() => setStep('task-hint')}
                disabled={!repoPath.trim()}
                className={cn(
                  'flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium',
                  repoPath.trim() ? 'bg-blue-600 text-white hover:bg-blue-700' : 'cursor-not-allowed bg-blue-500/30 text-blue-400/50',
                )}
              >
                Next <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}

        {step === 'task-hint' && (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Optionally provide a task hint to guide the agent&apos;s configuration generation.
            </p>
            <div>
              <label className="mb-1 block text-sm font-medium">Task Hint (optional)</label>
              <textarea
                value={taskHint}
                onChange={(e) => setTaskHint(e.target.value)}
                placeholder="e.g., This is a Node.js Express API with MongoDB..."
                rows={3}
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              />
            </div>
            <div className="flex justify-between">
              <button
                onClick={() => setStep('select-repo')}
                className="flex items-center gap-1.5 rounded-md border border-border px-4 py-2 text-sm hover:bg-accent"
              >
                <ArrowLeft className="h-4 w-4" /> Back
              </button>
              <button
                onClick={() => void handleStartInit()}
                className="flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                <Sparkles className="h-4 w-4" /> Start Init
              </button>
            </div>
          </div>
        )}

        {step === 'running' && (
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-2 text-sm text-blue-400">
              <Loader2 className="h-4 w-4 animate-spin" />
              Agent is analyzing the repository...
            </div>
            <div className="max-h-64 overflow-y-auto rounded-md border border-border bg-black/30 p-3">
              {progress.map((msg, i) => (
                <div key={i} className="py-0.5 font-mono text-xs text-muted-foreground">
                  {msg}
                </div>
              ))}
            </div>
          </div>
        )}

        {step === 'review' && (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Review the generated configuration below. You can edit before confirming.
            </p>

            <div>
              <h3 className="mb-1 text-sm font-medium">demiurge.yaml</h3>
              <div className="overflow-hidden rounded-md border border-border">
                <Suspense fallback={<div className="flex h-48 items-center justify-center text-xs text-muted-foreground">Loading editor...</div>}>
                  <MonacoEditor
                    height="200px"
                    language="yaml"
                    theme="vs-dark"
                    value={generatedManifest}
                    onChange={(v) => setGeneratedManifest(v ?? '')}
                    options={{ minimap: { enabled: false }, fontSize: 12, lineNumbers: 'on', scrollBeyondLastLine: false, tabSize: 2 }}
                  />
                </Suspense>
              </div>
            </div>

            {generatedRequirements && (
              <div>
                <h3 className="mb-1 text-sm font-medium">requirements.yaml</h3>
                <div className="overflow-hidden rounded-md border border-border">
                  <Suspense fallback={<div className="flex h-48 items-center justify-center text-xs text-muted-foreground">Loading editor...</div>}>
                    <MonacoEditor
                      height="200px"
                      language="yaml"
                      theme="vs-dark"
                      value={generatedRequirements}
                      onChange={(v) => setGeneratedRequirements(v ?? '')}
                      options={{ minimap: { enabled: false }, fontSize: 12, lineNumbers: 'on', scrollBeyondLastLine: false, tabSize: 2 }}
                    />
                  </Suspense>
                </div>
              </div>
            )}

            <div className="flex justify-between">
              <button
                onClick={() => setStep('task-hint')}
                className="flex items-center gap-1.5 rounded-md border border-border px-4 py-2 text-sm hover:bg-accent"
              >
                <ArrowLeft className="h-4 w-4" /> Back
              </button>
              <button
                onClick={handleConfirm}
                className="flex items-center gap-1.5 rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700"
              >
                <CheckCircle className="h-4 w-4" /> Confirm
              </button>
            </div>
          </div>
        )}

        {step === 'done' && (
          <div className="flex flex-col items-center gap-3 py-8">
            <CheckCircle className="h-12 w-12 text-green-400" />
            <p className="text-sm font-medium text-green-400">Configuration saved successfully!</p>
          </div>
        )}
    </DialogOverlay>
  );
}
