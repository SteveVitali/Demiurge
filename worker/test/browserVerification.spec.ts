// Design: Agentic Browser UI Verification — TypeScript tests
// Tests verdict parsing, MCP server construction, and BrowserArtifactCollector.

import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { BrowserArtifactCollector } from '../src/artifacts/browserArtifactCollector';
import { parseVerificationVerdict } from '../src/methods/agentExecute';

describe('parseVerificationVerdict', () => {
  test('parses PASS verdict from fenced JSON block', () => {
    const text = `I've verified the feature. Here is my verdict:

\`\`\`json
{
  "verdict": "PASS",
  "confidence": 0.95,
  "featureSatisfied": true,
  "observations": [
    {"aspect": "Login form", "status": "pass", "detail": "Form renders correctly"}
  ],
  "tasteIssues": [],
  "screenshots": [
    {"ref": "ss-001.png", "description": "Login page", "phase": "after"}
  ],
  "summary": "All checks passed"
}
\`\`\``;

    const verdict = parseVerificationVerdict(text);
    expect(verdict).toBeDefined();
    expect(verdict!.verdict).toBe('PASS');
    expect(verdict!.confidence).toBe(0.95);
    expect(verdict!.featureSatisfied).toBe(true);
    expect(verdict!.observations).toHaveLength(1);
    expect(verdict!.observations[0].aspect).toBe('Login form');
    expect(verdict!.screenshots).toHaveLength(1);
    expect(verdict!.summary).toBe('All checks passed');
  });

  test('parses FAIL verdict', () => {
    const text = `\`\`\`json
{
  "verdict": "FAIL",
  "confidence": 0.8,
  "featureSatisfied": false,
  "observations": [
    {"aspect": "Button", "status": "fail", "detail": "Button not found"}
  ],
  "tasteIssues": [],
  "screenshots": [],
  "summary": "Feature not implemented"
}
\`\`\``;

    const verdict = parseVerificationVerdict(text);
    expect(verdict).toBeDefined();
    expect(verdict!.verdict).toBe('FAIL');
    expect(verdict!.featureSatisfied).toBe(false);
    expect(verdict!.summary).toBe('Feature not implemented');
  });

  test('parses TASTE_ISSUE verdict', () => {
    const text = `\`\`\`json
{
  "verdict": "TASTE_ISSUE",
  "confidence": 0.7,
  "featureSatisfied": true,
  "observations": [],
  "tasteIssues": [
    {"severity": "warning", "issue": "Low contrast text", "element": "h1"}
  ],
  "screenshots": [],
  "summary": "Feature works but has visual issues"
}
\`\`\``;

    const verdict = parseVerificationVerdict(text);
    expect(verdict).toBeDefined();
    expect(verdict!.verdict).toBe('TASTE_ISSUE');
    expect(verdict!.featureSatisfied).toBe(true);
    expect(verdict!.tasteIssues).toHaveLength(1);
    expect(verdict!.tasteIssues[0].severity).toBe('warning');
    expect(verdict!.tasteIssues[0].element).toBe('h1');
  });

  test('parses verdict from raw JSON (no fences)', () => {
    const text = 'Here is the result: {"verdict": "PASS", "confidence": 0.9, "featureSatisfied": true, "observations": [], "tasteIssues": [], "screenshots": [], "summary": "OK"}';

    const verdict = parseVerificationVerdict(text);
    expect(verdict).toBeDefined();
    expect(verdict!.verdict).toBe('PASS');
  });

  test('returns undefined for empty text', () => {
    expect(parseVerificationVerdict('')).toBeUndefined();
  });

  test('returns undefined for text without verdict', () => {
    expect(parseVerificationVerdict('Just some regular text without any JSON')).toBeUndefined();
  });

  test('returns undefined for invalid JSON', () => {
    const text = '```json\n{broken json\n```';
    expect(parseVerificationVerdict(text)).toBeUndefined();
  });

  test('returns undefined for JSON with unknown verdict value', () => {
    const text = '```json\n{"verdict": "UNKNOWN", "confidence": 0.5}\n```';
    expect(parseVerificationVerdict(text)).toBeUndefined();
  });

  test('provides defaults for missing optional fields', () => {
    const text = '```json\n{"verdict": "PASS"}\n```';
    const verdict = parseVerificationVerdict(text);
    expect(verdict).toBeDefined();
    expect(verdict!.confidence).toBe(0.5);
    expect(verdict!.featureSatisfied).toBe(false);
    expect(verdict!.observations).toEqual([]);
    expect(verdict!.tasteIssues).toEqual([]);
    expect(verdict!.screenshots).toEqual([]);
    expect(verdict!.summary).toBe('');
  });
});

