// Spec §10.2: Worker shutdown — close browser, stop server

import { WorkerState } from './initialize';

export async function handleShutdown(state: WorkerState): Promise<{ success: boolean }> {
  process.stderr.write('[worker] Shutdown requested\n');
  await state.browserManager.close();
  state.initialized = false;
  // Allow response to be sent before exiting
  setTimeout(() => process.exit(0), 100);
  return { success: true };
}
