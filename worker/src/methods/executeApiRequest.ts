// Spec §10.5: Execute API request — optionally with browser auth storage state

import * as http from 'http';
import * as https from 'https';
import * as url from 'url';
import * as fs from 'fs';
import { WorkerState } from './initialize';
import {
  ExecuteApiRequestParams,
  ApiRequestResult,
  ArtifactMetadata,
  ErrorCodes,
} from '../rpc/types';

export async function handleExecuteApiRequest(
  state: WorkerState,
  params: Record<string, unknown>,
): Promise<ApiRequestResult> {
  if (!state.initialized || !state.artifactWriter) {
    throw Object.assign(new Error('Worker not initialized'), { code: ErrorCodes.NOT_INITIALIZED });
  }

  const p = params as unknown as ExecuteApiRequestParams;
  if (!p.taskId || !p.method || !p.url) {
    throw Object.assign(new Error('Missing required params: taskId, method, url'), { code: ErrorCodes.INVALID_PARAMS });
  }

  if (state.activeTaskId) {
    throw Object.assign(new Error(`Task already active: ${state.activeTaskId}`), { code: ErrorCodes.INTERNAL_ERROR });
  }

  state.activeTaskId = p.taskId;
  const startTime = Date.now();
  const artifacts: ArtifactMetadata[] = [];
  const timeoutMs = p.timeoutMs ?? 30000;

  try {
    // Merge headers from storage state if provided
    let headers: Record<string, string> = { ...(p.headers || {}) };
    if (p.storageStatePath && fs.existsSync(p.storageStatePath)) {
      try {
        const storageState = JSON.parse(fs.readFileSync(p.storageStatePath, 'utf-8'));
        if (storageState.apiHeaders) {
          headers = { ...storageState.apiHeaders, ...headers };
        }
      } catch { /* ignore parse errors */ }
    }

    const parsedUrl = new url.URL(p.url);
    const requestModule = parsedUrl.protocol === 'https:' ? https : http;

    const response = await new Promise<{ status: number; body: string; headers: Record<string, string> }>((resolve, reject) => {
      const reqHeaders: Record<string, string> = { ...headers };
      if (p.body && !reqHeaders['Content-Type']) {
        reqHeaders['Content-Type'] = 'application/json';
      }
      if (p.body) {
        reqHeaders['Content-Length'] = Buffer.byteLength(p.body).toString();
      }

      const req = requestModule.request(p.url, {
        method: p.method,
        headers: reqHeaders,
        timeout: timeoutMs,
      }, (res) => {
        let data = '';
        res.on('data', (chunk: Buffer) => { data += chunk.toString(); });
        res.on('end', () => {
          const respHeaders: Record<string, string> = {};
          for (const [k, v] of Object.entries(res.headers)) {
            if (typeof v === 'string') respHeaders[k] = v;
            else if (Array.isArray(v)) respHeaders[k] = v.join(', ');
          }
          resolve({ status: res.statusCode ?? 0, body: data, headers: respHeaders });
        });
      });
      req.on('error', reject);
      req.on('timeout', () => { req.destroy(); reject(new Error('Request timeout')); });
      if (p.body) req.write(p.body);
      req.end();
    });

    // Write API request/response as artifact
    const apiLog = JSON.stringify({
      request: { method: p.method, url: p.url, headers, body: p.body },
      response: { status: response.status, headers: response.headers, body: response.body },
    }, null, 2);
    const art = await state.artifactWriter.writeApiRequestResponse(apiLog, p.taskId, 'api_request');
    artifacts.push(art);

    return {
      status: response.status,
      headers: response.headers,
      body: response.body,
      durationMs: Date.now() - startTime,
      artifacts,
    };
  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    throw Object.assign(new Error(`API request failed: ${errorMessage}`), { code: ErrorCodes.REQUEST_FAILED });
  } finally {
    state.activeTaskId = null;
  }
}
