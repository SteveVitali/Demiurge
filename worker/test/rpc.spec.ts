// Phase 6: Worker protocol tests — JSON-RPC 2.0 server loop

import { ChildProcess, spawn } from 'child_process';
import * as path from 'path';
import * as readline from 'readline';

const WORKER_SCRIPT = path.join(__dirname, '..', 'dist', 'index.js');

interface RpcResponse {
  jsonrpc: string;
  id: number | string | null;
  result?: any;
  error?: { code: number; message: string; data?: any };
}

function spawnWorker(): ChildProcess {
  return spawn('node', [WORKER_SCRIPT], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}

function sendRequest(worker: ChildProcess, id: number, method: string, params?: any): void {
  const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params: params || {} });
  worker.stdin!.write(msg + '\n');
}

function readResponse(worker: ChildProcess, timeoutMs = 10000): Promise<RpcResponse> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Response timeout')), timeoutMs);
    const rl = readline.createInterface({ input: worker.stdout!, terminal: false });
    rl.on('line', (line: string) => {
      clearTimeout(timer);
      rl.close();
      try {
        resolve(JSON.parse(line));
      } catch {
        reject(new Error(`Invalid JSON: ${line}`));
      }
    });
  });
}

async function initializeWorker(worker: ChildProcess): Promise<RpcResponse> {
  const tmpDir = require('os').tmpdir();
  sendRequest(worker, 1, 'initialize', {
    artifactRoot: path.join(tmpDir, 'test-artifacts-' + Date.now()),
    worktreePath: tmpDir,
    runId: 'test-run-' + Date.now(),
  });
  return readResponse(worker);
}

describe('JSON-RPC Server', () => {
  let worker: ChildProcess;

  afterEach((done) => {
    if (worker && !worker.killed) {
      worker.kill();
    }
    // Give process time to die
    setTimeout(done, 100);
  });

  test('worker process spawns and initializes', async () => {
    worker = spawnWorker();
    const resp = await initializeWorker(worker);
    expect(resp.jsonrpc).toBe('2.0');
    expect(resp.id).toBe(1);
    expect(resp.error).toBeUndefined();
    expect(resp.result).toBeDefined();
    expect(resp.result.capabilities.browserFlow).toBe(true);
    expect(resp.result.capabilities.apiRequest).toBe(true);
    expect(resp.result.capabilities.authBootstrap).toBe(true);
    expect(resp.result.capabilities.pageSnapshot).toBe(true);
    expect(resp.result.browserVersion).toBeDefined();
  });

  test('malformed JSON-RPC request returns parse error', async () => {
    worker = spawnWorker();
    // Send garbage
    worker.stdin!.write('this is not json\n');
    const resp = await readResponse(worker);
    expect(resp.jsonrpc).toBe('2.0');
    expect(resp.error).toBeDefined();
    expect(resp.error!.code).toBe(-32700); // Parse error
  });

  test('invalid JSON-RPC version returns error', async () => {
    worker = spawnWorker();
    const msg = JSON.stringify({ jsonrpc: '1.0', id: 1, method: 'ping' });
    worker.stdin!.write(msg + '\n');
    const resp = await readResponse(worker);
    expect(resp.error).toBeDefined();
    expect(resp.error!.code).toBe(-32600); // Invalid request
  });

  test('ping/pong works', async () => {
    worker = spawnWorker();
    await initializeWorker(worker);
    sendRequest(worker, 2, 'ping');
    const resp = await readResponse(worker);
    expect(resp.id).toBe(2);
    expect(resp.result).toBeDefined();
    expect(resp.result.pong).toBe(true);
  });

  test('method not found returns error', async () => {
    worker = spawnWorker();
    sendRequest(worker, 1, 'nonexistentMethod');
    const resp = await readResponse(worker);
    expect(resp.error).toBeDefined();
    expect(resp.error!.code).toBe(-32601); // Method not found
  });

  test('cancel works when no active task', async () => {
    worker = spawnWorker();
    await initializeWorker(worker);
    sendRequest(worker, 2, 'cancel');
    const resp = await readResponse(worker);
    expect(resp.id).toBe(2);
    expect(resp.result).toBeDefined();
    expect(resp.result.cancelled).toBe(true);
  });

  test('shutdown works', async () => {
    worker = spawnWorker();
    await initializeWorker(worker);
    sendRequest(worker, 2, 'shutdown');
    const resp = await readResponse(worker);
    expect(resp.id).toBe(2);
    expect(resp.result).toBeDefined();
    expect(resp.result.success).toBe(true);
  });

  test('method requiring initialization returns error before init', async () => {
    worker = spawnWorker();
    sendRequest(worker, 1, 'executeBrowserFlow', {
      taskId: 'test', entryUrl: 'http://localhost:3000',
    });
    const resp = await readResponse(worker);
    expect(resp.error).toBeDefined();
    expect(resp.error!.code).toBe(-32003); // Not initialized
  });
});
