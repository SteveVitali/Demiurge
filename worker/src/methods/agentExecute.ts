// Design §5: Agent SDK query() wrapper.
// Implements the `agent/execute` JSON-RPC method that the Scala orchestrator invokes.
// Wraps the Claude Agent SDK's query() function with Demiurge-specific configuration:
// MCP tools, permissions, hooks, and timeout control.

import { JsonRpcServer } from '../rpc/server';
import { createDemiurgeTools } from './demiurgeMcpTools';
import { BrowserArtifactCollector } from '../artifacts/browserArtifactCollector';

// Design §5.2: AgentExecuteParams — matches the JSON-RPC params from Scala
export interface AgentExecuteParams {
  runId: string;
  mode?: 'repair' | 'verification';  // Design: Agentic Browser Verification
  systemPrompt: string;
  userPrompt: string;
  worktreePath: string;
  repoRoot: string;
  serviceIds: string[];
  beforeScreenshots?: string[];       // paths to before-implementation screenshots
  agentConfig: {
    model?: string;
    maxTurns?: number;
    maxBudgetUsd?: number;
    timeoutMs: number;
    enableMcpTools: boolean;
    enableBrowserTools?: boolean;     // Playwright MCP browser tools
    headedBrowser?: boolean;          // launch browser in headed mode
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
  verificationVerdict?: VerificationVerdict;  // parsed from agent output in verification mode
}

// Design: Agentic Browser Verification — structured verdict from verification agent
interface VerificationVerdict {
  verdict: 'PASS' | 'FAIL' | 'TASTE_ISSUE';
  confidence: number;
  featureSatisfied: boolean;
  observations: Array<{
    aspect: string;
    status: string;
    detail: string;
    screenshotRef?: string;
  }>;
  tasteIssues: Array<{
    severity: string;
    issue: string;
    element?: string;
    screenshotRef?: string;
  }>;
  screenshots: Array<{
    ref: string;
    description: string;
    phase: string;
  }>;
  summary: string;
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

  // Design: Agentic Browser Verification — build mcpServers map dynamically
  const mcpServers: Record<string, unknown> = {};
  if (mcpServerConfig) {
    mcpServers['demiurge'] = mcpServerConfig;
  }

  // Design: Agentic Browser Verification — add Playwright MCP server when browser tools enabled
  if (p.agentConfig.enableBrowserTools) {
    const headed = p.agentConfig.headedBrowser ?? false;
    mcpServers['playwright'] = {
      command: 'npx',
      args: [
        '@playwright/mcp@latest',
        ...(headed ? ['--headed'] : []),
      ],
    };
  }

  // Design §5.3: Build query options (matches SDK Options type)
  const queryOptions: Record<string, unknown> = {
    customSystemPrompt: p.systemPrompt,
    cwd: p.worktreePath,
    permissionMode: 'bypassPermissions' as const,
    maxTurns: p.agentConfig.maxTurns ?? 50,
    ...(p.agentConfig.model && { model: p.agentConfig.model }),
    ...(p.agentConfig.resume && p.agentConfig.sessionId && { resume: p.agentConfig.sessionId }),
    ...(Object.keys(mcpServers).length > 0 && { mcpServers }),
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
  let lastAssistantText = '';  // track last assistant text for verdict extraction
  const allMessages: unknown[] = [];  // collect all messages for transcript artifact

  // Design: Agentic Browser Verification — artifact collector for verification mode
  const artifactCollector = (p.mode === 'verification' && process.env.DEMIURGE_ARTIFACT_ROOT)
    ? new BrowserArtifactCollector({
        artifactRoot: process.env.DEMIURGE_ARTIFACT_ROOT,
        runId: p.runId,
        verifierId: p.runId,  // use runId as verifierId for browser verification
      })
    : null;

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
      allMessages.push(message);
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
            lastAssistantText = block.text;
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

  // Design: Agentic Browser Verification — parse verdict from agent output in verification mode
  let verificationVerdict: VerificationVerdict | undefined;
  if (p.mode === 'verification') {
    verificationVerdict = parseVerificationVerdict(resultText || lastAssistantText);

    // Save artifacts if collector is available
    if (artifactCollector) {
      try {
        if (verificationVerdict) {
          artifactCollector.saveVerdict(verificationVerdict as unknown as Record<string, unknown>);
        }
        artifactCollector.saveTranscript(allMessages);
      } catch {
        // best-effort artifact collection
      }
    }
  }

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
    ...(verificationVerdict && { verificationVerdict }),
  };
}

/**
 * Parse a VerificationVerdict JSON block from the agent's output text.
 * Looks for ```json ... ``` fenced blocks or raw JSON containing "verdict".
 */
export function parseVerificationVerdict(text: string): VerificationVerdict | undefined {
  if (!text) return undefined;

  // Try fenced JSON block first
  const fencedMatch = text.match(/```json\s*\n([\s\S]*?)\n\s*```/);
  let jsonStr = fencedMatch ? fencedMatch[1] : '';

  // Fallback: find raw JSON containing "verdict" key using balanced-brace extraction
  if (!jsonStr) {
    jsonStr = extractBalancedJsonContaining(text, '"verdict"');
  }

  if (!jsonStr) return undefined;

  try {
    const parsed = JSON.parse(jsonStr);
    if (parsed.verdict && ['PASS', 'FAIL', 'TASTE_ISSUE'].includes(parsed.verdict)) {
      return {
        verdict: parsed.verdict,
        confidence: typeof parsed.confidence === 'number' ? parsed.confidence : 0.5,
        featureSatisfied: typeof parsed.featureSatisfied === 'boolean' ? parsed.featureSatisfied : false,
        observations: Array.isArray(parsed.observations) ? parsed.observations : [],
        tasteIssues: Array.isArray(parsed.tasteIssues) ? parsed.tasteIssues : [],
        screenshots: Array.isArray(parsed.screenshots) ? parsed.screenshots : [],
        summary: typeof parsed.summary === 'string' ? parsed.summary : '',
      };
    }
  } catch {
    // JSON parse failed — not a valid verdict
  }

  return undefined;
}

/**
 * Extract a balanced JSON object from text that contains the given keyword.
 * Uses brace counting to handle nested objects correctly.
 */
function extractBalancedJsonContaining(text: string, keyword: string): string {
  const keyIndex = text.indexOf(keyword);
  if (keyIndex === -1) return '';

  // Find the last '{' before the keyword
  let startIndex = -1;
  for (let i = keyIndex; i >= 0; i--) {
    if (text[i] === '{') { startIndex = i; break; }
  }
  if (startIndex === -1) return '';

  // Count balanced braces to find the matching '}'
  let depth = 0;
  let inString = false;
  let escape = false;
  for (let i = startIndex; i < text.length; i++) {
    const ch = text[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"') { inString = !inString; continue; }
    if (inString) continue;
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return text.substring(startIndex, i + 1);
    }
  }
  return '';
}
