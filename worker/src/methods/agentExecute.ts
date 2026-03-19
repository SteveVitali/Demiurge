// Design §5: Agent SDK query() wrapper.
// Implements the `agent/execute` JSON-RPC method that the Scala orchestrator invokes.
// Wraps the Claude Agent SDK's query() function with Demiurge-specific configuration:
// MCP tools, permissions, hooks, and timeout control.

import { JsonRpcServer } from '../rpc/server';
import { createDemiurgeTools } from './demiurgeMcpTools';

// Design §5.2: AgentExecuteParams — matches the JSON-RPC params from Scala
export interface AgentExecuteParams {
  runId: string;
  systemPrompt: string;
  userPrompt: string;
  worktreePath: string;
  repoRoot: string;
  serviceIds: string[];
  agentConfig: {
    model?: string;
    maxTurns?: number;
    timeoutMs: number;
    enableMcpTools: boolean;
    sessionId?: string;
    resume?: boolean;
    pathToClaudeCodeExecutable?: string;
  };
}

// Design §5.2: AgentExecuteResult — returned to Scala via JSON-RPC
export interface AgentExecuteResult {
  sessionId: string;
  success: boolean;
  resultText: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  numTurns: number;
  durationMs: number;
  isInterrupted: boolean;
  isBudgetExceeded: boolean;
  toolUseLog: ToolUseEntry[];
}

interface ToolUseEntry {
  toolName: string;
  timestamp: string;
  inputSummary: string;
}

/**
 * Handle the `agent/execute` JSON-RPC method.
 * This is the main entry point for the Scala orchestrator to invoke the Claude Agent SDK.
 */
