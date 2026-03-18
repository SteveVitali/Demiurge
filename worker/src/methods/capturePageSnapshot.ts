// Spec §10.6: Capture page snapshot — screenshot, DOM, accessibility tree

import { WorkerState } from './initialize';
import {
  CapturePageSnapshotParams,
  PageSnapshotResult,
  ArtifactMetadata,
  ErrorCodes,
} from '../rpc/types';

export async function handleCapturePageSnapshot(
  state: WorkerState,
  params: Record<string, unknown>,
): Promise<PageSnapshotResult> {
  if (!state.initialized || !state.artifactWriter) {
    throw Object.assign(new Error('Worker not initialized'), { code: ErrorCodes.NOT_INITIALIZED });
  }

  const p = params as unknown as CapturePageSnapshotParams;
  if (!p.taskId || !p.url) {
    throw Object.assign(new Error('Missing required params: taskId, url'), { code: ErrorCodes.INVALID_PARAMS });
  }

  if (state.activeTaskId) {
    throw Object.assign(new Error(`Task already active: ${state.activeTaskId}`), { code: ErrorCodes.BROWSER_LAUNCH_FAILED });
  }

  state.activeTaskId = p.taskId;
  const artifacts: ArtifactMetadata[] = [];
  const timeoutMs = p.timeoutMs ?? 30000;

  try {
    const { page } = await state.browserManager.createContext(p.storageStatePath);

    await page.goto(p.url, { timeout: timeoutMs, waitUntil: 'domcontentloaded' });

    if (p.waitForSelector) {
      await page.locator(p.waitForSelector).waitFor({ state: 'visible', timeout: timeoutMs });
    }

    // Screenshot
    const screenshot = await state.artifactWriter.writeScreenshot(page, 'snapshot', p.taskId);
    artifacts.push(screenshot);

    // DOM snapshot
    const html = await page.content();
    const domArt = await state.artifactWriter.writeDomSnapshot(html, p.taskId, 'snapshot');
    artifacts.push(domArt);

    // Accessibility snapshot
    const accessTree = await page.accessibility.snapshot();
    const accessArt = await state.artifactWriter.writeAccessibilitySnapshot(
      JSON.stringify(accessTree, null, 2), p.taskId, 'snapshot',
    );
    artifacts.push(accessArt);

    return { artifacts };
  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    process.stderr.write(`[worker] Page snapshot error: ${errorMessage}\n`);
    return { artifacts, errorMessage };
  } finally {
    state.activeTaskId = null;
  }
}
