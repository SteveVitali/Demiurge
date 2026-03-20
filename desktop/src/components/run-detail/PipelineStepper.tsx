import { cn } from '@/lib/utils';
import type { RunStatus } from '@/api/types';
import { motion } from 'framer-motion';

interface PipelineStep {
  id: string;
  label: string;
  states: RunStatus[];
}

const pipelineSteps: PipelineStep[] = [
  { id: 'inspect', label: 'Inspect', states: ['InspectingRepo'] },
  { id: 'compile', label: 'Compile', states: ['CompilingRequirements'] },
  { id: 'plan', label: 'Plan', states: ['PlanningEnvironment', 'PlanningFeature'] },
  { id: 'build', label: 'Build', states: ['GeneratingCode'] },
  { id: 'boot', label: 'Boot', states: ['BootstrappingEnvironment', 'SeedingFixtures', 'BootstrappingAuth'] },
  { id: 'verify', label: 'Verify', states: ['ReadyToVerify', 'Verifying'] },
  { id: 'analyze', label: 'Analyze', states: ['AnalyzingFailure', 'PlanningRepair'] },
  { id: 'repair', label: 'Repair', states: ['Repairing', 'RepairFailed'] },
  { id: 'reset', label: 'Reset', states: ['SoftResettingEnvironment', 'RebuildingEnvironment', 'PlanningRerun'] },
  { id: 'done', label: 'Done', states: ['Succeeded', 'Exhausted', 'Cancelled', 'Interrupted', 'EnvironmentFailed'] },
];

type StepState = 'completed' | 'active' | 'pending' | 'failed' | 'skipped';

// For terminal failure statuses, map back to the pipeline step where the failure originated.
// This prevents steps that never ran from being shown as "completed".
function getFailureStepIndex(currentStatus: RunStatus, attemptCount: number): number {
  if (currentStatus === 'EnvironmentFailed') {
    return pipelineSteps.findIndex((s) => s.id === 'boot');
  }
  if (currentStatus === 'Exhausted') {
    // Infer origin from attemptCount: 0 means we never reached verification (boot failure),
    // >0 means we exhausted the repair loop (repair was the last active step)
    if (attemptCount === 0) return pipelineSteps.findIndex((s) => s.id === 'boot');
    return pipelineSteps.findIndex((s) => s.id === 'repair');
  }
  return -1;
}

function getStepState(step: PipelineStep, currentStatus: RunStatus | null, stepIndex: number, activeIndex: number, attemptCount: number): StepState {
  if (!currentStatus) return 'pending';

  // Check if this is a terminal state
  const terminalFailures: RunStatus[] = ['Exhausted', 'EnvironmentFailed'];
  const isTerminalFailure = terminalFailures.includes(currentStatus);
  const isTerminalSuccess = currentStatus === 'Succeeded';
  const isTerminalCancel = currentStatus === 'Cancelled' || currentStatus === 'Interrupted';
  const isTerminal = isTerminalFailure || isTerminalSuccess || isTerminalCancel;

  // For terminal failures, determine which step actually failed
  const failedAtIndex = isTerminalFailure ? getFailureStepIndex(currentStatus, attemptCount) : -1;

  // Done step handling
  if (step.id === 'done') {
    if (isTerminalFailure) return 'failed';
    if (isTerminalSuccess) return 'completed';
    if (isTerminalCancel) return 'completed';
    // If still in-progress, done is pending
    return 'pending';
  }

  // Non-done steps when run is still in progress
  if (!isTerminal) {
    if (step.states.includes(currentStatus)) return 'active';
    if (stepIndex < activeIndex) return 'completed';
    return 'pending';
  }

  // Non-done steps when run reached a terminal state
  if (isTerminalSuccess) return 'completed';

  // Terminal failure: steps before the failed step completed, the failed step is failed, steps after are skipped
  if (failedAtIndex >= 0) {
    if (stepIndex < failedAtIndex) return 'completed';
    if (stepIndex === failedAtIndex) return 'failed';
    return 'skipped';
  }

  // Exhausted / Cancelled / Interrupted without a known failure origin:
  // mark steps before done as completed (they ran in the verify/repair loop)
  if (stepIndex < activeIndex) return 'completed';
  return 'skipped';
}

function getActiveStepIndex(currentStatus: RunStatus | null): number {
  if (!currentStatus) return -1;
  return pipelineSteps.findIndex((s) => s.states.includes(currentStatus));
}

const stateColors: Record<StepState, string> = {
  completed: 'bg-emerald-500 border-emerald-500',
  active: 'bg-blue-500 border-blue-500',
  pending: 'bg-transparent border-zinc-600',
  failed: 'bg-red-500 border-red-500',
  skipped: 'bg-transparent border-zinc-700 opacity-40',
};

const lineColors: Record<StepState, string> = {
  completed: 'bg-emerald-500',
  active: 'bg-blue-500',
  pending: 'bg-zinc-700',
  failed: 'bg-red-500',
  skipped: 'bg-zinc-800',
};

interface PipelineStepperProps {
  currentStatus: RunStatus | null;
  showBuildStep?: boolean;
  attemptNumber?: number;
  attemptCount?: number;
}

export function PipelineStepper({ currentStatus, showBuildStep = false, attemptNumber = 1, attemptCount = 0 }: PipelineStepperProps) {
  const activeIndex = getActiveStepIndex(currentStatus);
  const steps = showBuildStep ? pipelineSteps : pipelineSteps.filter((s) => s.id !== 'build');

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-medium text-muted-foreground">Pipeline</h3>
        {attemptNumber > 1 && (
          <span className="text-xs text-muted-foreground">
            Attempt {attemptNumber} (repair loop)
          </span>
        )}
      </div>
      <div
        className="flex items-center"
        role="progressbar"
        aria-valuetext={currentStatus ? `Current step: ${currentStatus}` : 'Not started'}
      >
        {steps.map((step, i) => {
          const stepIndex = pipelineSteps.indexOf(step);
          const state = getStepState(step, currentStatus, stepIndex, activeIndex, attemptCount);
          const isLast = i === steps.length - 1;

          return (
            <div key={step.id} className="flex items-center flex-1">
              {/* Step circle + label */}
              <div className="flex flex-col items-center gap-1">
                <div className="relative">
                  <div
                    className={cn(
                      'h-4 w-4 rounded-full border-2 transition-colors',
                      stateColors[state],
                    )}
                  />
                  {state === 'active' && (
                    <motion.div
                      className="absolute inset-0 rounded-full border-2 border-blue-400"
                      animate={{ scale: [1, 1.6, 1], opacity: [0.8, 0, 0.8] }}
                      transition={{ duration: 1.5, repeat: Infinity }}
                    />
                  )}
                </div>
                <span
                  className={cn(
                    'text-[10px] whitespace-nowrap',
                    state === 'active' ? 'text-blue-400 font-medium' : 'text-muted-foreground',
                  )}
                >
                  {step.label}
                </span>
              </div>

              {/* Connecting line */}
              {!isLast && (
                <div className={cn('h-0.5 flex-1 mx-1', lineColors[state])} />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
