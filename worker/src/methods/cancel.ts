// Spec §10.2: Cancel active task

import { WorkerState } from './initialize';

export async function handleCancel(
  state: WorkerState,
  params: Record<string, unknown>,
): Promise<{ cancelled: boolean; taskId: string | null }> {
  const taskId = state.activeTaskId;
  state.cancelled = true;
  await state.browserManager.closeActiveContext();
  state.activeTaskId = null;
  process.stderr.write(`[worker] Task cancelled: ${taskId}\n`);
  return { cancelled: true, taskId };
}