export async function handleAgentExecute(
  params: Record<string, unknown>,
  server: JsonRpcServer,
): Promise<AgentExecuteResult> {
  const p = params as unknown as AgentExecuteParams;
  const startMs = Date.now();
  const toolUseLog: ToolUseEntry[] = [];

  // Dynamically import the Agent SDK (it's an ESM module)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let sdkQuery: any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let sdkTool: any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let sdkCreateMcpServer: any;

  try {
    const sdk = await import('@anthropic-ai/claude-code');
    sdkQuery = sdk.query;
    sdkTool = sdk.tool;
    sdkCreateMcpServer = sdk.createSdkMcpServer;
  } catch (err) {
    throw new Error(
      `Failed to import @anthropic-ai/claude-code: ${err instanceof Error ? err.message : String(err)}. ` +
      'Install with: npm install @anthropic-ai/claude-code (in the worker directory)',
    );
  }

  // Design §6.2: Build in-process MCP tools
  const { tools: demiurgeTools, callbackManager } = createDemiurgeTools(server, p.runId);

  // Register callback response handler for this session's callback manager.
  server.registerInboundNotificationHandler('demiurge.callback.response', (cbParams) => {
    callbackManager.handleCallbackResponse(cbParams);
  });

  // Design §6.2: Convert tool definitions to SDK tool() format and wrap in an MCP server.
  // SDK tool() signature: tool(name, description, inputSchema, handler)
  // SDK createSdkMcpServer() wraps tools into an McpSdkServerConfig for query().
  const mcpServerConfig = p.agentConfig.enableMcpTools
    ? sdkCreateMcpServer({
        name: 'demiurge',
        version: '1.0.0',
        tools: Object.values(demiurgeTools).map((t) =>
          sdkTool(t.name, t.description, t.inputSchema, t.handler),
        ),
      })
    : null;

  // Design §5.3: Build query options (matches SDK Options type)
  const queryOptions: Record<string, unknown> = {
    customSystemPrompt: p.systemPrompt,
    cwd: p.worktreePath,
    permissionMode: 'bypassPermissions' as const,
    maxTurns: p.agentConfig.maxTurns ?? 50,
    ...(p.agentConfig.model && { model: p.agentConfig.model }),
    ...(p.agentConfig.resume && p.agentConfig.sessionId && { resume: p.agentConfig.sessionId }),
    ...(mcpServerConfig && { mcpServers: { demiurge: mcpServerConfig } }),
    ...(p.agentConfig.pathToClaudeCodeExecutable && { pathToClaudeCodeExecutable: p.agentConfig.pathToClaudeCodeExecutable }),
  };

  // Design §5.3: Timeout handling via AbortController
  const abortController = new AbortController();
  let isInterrupted = false;
  const timeoutHandle = setTimeout(() => {
    isInterrupted = true;
    abortController.abort();
  }, p.agentConfig.timeoutMs);

  let sessionId = '';
  let resultText = '';
  let inputTokens = 0;
  let outputTokens = 0;
  let costUsd = 0;
  let numTurns = 0;
  let success = false;
  let isBudgetExceeded = false;

  try {
    // Design §5.3: Invoke the Agent SDK — query({ prompt, options })
    const conversation = sdkQuery({
      prompt: p.userPrompt,
      options: {
        ...queryOptions,
        abortController,
      },
    });

    // Stream messages from the agent
    for await (const message of conversation) {
      // Track tool use and text from assistant messages
      if (message.type === 'assistant' && message.message?.content) {
        for (const block of message.message.content) {
          if (block.type === 'tool_use') {
            const entry: ToolUseEntry = {
              toolName: block.name ?? 'unknown',
              timestamp: new Date().toISOString(),
              inputSummary: JSON.stringify(block.input ?? {}).slice(0, 200),
            };
            toolUseLog.push(entry);

            // Design §5.3: Send tool use notification to Scala for real-time transcript
            server.sendNotification('agent/toolUse', {
              runId: p.runId,
              toolName: entry.toolName,
              timestamp: entry.timestamp,
              inputSummary: entry.inputSummary,
            });
          } else if (block.type === 'text' && block.text) {
            // Send text progress — first line only, truncated to 200 chars
            const firstLine = block.text.split('\n')[0].slice(0, 200);
            if (firstLine.trim()) {
              server.sendNotification('agent/progress', {
                runId: p.runId,
                text: firstLine,
                timestamp: new Date().toISOString(),
              });
            }
          }
        }
      }

      // Track tool results for progress logging
      if (message.type === 'tool_result') {
        const toolName = message.tool_name ?? '';
        const isError = message.is_error ?? false;
        server.sendNotification('agent/progress', {
          runId: p.runId,
          text: `${toolName} → ${isError ? 'error' : 'ok'}`,
          timestamp: new Date().toISOString(),
        });
      }

      // Capture result message (SDKResultMessage)
      if (message.type === 'result') {
        sessionId = message.session_id ?? '';
        numTurns = message.num_turns ?? 0;
        costUsd = message.total_cost_usd ?? 0;

        // Usage stats from the top-level usage field
        if (message.usage) {
          inputTokens = message.usage.input_tokens ?? 0;
          outputTokens = message.usage.output_tokens ?? 0;
        }

        if (message.subtype === 'success') {
          success = !message.is_error;
          resultText = message.result ?? '';
        } else if (message.subtype === 'error_max_turns') {
          success = false;
          resultText = 'Agent exhausted maximum turns';
        } else if (message.subtype === 'error_during_execution') {
          success = false;
          resultText = 'Agent encountered an error during execution';
        }
      }
    }
  } catch (err) {
    // AbortController.abort() may throw
    if (isInterrupted) {
      resultText = 'Agent session interrupted by timeout';
    } else {
      throw err;
    }
  } finally {
    clearTimeout(timeoutHandle);
    callbackManager.dispose();
  }

  const durationMs = Date.now() - startMs;

  return {
    sessionId,
    success,
    resultText,
    inputTokens,
    outputTokens,
    costUsd,
    numTurns,
    durationMs,
    isInterrupted,
    isBudgetExceeded,
    toolUseLog,
  };
}
