// Spec §10.1: stdio JSON-RPC 2.0 server loop
// Newline-delimited JSON, UTF-8, strictly serial task execution

import * as readline from 'readline';
import {
  JsonRpcRequest,
  JsonRpcResponse,
  JsonRpcError,
  ErrorCodes,
} from './types';

export type MethodHandler = (params: Record<string, unknown>) => Promise<unknown>;

export class JsonRpcServer {
  private handlers: Map<string, MethodHandler> = new Map();
  private running = false;
  private rl: readline.Interface | null = null;

  registerMethod(method: string, handler: MethodHandler): void {
    this.handlers.set(method, handler);
  }

  // Spec §10.1: Send a JSON-RPC notification (no id) to the orchestrator via stdout
  sendNotification(method: string, params?: Record<string, unknown>): void {
    const notification = {
      jsonrpc: '2.0' as const,
      method,
      params,
    };
    this.writeLine(JSON.stringify(notification));
  }

  // Spec §10.1: Start reading newline-delimited JSON from stdin
  start(): void {
    this.running = true;
    this.rl = readline.createInterface({
      input: process.stdin,
      terminal: false,
    });

    this.rl.on('line', (line: string) => {
      this.handleLine(line);
    });

    this.rl.on('close', () => {
      this.running = false;
      process.exit(0);
    });

    // Log to stderr for diagnostics (Spec §10.1: stderr captured as diagnostic log)
    process.stderr.write('[worker] JSON-RPC server started\n');
  }

  stop(): void {
    this.running = false;
    if (this.rl) {
      this.rl.close();
      this.rl = null;
    }
  }

  isRunning(): boolean {
    return this.running;
  }

  private async handleLine(line: string): Promise<void> {
    const trimmed = line.trim();
    if (trimmed.length === 0) return;

    let parsed: unknown;
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      this.sendError(null, ErrorCodes.PARSE_ERROR, 'Parse error');
      return;
    }

    const msg = parsed as Record<string, unknown>;

    // Validate JSON-RPC 2.0
    if (msg.jsonrpc !== '2.0') {
      this.sendError(
        (msg.id as number | string | null) ?? null,
        ErrorCodes.INVALID_REQUEST,
        'Invalid JSON-RPC version',
      );
      return;
    }

    // If no id, it's a notification from orchestrator — currently we don't handle inbound notifications
    if (msg.id === undefined || msg.id === null) {
      return;
    }

    const id = msg.id as number | string;
    const method = msg.method as string;

    if (!method || typeof method !== 'string') {
      this.sendError(id, ErrorCodes.INVALID_REQUEST, 'Missing method');
      return;
    }

    const handler = this.handlers.get(method);
    if (!handler) {
      this.sendError(id, ErrorCodes.METHOD_NOT_FOUND, `Method not found: ${method}`);
      return;
    }

    try {
      const params = (msg.params as Record<string, unknown>) ?? {};
      const result = await handler(params);
      this.sendResult(id, result);
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      const errorCode = (err as { code?: number }).code ?? ErrorCodes.INTERNAL_ERROR;
      this.sendError(id, errorCode, errorMessage);
    }
  }

  private sendResult(id: number | string, result: unknown): void {
    const response: JsonRpcResponse = {
      jsonrpc: '2.0',
      id,
      result,
    };
    this.writeLine(JSON.stringify(response));
  }

  private sendError(id: number | string | null, code: number, message: string, data?: unknown): void {
    const error: JsonRpcError = { code, message };
    if (data !== undefined) error.data = data;
    const response: JsonRpcResponse = {
      jsonrpc: '2.0',
      id,
      error,
    };
    this.writeLine(JSON.stringify(response));
  }

  private writeLine(json: string): void {
    process.stdout.write(json + '\n');
  }
}
