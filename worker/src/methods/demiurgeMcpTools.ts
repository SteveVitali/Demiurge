// Design §6: In-process Demiurge MCP server.
// Defines MCP tools that the Claude Agent SDK can invoke during repair/build sessions.
// Tool handlers call back to the Scala orchestrator via JSON-RPC notifications,
// wait for responses, and return results to the agent.

import { z } from 'zod';
import { JsonRpcServer } from '../rpc/server';

// Pending RPC callback state: the MCP tool handler sends a JSON-RPC notification
// to Scala and waits for a response notification back.
interface PendingCallback {
  resolve: (result: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

/**
 * Manages the callback lifecycle for a single agent session.
 * Encapsulates mutable state (counter + pending map) per session
 * rather than using module-global state.
 */
export class DemiurgeCallbackManager {
  private pendingCallbacks = new Map<string, PendingCallback>();
  private callbackIdCounter = 0;

  /**
   * Send a JSON-RPC callback to Scala and wait for the response.
   * The orchestrator receives a notification like `demiurge.verifyRequirements`
   * and responds with a `demiurge.callback.response` notification.
   */
  callScala(
    server: JsonRpcServer,
    method: string,
    params: Record<string, unknown>,
    timeoutMs: number = 120000,
  ): Promise<unknown> {
    const callbackId = `cb_${++this.callbackIdCounter}_${Date.now()}`;

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingCallbacks.delete(callbackId);
        reject(new Error(`Callback to Scala timed out after ${timeoutMs}ms: ${method}`));
      }, timeoutMs);

      this.pendingCallbacks.set(callbackId, { resolve, reject, timer });

      server.sendNotification(method, {
        ...params,
        _callbackId: callbackId,
      });
    });
  }

  /**
   * Handle a callback response from Scala.
   * Called when the worker receives a `demiurge.callback.response` notification.
   */
  handleCallbackResponse(params: Record<string, unknown>): void {
    const callbackId = params._callbackId as string;
    if (!callbackId) return;

    const pending = this.pendingCallbacks.get(callbackId);
    if (!pending) return;

    this.pendingCallbacks.delete(callbackId);
    clearTimeout(pending.timer);

    if (params._error) {
      pending.reject(new Error(params._error as string));
    } else {
      pending.resolve(params._result ?? params);
    }
  }

  /** Clean up any pending callbacks (e.g. on session end). */
  dispose(): void {
    for (const [, pending] of this.pendingCallbacks) {
      clearTimeout(pending.timer);
      pending.reject(new Error('Session ended'));
    }
    this.pendingCallbacks.clear();
  }
}

/**
 * Tool definition factory for Demiurge MCP tools.
 * Returns tool definitions compatible with the Claude Agent SDK's
 * createSdkMcpServer() / tool() API.
 *
 * Each tool is an object with { name, description, inputSchema, handler }.
 */
// CallToolResult shape from MCP SDK — returned by tool handlers to the Agent SDK.
interface McpTextContent {
  type: 'text';
  text: string;
}
interface McpCallToolResult {
  content: McpTextContent[];
  isError?: boolean;
}

// ZodRawShape: the raw shape object passed to z.object(), e.g. { serviceId: z.string() }
// This matches the SDK's tool() inputSchema parameter type.
type ZodRawShape = Record<string, z.ZodTypeAny>;

export interface DemiurgeToolDef {
  name: string;
  description: string;
  inputSchema: ZodRawShape;
  handler: (args: Record<string, unknown>, extra: unknown) => Promise<McpCallToolResult>;
}

/** Helper: wrap a JSON result string as a successful McpCallToolResult. */
function textResult(json: string): McpCallToolResult {
  return { content: [{ type: 'text', text: json }] };
}

/** Helper: wrap an error string as a failed McpCallToolResult. */
function errorResult(msg: string): McpCallToolResult {
  return { content: [{ type: 'text', text: msg }], isError: true };
}