describe('mcpServers construction', () => {
  test('builds correct playwright MCP server config', () => {
    const enableBrowserTools = true;
    const headedBrowser = false;

    const mcpServers: Record<string, unknown> = {};
    if (enableBrowserTools) {
      mcpServers['playwright'] = {
        command: 'npx',
        args: [
          '@playwright/mcp@latest',
          ...(headedBrowser ? ['--headed'] : []),
        ],
      };
    }

    expect(mcpServers['playwright']).toBeDefined();
    const config = mcpServers['playwright'] as { command: string; args: string[] };
    expect(config.command).toBe('npx');
    expect(config.args).toContain('@playwright/mcp@latest');
    expect(config.args).not.toContain('--headed');
  });

  test('includes --headed flag when headedBrowser is true', () => {
    const mcpServers: Record<string, unknown> = {};
    mcpServers['playwright'] = {
      command: 'npx',
      args: ['@playwright/mcp@latest', '--headed'],
    };

    const config = mcpServers['playwright'] as { command: string; args: string[] };
    expect(config.args).toContain('--headed');
  });

  test('verification mode has playwright only, no demiurge', () => {
    const enableMcpTools = false;    // verification mode
    const enableBrowserTools = true;

    const mcpServers: Record<string, unknown> = {};
    if (enableMcpTools) {
      mcpServers['demiurge'] = {};
    }
    if (enableBrowserTools) {
      mcpServers['playwright'] = { command: 'npx', args: ['@playwright/mcp@latest'] };
    }

    expect(mcpServers['demiurge']).toBeUndefined();
    expect(mcpServers['playwright']).toBeDefined();
  });

  test('repair mode with browser has both demiurge and playwright', () => {
    const enableMcpTools = true;     // repair mode
    const enableBrowserTools = true;

    const mcpServers: Record<string, unknown> = {};
    if (enableMcpTools) {
      mcpServers['demiurge'] = { name: 'demiurge' };
    }
    if (enableBrowserTools) {
      mcpServers['playwright'] = { command: 'npx', args: ['@playwright/mcp@latest'] };
    }

    expect(mcpServers['demiurge']).toBeDefined();
    expect(mcpServers['playwright']).toBeDefined();
    expect(Object.keys(mcpServers)).toHaveLength(2);
  });
});

describe('BrowserArtifactCollector', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'demiurge-test-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  test('saveScreenshot creates file and returns artifact', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const pngData = Buffer.from('fake png data');
    const artifact = collector.saveScreenshot(pngData, 'initial');

    expect(artifact.type).toBe('screenshot');
    expect(artifact.contentType).toBe('image/png');
    expect(artifact.sizeBytes).toBe(pngData.length);
    expect(artifact.checksumSha256).toBeTruthy();
    expect(artifact.label).toBe('initial');

    // File should exist on disk
    const fullPath = path.join(tmpDir, artifact.relativePath);
    expect(fs.existsSync(fullPath)).toBe(true);
    expect(fs.readFileSync(fullPath)).toEqual(pngData);
  });

  test('saveAccessibilityTree creates JSON file', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const tree = JSON.stringify({ role: 'document', children: [] });
    const artifact = collector.saveAccessibilityTree(tree, 'page-tree');

    expect(artifact.type).toBe('accessibility_tree');
    expect(artifact.contentType).toBe('application/json');
    expect(artifact.relativePath).toContain('accessibility');
  });

  test('saveVerdict creates verdict file', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const verdict = { verdict: 'PASS', confidence: 0.9, summary: 'All good' };
    const artifact = collector.saveVerdict(verdict);

    expect(artifact.type).toBe('verdict');
    const fullPath = path.join(tmpDir, artifact.relativePath);
    const content = JSON.parse(fs.readFileSync(fullPath, 'utf-8'));
    expect(content.verdict).toBe('PASS');
    expect(content.confidence).toBe(0.9);
  });

  test('saveTranscript creates transcript file', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const messages = [
      { type: 'assistant', content: 'Navigating...' },
      { type: 'tool_result', result: 'screenshot taken' },
    ];
    const artifact = collector.saveTranscript(messages);

    expect(artifact.type).toBe('transcript');
    const fullPath = path.join(tmpDir, artifact.relativePath);
    const content = JSON.parse(fs.readFileSync(fullPath, 'utf-8'));
    expect(content).toHaveLength(2);
  });

  test('saveConsoleLogs creates console log file', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const artifact = collector.saveConsoleLogs('["error: something failed"]');
    expect(artifact.type).toBe('console_log');
    expect(artifact.relativePath).toContain('console');
  });

  test('saveNetworkRequests creates network file', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    const artifact = collector.saveNetworkRequests('["GET /api/data 200"]');
    expect(artifact.type).toBe('network_request');
    expect(artifact.relativePath).toContain('network');
  });

  test('getArtifacts returns all collected artifacts', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    collector.saveScreenshot(Buffer.from('png1'), 'shot1');
    collector.saveScreenshot(Buffer.from('png2'), 'shot2');
    collector.saveVerdict({ verdict: 'PASS' });

    const artifacts = collector.getArtifacts();
    expect(artifacts).toHaveLength(3);
    expect(artifacts.filter(a => a.type === 'screenshot')).toHaveLength(2);
    expect(artifacts.filter(a => a.type === 'verdict')).toHaveLength(1);
  });

  test('getScreenshotPaths returns full paths', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-123',
      verifierId: 'ver-456',
    });

    collector.saveScreenshot(Buffer.from('png1'), 'shot1');
    collector.saveScreenshot(Buffer.from('png2'), 'shot2');

    const paths = collector.getScreenshotPaths();
    expect(paths).toHaveLength(2);
    paths.forEach(p => {
      expect(p.startsWith(tmpDir)).toBe(true);
      expect(fs.existsSync(p)).toBe(true);
    });
  });

  test('artifact paths include runId and verifierId', () => {
    const collector = new BrowserArtifactCollector({
      artifactRoot: tmpDir,
      runId: 'run-abc',
      verifierId: 'ver-xyz',
    });

    const artifact = collector.saveScreenshot(Buffer.from('data'), 'test');
    expect(artifact.relativePath).toContain('run-abc');
    expect(artifact.relativePath).toContain('ver-xyz');
    expect(artifact.relativePath).toContain('browser-verification');
  });
});
