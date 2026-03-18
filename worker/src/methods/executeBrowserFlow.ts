// Spec §10.3: Execute browser flow — navigate, perform actions, check assertions, capture artifacts

import { Page, BrowserContext } from 'playwright';
import { WorkerState } from './initialize';
import { JsonRpcServer } from '../rpc/server';
import {
  ExecuteBrowserFlowParams,
  BrowserFlowResult,
  BrowserActionSpec,
  AssertionSpec,
  ObservationResult,
  ArtifactMetadata,
  ErrorCodes,
} from '../rpc/types';

export async function handleExecuteBrowserFlow(
  state: WorkerState,
  params: Record<string, unknown>,
  rpcServer: JsonRpcServer,
): Promise<BrowserFlowResult> {
  if (!state.initialized || !state.artifactWriter) {
    throw Object.assign(new Error('Worker not initialized'), { code: ErrorCodes.NOT_INITIALIZED });
  }

  const p = params as unknown as ExecuteBrowserFlowParams;
  if (!p.taskId || !p.entryUrl) {
    throw Object.assign(new Error('Missing required params: taskId, entryUrl'), { code: ErrorCodes.INVALID_PARAMS });
  }

  // Spec §10.3: strictly serial — one active task at a time
  if (state.activeTaskId) {
    throw Object.assign(new Error(`Task already active: ${state.activeTaskId}`), { code: ErrorCodes.BROWSER_LAUNCH_FAILED });
  }

  state.activeTaskId = p.taskId;
  state.cancelled = false;
  const startTime = Date.now();
  const observations: ObservationResult[] = [];
  const artifacts: ArtifactMetadata[] = [];
  const consoleLogs: Array<{ type: string; text: string; timestamp: string }> = [];
  const networkRequests: Array<{ url: string; method: string; status?: number; resourceType: string }> = [];
  const timeoutMs = p.timeoutMs ?? 30000;

  // Spec §9.9: Max 500 captured network requests per task
  const MAX_NETWORK_REQUESTS = 500;
  // Spec §9.10: Max 200 console entries, truncated to 4096 chars each
  const MAX_CONSOLE_ENTRIES = 200;
  const MAX_CONSOLE_CHAR_LENGTH = 4096;

  let context: BrowserContext | undefined;
  let page: Page | undefined;

  try {
    // Create fresh browser context per task (Spec §10.3)
    const result = await state.browserManager.createContext(p.storageStatePath);
    context = result.context;
    page = result.page;

    // Start tracing (Spec §12.3: BrowserTrace artifact)
    await context.tracing.start({ screenshots: true, snapshots: true });

    // Spec §9.10: Capture console logs with limits
    page.on('console', (msg) => {
      const text = msg.text().substring(0, MAX_CONSOLE_CHAR_LENGTH);
      if (consoleLogs.length < MAX_CONSOLE_ENTRIES) {
        consoleLogs.push({
          type: msg.type(),
          text,
          timestamp: new Date().toISOString(),
        });
      } else if (msg.type() === 'error') {
        // Spec §9.10: Beyond limit, only error-level entries are kept
        consoleLogs.push({
          type: msg.type(),
          text,
          timestamp: new Date().toISOString(),
        });
      }
    });
    page.on('pageerror', (error) => {
      if (consoleLogs.length < MAX_CONSOLE_ENTRIES) {
        consoleLogs.push({
          type: 'pageerror',
          text: error.message.substring(0, MAX_CONSOLE_CHAR_LENGTH),
          timestamp: new Date().toISOString(),
        });
      }
    });

    // Spec §9.9: Capture network requests with limit of 500
    page.on('response', (response) => {
      if (networkRequests.length < MAX_NETWORK_REQUESTS) {
        networkRequests.push({
          url: response.url(),
          method: response.request().method(),
          status: response.status(),
          resourceType: response.request().resourceType(),
        });
      }
    });

    // Navigate to entry URL
    rpcServer.sendNotification('progress', { taskId: p.taskId, step: 'navigate', message: `Navigating to ${p.entryUrl}` });
    try {
      await page.goto(p.entryUrl, { timeout: timeoutMs, waitUntil: 'domcontentloaded' });
    } catch (navErr: any) {
      // Spec §9.13: Use NAVIGATION_FAILED error code
      throw Object.assign(new Error(`Navigation failed: ${navErr.message}`), { code: ErrorCodes.NAVIGATION_FAILED });
    }

    if (state.cancelled) {
      return makeResult('error', observations, artifacts, 'Task cancelled', startTime);
    }

    // Execute actions
    if (p.actions && p.actions.length > 0) {
      for (const action of p.actions) {
        if (state.cancelled) {
          return makeResult('error', observations, artifacts, 'Task cancelled during actions', startTime);
        }
        rpcServer.sendNotification('progress', {
          taskId: p.taskId, step: 'action', message: action.description,
        });
        await executeAction(page, action, timeoutMs);
      }
    }

    // Run assertions
    let allPassed = true;
    if (p.assertions && p.assertions.length > 0) {
      for (const assertion of p.assertions) {
        if (state.cancelled) {
          return makeResult('error', observations, artifacts, 'Task cancelled during assertions', startTime);
        }
        const obs = await checkAssertion(page, assertion);
        observations.push(obs);
        if (obs.observationType === 'assertion_failed') {
          allPassed = false;
        }
      }
    }

    // Capture artifacts per plan
    if (p.artifactPlan && p.artifactPlan.length > 0) {
      for (const capture of p.artifactPlan) {
        const art = await captureArtifact(state, page, context, capture, p.taskId);
        if (art) artifacts.push(art);
      }
    }

    // Always capture: screenshot, console, network, DOM snapshot, accessibility snapshot
    const screenshot = await state.artifactWriter.writeScreenshot(page, 'final', p.taskId);
    artifacts.push(screenshot);

    const consoleArt = await state.artifactWriter.writeConsoleLog(
      JSON.stringify(consoleLogs, null, 2), p.taskId,
    );
    artifacts.push(consoleArt);

    const networkArt = await state.artifactWriter.writeNetworkSummary(
      JSON.stringify(networkRequests, null, 2), p.taskId,
    );
    artifacts.push(networkArt);

    // DOM snapshot
    const html = await page.content();
    const domArt = await state.artifactWriter.writeDomSnapshot(html, p.taskId, 'final');
    artifacts.push(domArt);

    // Accessibility snapshot
    const accessTree = await page.accessibility.snapshot();
    const accessArt = await state.artifactWriter.writeAccessibilitySnapshot(
      JSON.stringify(accessTree, null, 2), p.taskId, 'final',
    );
    artifacts.push(accessArt);

    // Stop tracing and write trace artifact
    const traceTs = Date.now();
    const traceRelPath = `${state.runId}/${p.taskId}/traces/trace_${traceTs}.zip`;
    const tracePath = `${state.artifactRoot}/${traceRelPath}`;
    const traceDir = tracePath.substring(0, tracePath.lastIndexOf('/'));
    const fs = await import('fs');
    fs.mkdirSync(traceDir, { recursive: true });
    await context.tracing.stop({ path: tracePath });
    if (fs.existsSync(tracePath)) {
      const traceData = fs.readFileSync(tracePath);
      const { sha256 } = await import('../utils/checksum');
      artifacts.push({
        artifactType: 'BrowserTrace',
        relativePath: traceRelPath,
        contentType: 'application/zip',
        sizeBytes: traceData.length,
        checksumSha256: sha256(traceData),
        label: 'trace',
      });
    }

    const status = allPassed ? 'pass' : 'fail';
    return makeResult(status, observations, artifacts, undefined, startTime);

  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    process.stderr.write(`[worker] Browser flow error: ${errorMessage}\n`);

    // Try to capture error screenshot
    if (page && state.artifactWriter) {
      try {
        const errScreenshot = await state.artifactWriter.writeScreenshot(page, 'error', p.taskId);
        artifacts.push(errScreenshot);
      } catch { /* ignore */ }
    }

    const isTimeout = errorMessage.includes('Timeout') || errorMessage.includes('timeout');
    return makeResult(
      isTimeout ? 'timeout' : 'error',
      observations,
      artifacts,
      errorMessage,
      startTime,
    );
  } finally {
    state.activeTaskId = null;
  }
}

