// Spec §10.1: Browser worker entry point
// stdio JSON-RPC 2.0 server, serial task execution, Chromium via Playwright

import { JsonRpcServer } from './rpc/server';
import { createInitialState, handleInitialize, WorkerState } from './methods/initialize';
import { handleShutdown } from './methods/shutdown';
import { handleCancel } from './methods/cancel';
import { handleExecuteBrowserFlow } from './methods/executeBrowserFlow';
import { handleExecuteAuthBootstrap } from './methods/executeAuthBootstrap';
import { handleExecuteApiRequest } from './methods/executeApiRequest';
import { handleCapturePageSnapshot } from './methods/capturePageSnapshot';

const state: WorkerState = createInitialState();
const server = new JsonRpcServer();

// Spec §10.2: Register all JSON-RPC methods
server.registerMethod('initialize', (params) => handleInitialize(state, params));
server.registerMethod('shutdown', () => handleShutdown(state));
server.registerMethod('cancel', (params) => handleCancel(state, params));
server.registerMethod('executeBrowserFlow', (params) => handleExecuteBrowserFlow(state, params, server));
server.registerMethod('executeAuthBootstrap', (params) => handleExecuteAuthBootstrap(state, params, server));
server.registerMethod('executeApiRequest', (params) => handleExecuteApiRequest(state, params));
server.registerMethod('capturePageSnapshot', (params) => handleCapturePageSnapshot(state, params));

// Spec §10.2: Ping/pong for health checking
server.registerMethod('ping', async () => ({ pong: true, timestamp: new Date().toISOString() }));

// Start the server
server.start();
process.stderr.write('[worker] Demiurge browser worker started\n');
