import { useState } from 'react';
import { CheckCircle2, ChevronRight, Loader2, XCircle } from 'lucide-react';
import { usePreferencesStore } from '@/stores/preferences.store';
import { useAppStore } from '@/stores/app.store';
import { getHealth } from '@/api/endpoints';

// Desktop Phase 5 — §13.4: First-run onboarding wizard.
// Steps: Welcome → Backend check → API key setup → Repo selection → Done.
// Every step has "Skip Setup" to dismiss immediately.

type WizardStep = 'welcome' | 'backend' | 'apikey' | 'repo' | 'done';

interface BackendCheckState {
  status: 'idle' | 'checking' | 'connected' | 'failed';
}

export function WelcomeWizard() {
  const hasCompleted = usePreferencesStore((s) => s.hasCompletedOnboarding);
  const setCompleted = usePreferencesStore((s) => s.setHasCompletedOnboarding);
  const setApiKeySet = usePreferencesStore((s) => s.setAnthropicApiKeySet);
  const setDefaultRepoPath = usePreferencesStore((s) => s.setDefaultRepoPath);
  const backendStatus = useAppStore((s) => s.backendStatus);

  const [step, setStep] = useState<WizardStep>('welcome');
  const [backendCheck, setBackendCheck] = useState<BackendCheckState>({ status: 'idle' });
  const [apiKey, setApiKey] = useState('');
  const [repoPath, setRepoPath] = useState('');

  if (hasCompleted) return null;

  const skipSetup = () => {
    setCompleted(true);
  };

  const handleCheckBackend = async () => {
    setBackendCheck({ status: 'checking' });
    try {
      await getHealth();
      setBackendCheck({ status: 'connected' });
    } catch {
      setBackendCheck({ status: 'failed' });
    }
  };

  const handleSaveApiKey = () => {
    if (apiKey.trim().startsWith('sk-')) {
      setApiKeySet(true);
      setStep('repo');
    }
  };

  const handleSaveRepo = () => {
    if (repoPath.trim()) {
      setDefaultRepoPath(repoPath.trim());
    }
    setStep('done');
  };

  const handleFinish = () => {
    setCompleted(true);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className="w-full max-w-lg rounded-xl border border-border bg-background p-8 shadow-2xl">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-xl font-semibold text-foreground">
            {step === 'welcome' && 'Welcome to Demiurge'}
            {step === 'backend' && 'Backend Connection'}
            {step === 'apikey' && 'API Key Setup'}
            {step === 'repo' && 'Default Repository'}
            {step === 'done' && 'All Set!'}
          </h1>
          <button
            onClick={skipSetup}
            className="text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            Skip Setup
          </button>
        </div>

        {/* Step indicators */}
        <div className="mb-6 flex gap-1">
          {(['welcome', 'backend', 'apikey', 'repo', 'done'] as WizardStep[]).map((s) => (
            <div
              key={s}
              className={`h-1 flex-1 rounded-full transition-colors ${
                s === step ? 'bg-blue-500' : 
                (['welcome', 'backend', 'apikey', 'repo', 'done'].indexOf(s) < 
                 ['welcome', 'backend', 'apikey', 'repo', 'done'].indexOf(step))
                  ? 'bg-blue-500/40' : 'bg-muted'
              }`}
            />
          ))}
        </div>

        {/* Step content */}
        <div className="min-h-[180px]">
          {step === 'welcome' && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                Demiurge is a last-mile web development automation platform. 
                It verifies, repairs, and builds web applications using AI.
              </p>
              <p className="text-sm text-muted-foreground">
                This quick setup will help you configure the essentials.
                You can change these settings anytime from the Settings screen.
              </p>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li className="flex items-center gap-2">
                  <ChevronRight className="h-3 w-3 text-blue-400" />
                  Check backend connectivity
                </li>
                <li className="flex items-center gap-2">
                  <ChevronRight className="h-3 w-3 text-blue-400" />
                  Configure your Anthropic API key
                </li>
                <li className="flex items-center gap-2">
                  <ChevronRight className="h-3 w-3 text-blue-400" />
                  Set a default repository
                </li>
              </ul>
            </div>
          )}

          {step === 'backend' && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                Demiurge needs a backend server running. Start it with:
              </p>
              <code className="block rounded bg-muted/50 px-3 py-2 text-xs font-mono text-foreground">
                demiurge serve
              </code>
              <div className="flex items-center gap-3">
                <button
                  onClick={handleCheckBackend}
                  disabled={backendCheck.status === 'checking'}
                  className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-50 transition-colors"
                >
                  {backendCheck.status === 'checking' ? (
                    <span className="flex items-center gap-2">
                      <Loader2 className="h-3 w-3 animate-spin" /> Checking...
                    </span>
                  ) : 'Check Connection'}
                </button>
                {backendCheck.status === 'connected' && (
                  <span className="flex items-center gap-1 text-sm text-green-400">
                    <CheckCircle2 className="h-4 w-4" /> Connected
                  </span>
                )}
                {backendCheck.status === 'failed' && (
                  <span className="flex items-center gap-1 text-sm text-red-400">
                    <XCircle className="h-4 w-4" /> Not reachable
                  </span>
                )}
              </div>
              {backendStatus === 'connected' && backendCheck.status === 'idle' && (
                <p className="text-xs text-green-400">Backend is already connected.</p>
              )}
            </div>
          )}

          {step === 'apikey' && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                An Anthropic API key is needed for AI-powered repair and build features.
                Your key is stored locally and never sent anywhere except Anthropic's API.
              </p>
              <input
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="sk-ant-..."
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <p className="text-xs text-muted-foreground">
                You can also set this later in Settings → API Keys.
              </p>
            </div>
          )}

          {step === 'repo' && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                Set a default repository path. This will be pre-filled when starting new runs.
              </p>
              <input
                type="text"
                value={repoPath}
                onChange={(e) => setRepoPath(e.target.value)}
                placeholder="/path/to/your/repo"
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <p className="text-xs text-muted-foreground">
                You can skip this and select a repo when starting a run.
              </p>
            </div>
          )}

          {step === 'done' && (
            <div className="space-y-4">
              <div className="flex items-center gap-3">
                <CheckCircle2 className="h-8 w-8 text-green-400" />
                <div>
                  <p className="text-sm font-medium text-foreground">Setup complete!</p>
                  <p className="text-sm text-muted-foreground">
                    You're ready to start using Demiurge. Create a new run from the dashboard
                    or use the command palette (⌘K).
                  </p>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Navigation */}
        <div className="mt-6 flex justify-between">
          <div>
            {step !== 'welcome' && step !== 'done' && (
              <button
                onClick={() => {
                  const steps: WizardStep[] = ['welcome', 'backend', 'apikey', 'repo', 'done'];
                  const idx = steps.indexOf(step);
                  if (idx > 0) setStep(steps[idx - 1] as WizardStep);
                }}
                className="rounded-md border border-border px-4 py-2 text-sm text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              >
                Back
              </button>
            )}
          </div>
          <div>
            {step === 'welcome' && (
              <button
                onClick={() => setStep('backend')}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 transition-colors"
              >
                Get Started
              </button>
            )}
            {step === 'backend' && (
              <button
                onClick={() => setStep('apikey')}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 transition-colors"
              >
                Next
              </button>
            )}
            {step === 'apikey' && (
              <button
                onClick={apiKey.trim().startsWith('sk-') ? handleSaveApiKey : () => setStep('repo')}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 transition-colors"
              >
                {apiKey.trim().startsWith('sk-') ? 'Save & Continue' : 'Skip'}
              </button>
            )}
            {step === 'repo' && (
              <button
                onClick={handleSaveRepo}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 transition-colors"
              >
                {repoPath.trim() ? 'Save & Finish' : 'Skip & Finish'}
              </button>
            )}
            {step === 'done' && (
              <button
                onClick={handleFinish}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 transition-colors"
              >
                Start Using Demiurge
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
