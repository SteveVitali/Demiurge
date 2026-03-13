// Spec §10.4: Auth bootstrap — login via browser form, save storage state

import * as fs from 'fs';
import * as path from 'path';
import { WorkerState } from './initialize';
import { JsonRpcServer } from '../rpc/server';
import {
  ExecuteAuthBootstrapParams,
  AuthBootstrapResult,
  ArtifactMetadata,
  ErrorCodes,
} from '../rpc/types';

export async function handleExecuteAuthBootstrap(
  state: WorkerState,
  params: Record<string, unknown>,
  rpcServer: JsonRpcServer,
): Promise<AuthBootstrapResult> {
  if (!state.initialized || !state.artifactWriter) {
    throw Object.assign(new Error('Worker not initialized'), { code: ErrorCodes.NOT_INITIALIZED });
  }

  const p = params as unknown as ExecuteAuthBootstrapParams;
  if (!p.taskId || !p.mode || !p.storageStateOutput) {
    throw Object.assign(new Error('Missing required params: taskId, mode, storageStateOutput'), { code: ErrorCodes.INVALID_PARAMS });
  }

  if (state.activeTaskId) {
    throw Object.assign(new Error(`Task already active: ${state.activeTaskId}`), { code: ErrorCodes.BROWSER_ERROR });
  }

  state.activeTaskId = p.taskId;
  state.cancelled = false;
  const artifacts: ArtifactMetadata[] = [];
  const timeoutMs = p.timeoutMs ?? 30000;

  try {
    rpcServer.sendNotification('progress', {
      taskId: p.taskId, step: 'auth_bootstrap', message: `Starting auth bootstrap: ${p.mode}`,
    });

    switch (p.mode) {
      case 'BrowserFormLogin': {
        if (!p.loginUrl) {
          throw new Error('loginUrl required for BrowserFormLogin');
        }

        const { context, page } = await state.browserManager.createContext();

        // Navigate to login URL
        rpcServer.sendNotification('progress', {
          taskId: p.taskId, step: 'navigate', message: `Navigating to ${p.loginUrl}`,
        });
        await page.goto(p.loginUrl, { timeout: timeoutMs, waitUntil: 'domcontentloaded' });

        // Try common login form patterns
        // Fill username/email
        const usernameValue = p.credentials.username || p.credentials.email || '';
        const passwordValue = p.credentials.password || '';

        // Try multiple selector strategies for username field
        const usernameSelectors = [
          'input[name="username"]', 'input[name="email"]', 'input[type="email"]',
          'input[name="login"]', '#username', '#email', '#login',
        ];
        let filled = false;
        for (const sel of usernameSelectors) {
          try {
            const loc = page.locator(sel);
            if (await loc.isVisible({ timeout: 2000 })) {
              await loc.fill(usernameValue, { timeout: timeoutMs });
              filled = true;
              break;
            }
          } catch { /* try next */ }
        }
        if (!filled) {
          // Fallback: fill first visible text/email input
          try {
            const firstInput = page.locator('input[type="text"], input[type="email"]').first();
            await firstInput.fill(usernameValue, { timeout: timeoutMs });
          } catch { /* continue anyway */ }
        }

        // Fill password
        const passwordSelectors = ['input[name="password"]', 'input[type="password"]', '#password'];
        filled = false;
        for (const sel of passwordSelectors) {
          try {
            const loc = page.locator(sel);
            if (await loc.isVisible({ timeout: 2000 })) {
              await loc.fill(passwordValue, { timeout: timeoutMs });
              filled = true;
              break;
            }
          } catch { /* try next */ }
        }

        // Click submit
        const submitSelectors = [
          'button[type="submit"]', 'input[type="submit"]',
          'button:has-text("Log in")', 'button:has-text("Sign in")',
          'button:has-text("Login")', 'button:has-text("Submit")',
        ];
        for (const sel of submitSelectors) {
          try {
            const loc = page.locator(sel);
            if (await loc.isVisible({ timeout: 2000 })) {
              await loc.click({ timeout: timeoutMs });
              break;
            }
          } catch { /* try next */ }
        }

        // Wait for navigation after login
        await page.waitForLoadState('domcontentloaded', { timeout: timeoutMs });

        // Save storage state (Spec §10.4)
        const outputDir = path.dirname(p.storageStateOutput);
        fs.mkdirSync(outputDir, { recursive: true });
        await context.storageState({ path: p.storageStateOutput });

        // Capture screenshot as artifact
        const screenshot = await state.artifactWriter!.writeScreenshot(page, 'auth_complete', p.taskId);
        artifacts.push(screenshot);

        // Capture storage state as artifact
        const storageContent = fs.readFileSync(p.storageStateOutput, 'utf-8');
        const storageArt = await state.artifactWriter!.writeStorageState(storageContent, p.taskId);
        artifacts.push(storageArt);

        return {
          success: true,
          storageStatePath: p.storageStateOutput,
          artifacts,
        };
      }

      case 'ApiLogin': {
        // API login: make HTTP request to get token, then create storage state
        const tokenEndpoint = p.credentials.tokenEndpoint || '';
        if (!tokenEndpoint) {
          throw new Error('credentials.tokenEndpoint required for ApiLogin');
        }

        const httpMod = await import('http');
        const httpsMod = await import('https');
        const urlMod = await import('url');

        const parsedUrl = new urlMod.URL(tokenEndpoint);
        const requestModule = parsedUrl.protocol === 'https:' ? httpsMod : httpMod;

        const body = JSON.stringify({
          username: p.credentials.username || p.credentials.email,
          password: p.credentials.password,
        });

        const response = await new Promise<{ status: number; body: string; headers: Record<string, string> }>((resolve, reject) => {
          const req = requestModule.request(tokenEndpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body).toString() },
            timeout: timeoutMs,
          }, (res) => {
            let data = '';
            res.on('data', (chunk: Buffer) => { data += chunk.toString(); });
            res.on('end', () => {
              resolve({
                status: res.statusCode ?? 0,
                body: data,
                headers: (res.headers || {}) as Record<string, string>,
              });
            });
          });
          req.on('error', reject);
          req.write(body);
          req.end();
        });

        let apiHeaders: Record<string, string> = {};
        try {
          const parsed = JSON.parse(response.body);
          if (parsed.token) apiHeaders['Authorization'] = `Bearer ${parsed.token}`;
          if (parsed.access_token) apiHeaders['Authorization'] = `Bearer ${parsed.access_token}`;
        } catch { /* non-JSON response */ }

        // Write minimal storage state file
        const apiOutputDir = path.dirname(p.storageStateOutput);
        fs.mkdirSync(apiOutputDir, { recursive: true });
        const storageState = { cookies: [], origins: [], apiHeaders };
        fs.writeFileSync(p.storageStateOutput, JSON.stringify(storageState, null, 2));

        const storageArt = await state.artifactWriter!.writeStorageState(
          JSON.stringify(storageState, null, 2), p.taskId,
        );
        artifacts.push(storageArt);

        return {
          success: true,
          storageStatePath: p.storageStateOutput,
          apiHeaders,
          artifacts,
        };
      }

      case 'StaticTestToken': {
        const token = p.credentials.token || p.credentials.staticToken || '';
        const apiHeaders: Record<string, string> = { Authorization: `Bearer ${token}` };

        const staticOutputDir = path.dirname(p.storageStateOutput);
        fs.mkdirSync(staticOutputDir, { recursive: true });
        const storageState = { cookies: [], origins: [], apiHeaders };
        fs.writeFileSync(p.storageStateOutput, JSON.stringify(storageState, null, 2));

        const storageArt = await state.artifactWriter!.writeStorageState(
          JSON.stringify(storageState, null, 2), p.taskId,
        );
        artifacts.push(storageArt);

        return {
          success: true,
          storageStatePath: p.storageStateOutput,
          apiHeaders,
          artifacts,
        };
      }

      case 'DevBypassHeader': {
        const apiHeaders: Record<string, string> = {};
        for (const [k, v] of Object.entries(p.credentials)) {
          if (k.startsWith('header_')) {
            apiHeaders[k.substring(7)] = v;
          } else {
            apiHeaders[k] = v;
          }
        }

        const bypassOutputDir = path.dirname(p.storageStateOutput);
        fs.mkdirSync(bypassOutputDir, { recursive: true });
        const storageState = { cookies: [], origins: [], apiHeaders };
        fs.writeFileSync(p.storageStateOutput, JSON.stringify(storageState, null, 2));

        return {
          success: true,
          storageStatePath: p.storageStateOutput,
          apiHeaders,
          artifacts,
        };
      }

      default:
        throw new Error(`Unsupported auth mode: ${p.mode}`);
    }
  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    process.stderr.write(`[worker] Auth bootstrap error: ${errorMessage}\n`);
    return {
      success: false,
      errorMessage,
      artifacts,
    };
  } finally {
    state.activeTaskId = null;
  }
}
