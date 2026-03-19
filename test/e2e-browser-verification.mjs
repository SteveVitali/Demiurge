#!/usr/bin/env node
/**
 * E2E Test: Agentic Browser UI Verification
 * 
 * Spawns the Demiurge worker, sends a JSON-RPC agent/execute request
 * with mode=verification and Playwright browser tools enabled,
 * and observes the Claude Agent verifying the son-of-steve frontend.
 * 
 * Prerequisites:
 *   - son-of-steve server running on http://localhost:3006
 *   - ANTHROPIC_API_KEY set in environment
 *   - Claude CLI installed (which claude)
 */

import { spawn } from 'child_process';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { mkdirSync } from 'fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const WORKER_PATH = resolve(__dirname, '../worker/dist/index.js');
const ARTIFACT_ROOT = resolve(__dirname, '../.demiurge/e2e-test-artifacts');
const RUN_ID = `e2e-browser-${Date.now()}`;
const VERIFIER_ID = 'v-ui-dashboard';

mkdirSync(ARTIFACT_ROOT, { recursive: true });

// --- JSON-RPC helpers ---
let msgId = 0;
function jsonRpcRequest(method, params) {
  return JSON.stringify({ jsonrpc: '2.0', id: ++msgId, method, params });
}

function sendRequest(proc, method, params) {
  const req = jsonRpcRequest(method, params);
  console.log(`\n→ ${method} (id=${msgId})`);
  proc.stdin.write(req + '\n');
  return msgId;
}

function waitForResponse(proc, expectedId, timeoutMs = 300000) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error(`Timeout waiting for response id=${expectedId}`)), timeoutMs);

    function onData(chunk) {
      buffer += chunk.toString();
      const lines = buffer.split('\n');
      buffer = lines.pop(); // keep incomplete line
      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const msg = JSON.parse(line);
          if (msg.id === expectedId) {
            clearTimeout(timer);
            proc.stdout.removeListener('data', onData);
            resolve(msg);
          }
        } catch { /* not JSON, ignore */ }
      }
    }
    proc.stdout.on('data', onData);
  });
}

// --- Build prompts (mirrors BrowserVerificationPromptBuilder) ---
const systemPrompt = `You are a meticulous QA engineer and black-box tester. Your job is to verify
that a web application's UI works correctly by navigating it with browser tools.

# Feature to Verify

<feature_description>
Verify the web application's main dashboard UI renders correctly.
Navigate to http://localhost:3006 and verify: (1) the page loads without
errors, (2) the navigation bar is visible with tabs for Jobs, Workers,
Chat, GitHub, KB, Memory, Models, Research, and Routing, (3) clicking
on the Jobs tab shows a jobs list view, (4) the page is responsive and
visually well-structured with no broken layouts or missing elements.
</feature_description>

# Entry Point
Navigate to: http://localhost:3006

# Verification Protocol

1. **Initial State Capture**: Navigate to the entry URL. Take a screenshot with browser_screenshot and capture the accessibility tree with browser_snapshot.
2. **Systematic Feature Exploration**: Follow the feature description step by step. For each step, perform the action and verify the expected outcome. Take screenshots at key states.
3. **Visual Taste Assessment**: Note any visual quality issues (contrast, sizing, alignment, typography, spacing, color harmony, consistency, responsiveness, accessibility).
4. **Verdict**: After completing all checks, output a JSON verdict block.

## Output Format

After completing all verification steps, output your verdict as a JSON block:

\`\`\`json
{
  "verdict": "PASS | FAIL | TASTE_ISSUE",
  "confidence": 0.0-1.0,
  "featureSatisfied": true/false,
  "observations": [
    {"aspect": "...", "status": "pass|fail|warning", "detail": "...", "screenshotRef": "..."}
  ],
  "tasteIssues": [
    {"severity": "error|warning|info", "issue": "...", "element": "...", "screenshotRef": "..."}
  ],
  "summary": "Brief overall assessment"
}
\`\`\`

## Rules
- Do NOT try to access source code or file system — you are a black-box tester
- Take at least 3 screenshots during verification
- Use browser_snapshot to verify element presence and structure
- Test interactive elements (clicks, navigation)
- Be thorough but efficient`;

const userPrompt = 'Verify the feature as described in your system prompt. Begin by navigating to the entry URL and taking an initial screenshot.';