async function executeAction(page: Page, action: BrowserActionSpec, defaultTimeout: number): Promise<void> {
  const timeout = action.timeoutMs ?? defaultTimeout;

  try {
    switch (action.actionType) {
      case 'navigate':
        if (action.url) {
          try {
            await page.goto(action.url, { timeout, waitUntil: 'domcontentloaded' });
          } catch (navErr: any) {
            throw Object.assign(new Error(`Navigation failed: ${navErr.message}`), { code: ErrorCodes.NAVIGATION_FAILED });
          }
        }
        break;
      case 'click':
        if (action.selector) {
          const locator = resolveSelector(page, action.selector);
          await locator.click({ timeout });
        }
        break;
      case 'fill':
        if (action.selector && action.value !== undefined) {
          const locator = resolveSelector(page, action.selector);
          await locator.fill(action.value, { timeout });
        }
        break;
      case 'type':
        if (action.selector && action.value !== undefined) {
          const locator = resolveSelector(page, action.selector);
          await locator.pressSequentially(action.value, { timeout });
        }
        break;
      case 'press':
        if (action.selector && action.value) {
          const locator = resolveSelector(page, action.selector);
          await locator.press(action.value, { timeout });
        } else if (action.value) {
          await page.keyboard.press(action.value);
        }
        break;
      case 'select':
        if (action.selector && action.value) {
          const locator = resolveSelector(page, action.selector);
          await locator.selectOption(action.value, { timeout });
        }
        break;
      case 'wait':
        if (action.selector) {
          const locator = resolveSelector(page, action.selector);
          await locator.waitFor({ state: 'visible', timeout });
        } else if (action.timeoutMs) {
          await page.waitForTimeout(action.timeoutMs);
        }
        break;
      case 'waitForNavigation':
        await page.waitForLoadState('domcontentloaded', { timeout });
        break;
      case 'scroll':
        if (action.selector) {
          const locator = resolveSelector(page, action.selector);
          await locator.scrollIntoViewIfNeeded({ timeout });
        }
        break;
      default:
        process.stderr.write(`[worker] Unknown action type: ${action.actionType}\n`);
    }
  } catch (err: any) {
    // Spec §9.13: Map Playwright selector errors to SELECTOR_NOT_FOUND
    if (err.code) throw err; // Already has a specific code
    const msg = err.message ?? String(err);
    if (msg.includes('waiting for locator') || msg.includes('locator resolved to') || msg.includes('strict mode violation')) {
      throw Object.assign(new Error(`Selector not found: ${action.selector?.value ?? 'unknown'} — ${msg}`), { code: ErrorCodes.SELECTOR_NOT_FOUND });
    }
    throw err;
  }
}