export function createDemiurgeTools(
  server: JsonRpcServer,
  runId: string,
): { tools: Record<string, DemiurgeToolDef>; callbackManager: DemiurgeCallbackManager } {
  const mgr = new DemiurgeCallbackManager();

  const tools: Record<string, DemiurgeToolDef> = {
    verify_requirements: {
      name: 'verify_requirements',
      description:
        'Run the Demiurge verification suite against all requirements. ' +
        'Returns pass/fail status for each requirement with failure details. ' +
        'IMPORTANT: Always restart affected services before calling this tool.',
      inputSchema: {
        requirementIds: z
          .array(z.string())
          .optional()
          .describe('Optional list of specific requirement IDs to verify. If omitted, verifies all.'),
      },
      handler: async (args: Record<string, unknown>) => {
        try {
          const result = await mgr.callScala(server, 'demiurge.verifyRequirements', {
            runId,
            requirementIds: (args.requirementIds as string[]) ?? [],
          });
          return textResult(JSON.stringify(result));
        } catch (e) {
          return errorResult(`Verification failed: ${e instanceof Error ? e.message : String(e)}`);
        }
      },
    },

    get_service_logs: {
      name: 'get_service_logs',
      description:
        'Get recent log output from a running service. ' +
        'Useful for diagnosing crashes, errors, and unexpected behavior. ' +
        'Returns the last N lines of stdout/stderr.',
      inputSchema: {
        serviceId: z.string().describe('ID of the service to get logs from'),
        lines: z.number().optional().describe('Number of log lines to return (default: 200)'),
      },
      handler: async (args: Record<string, unknown>) => {
        try {
          const result = await mgr.callScala(server, 'demiurge.getServiceLogs', {
            runId,
            serviceId: args.serviceId as string,
            lines: (args.lines as number) ?? 200,
          });
          return textResult(JSON.stringify(result));
        } catch (e) {
          return errorResult(`Failed to get logs: ${e instanceof Error ? e.message : String(e)}`);
        }
      },
    },

    restart_service: {
      name: 'restart_service',
      description:
        'Restart a running service to pick up code changes. ' +
        'Call this after editing source files before running verification. ' +
        'Returns the new service health status.',
      inputSchema: {
        serviceId: z.string().describe('ID of the service to restart'),
      },
      handler: async (args: Record<string, unknown>) => {
        try {
          const result = await mgr.callScala(server, 'demiurge.restartService', {
            runId,
            serviceId: args.serviceId as string,
          });
          return textResult(JSON.stringify(result));
        } catch (e) {
          return errorResult(`Restart failed: ${e instanceof Error ? e.message : String(e)}`);
        }
      },
    },

    get_requirement_details: {
      name: 'get_requirement_details',
      description:
        'Get full details of a specific requirement including its verifiers, ' +
        'category, priority, and human description.',
      inputSchema: {
        requirementId: z.string().describe('The requirement ID to look up'),
      },
      handler: async (args: Record<string, unknown>) => {
        try {
          const result = await mgr.callScala(server, 'demiurge.getRequirementDetails', {
            runId,
            requirementId: args.requirementId as string,
          });
          return textResult(JSON.stringify(result));
        } catch (e) {
          return errorResult(`Failed to get details: ${e instanceof Error ? e.message : String(e)}`);
        }
      },
    },

    check_service_health: {
      name: 'check_service_health',
      description:
        'Check health status of all running services. ' +
        'Returns a list of services with their current status (healthy/unhealthy/stopped).',
      inputSchema: {},
      handler: async () => {
        try {
          const result = await mgr.callScala(server, 'demiurge.checkServiceHealth', {
            runId,
          });
          return textResult(JSON.stringify(result));
        } catch (e) {
          return errorResult(`Health check failed: ${e instanceof Error ? e.message : String(e)}`);
        }
      },
    },
  };

  return { tools, callbackManager: mgr };
}
