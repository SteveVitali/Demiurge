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

function getStepState(step: PipelineStep, currentStatus: RunStatus | null, stepIndex: number, activeIndex: number): StepState {
  if (!currentStatus) return 'pending';

  if (step.states.includes(currentStatus)) return 'active';

  const terminalFailures: RunStatus[] = ['Exhausted', 'EnvironmentFailed'];
  if (step.id === 'done' && terminalFailures.includes(currentStatus)) return 'failed';
  if (step.id === 'done' && currentStatus === 'Succeeded') return 'completed';
  if (step.id === 'done' && (currentStatus === 'Cancelled' || currentStatus === 'Interrupted')) return 'completed';

  if (stepIndex < activeIndex) return 'completed';
  return 'pending';
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
}

export function PipelineStepper({ currentStatus, showBuildStep = false, attemptNumber = 1 }: PipelineStepperProps) {
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
          const state = getStepState(step, currentStatus, stepIndex, activeIndex);
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