function resolveSelector(page: Page, ref: { strategy: string; value: string; roleName?: string }) {
  switch (ref.strategy) {
    case 'css':
      return page.locator(ref.value);
    case 'xpath':
      return page.locator(`xpath=${ref.value}`);
    case 'text':
      return page.locator(`text=${ref.value}`);
    case 'role':
      return page.getByRole(ref.roleName as any ?? ref.value as any, { name: ref.value });
    case 'testId':
      return page.getByTestId(ref.value);
    case 'label':
      return page.getByLabel(ref.value);
    case 'placeholder':
      return page.getByPlaceholder(ref.value);
    default:
      return page.locator(ref.value);
  }
}

async function checkAssertion(page: Page, assertion: AssertionSpec): Promise<ObservationResult> {
  const timestamp = new Date().toISOString();

  try {
    switch (assertion.assertionType) {
      case 'elementVisible': {
        if (!assertion.selector) throw new Error('Missing selector for elementVisible');
        const locator = resolveSelector(page, assertion.selector);
        const visible = await locator.isVisible();
        if (visible) {
          return { observationType: 'assertion_passed', message: assertion.description, selector: assertion.selector?.value, expected: 'visible', actual: 'visible', timestamp };
        }
        return { observationType: 'assertion_failed', message: assertion.description, selector: assertion.selector?.value, expected: 'visible', actual: 'not visible', timestamp };
      }
      case 'elementHidden': {
        if (!assertion.selector) throw new Error('Missing selector for elementHidden');
        const locator = resolveSelector(page, assertion.selector);
        const visible = await locator.isVisible();
        if (!visible) {
          return { observationType: 'assertion_passed', message: assertion.description, selector: assertion.selector?.value, expected: 'hidden', actual: 'hidden', timestamp };
        }
        return { observationType: 'assertion_failed', message: assertion.description, selector: assertion.selector?.value, expected: 'hidden', actual: 'visible', timestamp };
      }
      case 'textContent': {
        if (!assertion.selector) throw new Error('Missing selector for textContent');
        const locator = resolveSelector(page, assertion.selector);
        const text = await locator.textContent();
        if (assertion.expected && text?.includes(assertion.expected)) {
          return { observationType: 'assertion_passed', message: assertion.description, selector: assertion.selector?.value, expected: assertion.expected, actual: text ?? '', timestamp };
        }
        return { observationType: 'assertion_failed', message: assertion.description, selector: assertion.selector?.value, expected: assertion.expected, actual: text ?? '', timestamp };
      }
      case 'urlContains': {
        const url = page.url();
        if (assertion.expected && url.includes(assertion.expected)) {
          return { observationType: 'assertion_passed', message: assertion.description, expected: assertion.expected, actual: url, timestamp };
        }
        return { observationType: 'assertion_failed', message: assertion.description, expected: assertion.expected, actual: url, timestamp };
      }
      case 'titleContains': {
        const title = await page.title();
        if (assertion.expected && title.includes(assertion.expected)) {
          return { observationType: 'assertion_passed', message: assertion.description, expected: assertion.expected, actual: title, timestamp };
        }
        return { observationType: 'assertion_failed', message: assertion.description, expected: assertion.expected, actual: title, timestamp };
      }
      default:
        return { observationType: 'assertion_skipped', message: `Unknown assertion type: ${assertion.assertionType}`, timestamp };
    }
  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    // Spec §9.13: Map assertion errors to observation results for structured handling
    if (errorMessage.includes('waiting for locator') || errorMessage.includes('locator resolved to')) {
      return { observationType: 'assertion_error', message: `Selector not found in assertion: ${assertion.description}: ${errorMessage}`, timestamp };
    }
    return { observationType: 'assertion_error', message: `${assertion.description}: ${errorMessage}`, timestamp };
  }
}

