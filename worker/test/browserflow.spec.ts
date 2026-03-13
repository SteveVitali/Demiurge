// Phase 6: Browser flow tests — executeBrowserFlow with real Playwright

import { ChildProcess, spawn } from 'child_process';
import * as path from 'path';
import * as readline from 'readline';
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';

const WORKER_SCRIPT = path.join(__dirname, '..', 'dist', 'index.js');

interface RpcResponse {
  jsonrpc: string;
  id: number | string | null;
  result?: any;
  error?: { code: number; message: string; data?: any };
}

function spawnWorker(): ChildProcess {
  return spawn('node', [WORKER_SCRIPT], { stdio: ['pipe', 'pipe', 'pipe'] });
}

function sendRequest(worker: ChildProcess, id: number, method: string, params?: any): void {
  const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params: params || {} });
  worker.stdin!.write(msg + '\n');
}

function readResponse(worker: ChildProcess, timeoutMs = 30000): Promise<RpcResponse> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Response timeout')), timeoutMs);
    const rl = readline.createInterface({ input: worker.stdout!, terminal: false });
    const handler = (line: string) => {
      try {
        const parsed = JSON.parse(line);
        // Skip notifications (no id), only resolve on responses
        if (parsed.id !== undefined && parsed.id !== null) {
          clearTimeout(timer);
          rl.removeListener('line', handler);
          rl.close();
          resolve(parsed);
        }
      } catch { /* skip non-JSON lines */ }
    };
    rl.on('line', handler);
  });
}

// Simple HTTP server for testing browser flows
function createTestServer(port: number): http.Server {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<!DOCTYPE html>
<html><head><title>Test Page</title></head>
<body>
  <h1 id="title">Hello Demiurge</h1>
  <button id="btn" onclick="document.getElementById('result').style.display='block'">Click Me</button>
  <div id="result" style="display:none">Success!</div>
  <form id="login-form">
    <input name="username" type="text" />
    <input name="password" type="password" />
    <button type="submit">Log in</button>
  </form>
</body></html>`);
  });
  return server;
}

describe('Browser Flow', () => {
  let worker: ChildProcess;
  let server: http.Server;
  let artifactRoot: string;
  let serverPort: number;

  beforeAll((done) => {
    server = createTestServer(0);
    server.listen(0, () => {
      serverPort = (server.address() as any).port;
      done();
    });
  });

  afterAll((done) => {
    server.close(done);
  });

  beforeEach(() => {
    artifactRoot = path.join(os.tmpdir(), 'test-artifacts-' + Date.now());
    fs.mkdirSync(artifactRoot, { recursive: true });
  });

  afterEach((done) => {
    if (worker && !worker.killed) worker.kill();
    // Cleanup artifact dir
    try { fs.rmSync(artifactRoot, { recursive: true, force: true }); } catch {}
    setTimeout(done, 200);
  });

  async function initWorker(): Promise<void> {
    worker = spawnWorker();
    sendRequest(worker, 1, 'initialize', {
      artifactRoot, worktreePath: os.tmpdir(), runId: 'test-run',
    });
    const resp = await readResponse(worker);
    expect(resp.result).toBeDefined();
  }

  test('executeBrowserFlow succeeds against local fixture app', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-1',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [
        { assertionType: 'elementVisible', selector: { strategy: 'css', value: '#title' }, description: 'Title visible' },
      ],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    expect(resp.id).toBe(2);
    expect(resp.result).toBeDefined();
    expect(resp.result.status).toBe('pass');
    expect(resp.result.durationMs).toBeGreaterThanOrEqual(0);
  });

  test('screenshot artifact is written', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-screenshot',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    expect(resp.result.artifacts).toBeDefined();
    const screenshots = resp.result.artifacts.filter((a: any) => a.artifactType === 'Screenshot');
    expect(screenshots.length).toBeGreaterThan(0);
    // Verify file exists
    const screenshotPath = path.join(artifactRoot, screenshots[0].relativePath);
    expect(fs.existsSync(screenshotPath)).toBe(true);
    expect(screenshots[0].checksumSha256).toBeDefined();
    expect(screenshots[0].sizeBytes).toBeGreaterThan(0);
  });

  test('console capture works', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-console',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    const consoleLogs = resp.result.artifacts.filter((a: any) => a.artifactType === 'ConsoleLog');
    expect(consoleLogs.length).toBeGreaterThan(0);
    const consolePath = path.join(artifactRoot, consoleLogs[0].relativePath);
    expect(fs.existsSync(consolePath)).toBe(true);
  });

  test('network summary capture works', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-network',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    const networkSummaries = resp.result.artifacts.filter((a: any) => a.artifactType === 'NetworkSummary');
    expect(networkSummaries.length).toBeGreaterThan(0);
  });

  test('DOM snapshot capture works', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-dom',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    const domSnapshots = resp.result.artifacts.filter((a: any) => a.artifactType === 'DomSnapshot');
    expect(domSnapshots.length).toBeGreaterThan(0);
    const domPath = path.join(artifactRoot, domSnapshots[0].relativePath);
    expect(fs.existsSync(domPath)).toBe(true);
    const content = fs.readFileSync(domPath, 'utf-8');
    expect(content).toContain('Hello Demiurge');
  });

  test('accessibility snapshot capture works', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-a11y',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    const a11ySnapshots = resp.result.artifacts.filter((a: any) => a.artifactType === 'AccessibilitySnapshot');
    expect(a11ySnapshots.length).toBeGreaterThan(0);
  });

  test('trace artifact is written', async () => {
    await initWorker();
    sendRequest(worker, 2, 'executeBrowserFlow', {
      taskId: 'flow-trace',
      entryUrl: `http://localhost:${serverPort}`,
      actions: [],
      assertions: [],
      artifactPlan: [],
    });
    const resp = await readResponse(worker);
    const traces = resp.result.artifacts.filter((a: any) => a.artifactType === 'BrowserTrace');
    expect(traces.length).toBeGreaterThan(0);
  });
});
