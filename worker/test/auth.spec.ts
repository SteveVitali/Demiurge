// Phase 6: Auth bootstrap tests

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
        if (parsed.id !== undefined && parsed.id !== null) {
          clearTimeout(timer);
          rl.removeListener('line', handler);
          rl.close();
          resolve(parsed);
        }
      } catch { /* skip */ }
    };
    rl.on('line', handler);
  });
}

// Login page server
function createLoginServer(port: number): http.Server {
  const server = http.createServer((req, res) => {
    if (req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!DOCTYPE html>
<html><head><title>Login</title></head>
<body>
  <form action="/login" method="post">
    <input name="username" type="text" />
    <input name="password" type="password" />
    <button type="submit">Log in</button>
  </form>
</body></html>`);
    } else if (req.method === 'POST' && req.url === '/login') {
      res.writeHead(302, { 'Location': '/dashboard', 'Set-Cookie': 'session=test123; Path=/' });
      res.end();
    } else if (req.url === '/dashboard') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<html><body><h1>Dashboard</h1></body></html>');
    } else if (req.method === 'POST' && req.url === '/api/token') {
      let body = '';
      req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ token: 'test-token-123', access_token: 'test-access-token' }));
      });
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  return server;
}

describe('Auth Bootstrap', () => {
  let worker: ChildProcess;
  let server: http.Server;
  let artifactRoot: string;
  let serverPort: number;

  beforeAll((done) => {
    server = createLoginServer(0);
    server.listen(0, () => {
      serverPort = (server.address() as any).port;
      done();
    });
  });

  afterAll((done) => {
    server.close(done);
  });

  beforeEach(() => {
    artifactRoot = path.join(os.tmpdir(), 'test-auth-artifacts-' + Date.now());
    fs.mkdirSync(artifactRoot, { recursive: true });
  });

  afterEach((done) => {
    if (worker && !worker.killed) worker.kill();
    try { fs.rmSync(artifactRoot, { recursive: true, force: true }); } catch {}
    setTimeout(done, 200);
  });

  async function initWorker(): Promise<void> {
    worker = spawnWorker();
    sendRequest(worker, 1, 'initialize', {
      artifactRoot, worktreePath: os.tmpdir(), runId: 'test-auth-run',
    });
    const resp = await readResponse(worker);
    expect(resp.result).toBeDefined();
  }

  test('auth bootstrap writes storage state with BrowserFormLogin', async () => {
    await initWorker();
    const storageStatePath = path.join(artifactRoot, 'storage_state.json');
    sendRequest(worker, 2, 'executeAuthBootstrap', {
      taskId: 'auth-1',
      mode: 'BrowserFormLogin',
      loginUrl: `http://localhost:${serverPort}`,
      credentials: { username: 'testuser', password: 'testpass' },
      storageStateOutput: storageStatePath,
    });
    const resp = await readResponse(worker);
    expect(resp.id).toBe(2);
    expect(resp.result).toBeDefined();
    expect(resp.result.success).toBe(true);
    expect(resp.result.storageStatePath).toBe(storageStatePath);
    // Storage state file should exist
    expect(fs.existsSync(storageStatePath)).toBe(true);
    // Should have artifacts
    expect(resp.result.artifacts.length).toBeGreaterThan(0);
  });

  test('auth bootstrap with StaticTestToken writes storage state', async () => {
    await initWorker();
    const storageStatePath = path.join(artifactRoot, 'static_storage.json');
    sendRequest(worker, 2, 'executeAuthBootstrap', {
      taskId: 'auth-static',
      mode: 'StaticTestToken',
      credentials: { token: 'my-static-token-123' },
      storageStateOutput: storageStatePath,
    });
    const resp = await readResponse(worker);
    expect(resp.result.success).toBe(true);
    expect(fs.existsSync(storageStatePath)).toBe(true);
    const state = JSON.parse(fs.readFileSync(storageStatePath, 'utf-8'));
    expect(state.apiHeaders.Authorization).toBe('Bearer my-static-token-123');
  });

  test('auth bootstrap with DevBypassHeader writes headers', async () => {
    await initWorker();
    const storageStatePath = path.join(artifactRoot, 'bypass_storage.json');
    sendRequest(worker, 2, 'executeAuthBootstrap', {
      taskId: 'auth-bypass',
      mode: 'DevBypassHeader',
      credentials: { 'X-Dev-Bypass': 'true', 'X-User-Id': 'test-user' },
      storageStateOutput: storageStatePath,
    });
    const resp = await readResponse(worker);
    expect(resp.result.success).toBe(true);
    expect(fs.existsSync(storageStatePath)).toBe(true);
  });

  test('executeApiRequest works with storage state', async () => {
    await initWorker();
    // First create a storage state with headers
    const storageStatePath = path.join(artifactRoot, 'api_storage.json');
    fs.writeFileSync(storageStatePath, JSON.stringify({
      cookies: [], origins: [],
      apiHeaders: { Authorization: 'Bearer test-token' },
    }));

    sendRequest(worker, 2, 'executeApiRequest', {
      taskId: 'api-1',
      method: 'POST',
      url: `http://localhost:${serverPort}/api/token`,
      body: JSON.stringify({ username: 'test', password: 'test' }),
      storageStatePath,
    });
    const resp = await readResponse(worker);
    expect(resp.result).toBeDefined();
    expect(resp.result.status).toBe(200);
    expect(resp.result.durationMs).toBeGreaterThanOrEqual(0);
    expect(resp.result.artifacts.length).toBeGreaterThan(0);
    const body = JSON.parse(resp.result.body);
    expect(body.token).toBe('test-token-123');
  });
});
