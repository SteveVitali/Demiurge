import type { RunMode } from '@/api/types';
import { cn } from '@/lib/utils';

const RUN_MODES: { value: RunMode; label: string; description: string }[] = [
  { value: 'Full', label: 'Full', description: 'Verify → Analyze → Repair loop' },
  { value: 'Build', label: 'Build', description: 'Generate code → Verify → Repair' },
  { value: 'PlanOnly', label: 'Plan Only', description: 'Inspect + plan, no execution' },
  { value: 'VerifyOnly', label: 'Verify Only', description: 'Run verifiers only' },
  { value: 'InspectOnly', label: 'Inspect Only', description: 'Inspect repo only' },
];

export const AGENT_BACKENDS = [
  { value: 'claude-agent-sdk', label: 'Claude Agent SDK' },
  { value: 'legacy', label: 'Legacy (ClaudeClient)' },
];

export function RunModeSelector({ mode, onChange }: { mode: RunMode; onChange: (m: RunMode) => void }) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium">Mode</label>
      <div className="flex flex-wrap gap-2">
        {RUN_MODES.map((m) => (
          <button
            key={m.value}
            onClick={() => onChange(m.value)}
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
  );
}

export function BudgetFields({
  maxAttempts,
  onMaxAttemptsChange,
  runTimeoutMs,
  onRunTimeoutMsChange,
  agentBackend,
  onAgentBackendChange,
}: {
  maxAttempts: number;
  onMaxAttemptsChange: (v: number) => void;
  runTimeoutMs: number;
  onRunTimeoutMsChange: (v: number) => void;
  agentBackend: string;
  onAgentBackendChange: (v: string) => void;
}) {
  return (
    <>
      <div className="flex items-center gap-3">
        <label className="w-32 text-xs text-muted-foreground">Max Attempts</label>
        <input
          type="number"
          min={1}
          max={10}
          value={maxAttempts}
          onChange={(e) => onMaxAttemptsChange(Number(e.target.value))}
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
            if (!isNaN(m)) onRunTimeoutMsChange(m * 60000);
          }}
          className="w-20 rounded-md border border-border bg-background px-2 py-1 text-sm"
        />
      </div>
      <div className="flex items-center gap-3">
        <label className="w-32 text-xs text-muted-foreground">Agent Backend</label>
        <select
          value={agentBackend}
          onChange={(e) => onAgentBackendChange(e.target.value)}
          className="rounded-md border border-border bg-background px-2 py-1 text-sm"
        >
          {AGENT_BACKENDS.map((b) => (
            <option key={b.value} value={b.value}>{b.label}</option>
          ))}
        </select>
      </div>
    </>
  );
}

export function GitFields({
  branch,
  onBranchChange,
  openPr,
  onOpenPrChange,
  skipConfirmation,
  onSkipConfirmationChange,
  branchPlaceholder = 'fix/my-branch',
}: {
  branch: string;
  onBranchChange: (v: string) => void;
  openPr: boolean;
  onOpenPrChange: (v: boolean) => void;
  skipConfirmation: boolean;
  onSkipConfirmationChange: (v: boolean) => void;
  branchPlaceholder?: string;
}) {
  return (
    <>
      <div className="flex items-center gap-3">
        <label className="w-32 text-xs text-muted-foreground">Branch name</label>
        <input
          type="text"
          value={branch}
          onChange={(e) => onBranchChange(e.target.value)}
          placeholder={branchPlaceholder}
          className="flex-1 rounded-md border border-border bg-background px-2 py-1 text-sm"
        />
      </div>
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <input type="checkbox" checked={openPr} onChange={(e) => onOpenPrChange(e.target.checked)} />
        Open PR after success
      </label>
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <input type="checkbox" checked={skipConfirmation} onChange={(e) => onSkipConfirmationChange(e.target.checked)} />
        Skip confirmation
      </label>
    </>
  );
}