async function captureArtifact(
  state: WorkerState,
  page: Page,
  context: BrowserContext,
  capture: { artifactType: string; trigger: string; label?: string },
  taskId: string,
): Promise<ArtifactMetadata | null> {
  if (!state.artifactWriter) return null;

  try {
    switch (capture.artifactType) {
      case 'Screenshot':
        return await state.artifactWriter.writeScreenshot(page, capture.label ?? 'capture', taskId);
      case 'DomSnapshot': {
        const html = await page.content();
        return await state.artifactWriter.writeDomSnapshot(html, taskId, capture.label ?? 'capture');
      }
      case 'AccessibilitySnapshot': {
        const tree = await page.accessibility.snapshot();
        return await state.artifactWriter.writeAccessibilitySnapshot(
          JSON.stringify(tree, null, 2), taskId, capture.label ?? 'capture',
        );
      }
      default:
        return null;
    }
  } catch (err) {
    process.stderr.write(`[worker] Artifact capture error: ${err}\n`);
    return null;
  }
}

function makeResult(
  status: 'pass' | 'fail' | 'error' | 'timeout',
  observations: ObservationResult[],
  artifacts: ArtifactMetadata[],
  errorMessage: string | undefined,
  startTime: number,
): BrowserFlowResult {
  return {
    status,
    observations,
    artifacts,
    errorMessage,
    durationMs: Date.now() - startTime,
  };
}