// --- Main ---
async function main() {
  console.log('=== E2E: Agentic Browser UI Verification ===');
  console.log(`Worker: ${WORKER_PATH}`);
  console.log(`Target: http://localhost:3006`);
  console.log(`Run ID: ${RUN_ID}`);
  console.log();

  // Verify server is up
  try {
    const resp = await fetch('http://localhost:3006/api/health');
    const data = await resp.json();
    console.log(`✓ Server health: ${JSON.stringify(data)}`);
  } catch (e) {
    console.error('✗ Server not reachable at http://localhost:3006 — start it first');
    process.exit(1);
  }

  // Spawn worker
  console.log('\n--- Spawning Demiurge worker ---');
  const worker = spawn('node', [WORKER_PATH], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, NODE_OPTIONS: '' },
  });

  worker.stderr.on('data', (d) => {
    const lines = d.toString().split('\n').filter(Boolean);
    for (const line of lines) {
      console.log(`  [worker:stderr] ${line}`);
    }
  });

  // Wait for worker startup
  await new Promise(r => setTimeout(r, 1000));

  // Step 1: Initialize
  console.log('\n--- Step 1: Initialize worker ---');
  const initId = sendRequest(worker, 'initialize', {
    artifactRoot: ARTIFACT_ROOT,
    worktreePath: '/Users/stevenvitali/Desktop/son-of-steve',
    runId: RUN_ID,
  });
  const initResp = await waitForResponse(worker, initId, 10000);
  if (initResp.error) {
    console.error('✗ Initialize failed:', initResp.error);
    worker.kill();
    process.exit(1);
  }
  console.log('✓ Worker initialized:', JSON.stringify(initResp.result).slice(0, 200));

  // Step 2: Agent/execute with verification mode
  console.log('\n--- Step 2: agent/execute (mode=verification, browser tools) ---');
  console.log('This will launch Claude Agent SDK with Playwright MCP...');
  console.log('(This may take 1-3 minutes as the agent navigates the browser)\n');

  const startTime = Date.now();
  const execId = sendRequest(worker, 'agent/execute', {
    runId: RUN_ID,
    mode: 'verification',
    systemPrompt,
    userPrompt,
    worktreePath: '/Users/stevenvitali/Desktop/son-of-steve',
    repoRoot: '/Users/stevenvitali/Desktop/son-of-steve',
    artifactRoot: ARTIFACT_ROOT,
    serviceIds: [],
    agentConfig: {
      model: 'claude-sonnet-4-20250514',
      maxTurns: 25,
      maxBudgetUsd: 2.0,
      timeoutMs: 180000,
      enableMcpTools: false,  // No Demiurge MCP tools for verification
      enableBrowserTools: true,
      headedBrowser: false,
      pathToClaudeCodeExecutable: '/opt/homebrew/bin/claude',
    },
  });

  const execResp = await waitForResponse(worker, execId, 300000);
  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

  if (execResp.error) {
    console.error(`\n✗ agent/execute failed (${elapsed}s):`, JSON.stringify(execResp.error, null, 2));
    worker.kill();
    process.exit(1);
  }

  const result = execResp.result;
  console.log(`\n${'='.repeat(60)}`);
  console.log(`BROWSER VERIFICATION RESULT (${elapsed}s)`);
  console.log(`${'='.repeat(60)}`);
  console.log(`Success: ${result.success}`);
  console.log(`Turns: ${result.numTurns}`);
  console.log(`Cost: $${result.costUsd?.toFixed(4) || '?'}`);
  console.log(`Tokens: ${result.inputTokens} in / ${result.outputTokens} out`);
  console.log(`Duration: ${(result.durationMs / 1000).toFixed(1)}s`);

  if (result.verificationVerdict) {
    const v = result.verificationVerdict;
    console.log(`\n--- Verdict ---`);
    console.log(`Verdict: ${v.verdict}`);
    console.log(`Confidence: ${v.confidence}`);
    console.log(`Feature Satisfied: ${v.featureSatisfied}`);
    console.log(`Summary: ${v.summary}`);
    if (v.observations?.length) {
      console.log(`\nObservations:`);
      for (const obs of v.observations) {
        console.log(`  [${obs.status}] ${obs.aspect}: ${obs.detail}`);
      }
    }
    if (v.tasteIssues?.length) {
      console.log(`\nTaste Issues:`);
      for (const ti of v.tasteIssues) {
        console.log(`  [${ti.severity}] ${ti.issue}${ti.element ? ` (${ti.element})` : ''}`);
      }
    }
  } else {
    console.log(`\n--- No structured verdict parsed ---`);
    console.log(`Result text (first 1000 chars):`);
    console.log(result.resultText?.slice(0, 1000));
  }

  if (result.toolUseLog?.length) {
    console.log(`\n--- Tool Usage (${result.toolUseLog.length} calls) ---`);
    for (const t of result.toolUseLog) {
      console.log(`  ${t.toolName}: ${t.inputSummary}`);
    }
  }

  console.log(`\n${'='.repeat(60)}`);
  console.log(result.verificationVerdict?.verdict === 'PASS'
    ? '✅ E2E BROWSER VERIFICATION: PASSED'
    : result.verificationVerdict?.verdict === 'FAIL'
    ? '❌ E2E BROWSER VERIFICATION: FAILED'
    : result.verificationVerdict?.verdict === 'TASTE_ISSUE'
    ? '⚠️  E2E BROWSER VERIFICATION: TASTE ISSUES'
    : '❓ E2E BROWSER VERIFICATION: NO VERDICT PARSED');
  console.log(`${'='.repeat(60)}`);

  // Shutdown worker
  sendRequest(worker, 'shutdown', {});
  await new Promise(r => setTimeout(r, 1000));
  worker.kill();
  process.exit(result.success ? 0 : 1);
}

main().catch(e => {
  console.error('Fatal error:', e);
  process.exit(1);
});
