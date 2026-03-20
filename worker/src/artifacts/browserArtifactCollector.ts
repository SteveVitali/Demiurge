// Design: Agentic Browser UI Verification §9
// Collects and persists artifacts from browser verification agent sessions:
// screenshots, accessibility trees, console logs, network requests, verdict JSON, transcript.

import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';

export interface BrowserArtifact {
  type: 'screenshot' | 'accessibility_tree' | 'console_log' | 'network_request' | 'verdict' | 'transcript';
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  checksumSha256: string;
  label?: string;
  timestamp: number;
}

export interface BrowserArtifactCollectorOptions {
  artifactRoot: string;
  runId: string;
  verifierId: string;
}

export class BrowserArtifactCollector {
  private artifactRoot: string;
  private runId: string;
  private verifierId: string;
  private artifacts: BrowserArtifact[] = [];
  private screenshotCount = 0;

  constructor(options: BrowserArtifactCollectorOptions) {
    this.artifactRoot = options.artifactRoot;
    this.runId = options.runId;
    this.verifierId = options.verifierId;
  }

  /** Write raw data to an artifact file using temp-file-then-rename. */
  private writeArtifactFile(relativePath: string, data: Buffer | string): { fullPath: string; sizeBytes: number; checksumSha256: string } {
    const fullPath = path.join(this.artifactRoot, relativePath);
    const dir = path.dirname(fullPath);
    fs.mkdirSync(dir, { recursive: true });

    const buf = typeof data === 'string' ? Buffer.from(data, 'utf-8') : data;
    const tmpPath = fullPath + '.tmp.' + crypto.randomBytes(4).toString('hex');

    fs.writeFileSync(tmpPath, buf);
    fs.renameSync(tmpPath, fullPath);

    const checksum = crypto.createHash('sha256').update(buf).digest('hex');
    return { fullPath, sizeBytes: buf.length, checksumSha256: checksum };
  }

  /** Save a screenshot captured by the agent. */
  saveScreenshot(data: Buffer, label?: string): BrowserArtifact {
    this.screenshotCount++;
    const ts = Date.now();
    const name = label ? `${label}_${ts}.png` : `screenshot_${this.screenshotCount}_${ts}.png`;
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, 'screenshots', name);
    const result = this.writeArtifactFile(relativePath, data);

    const artifact: BrowserArtifact = {
      type: 'screenshot',
      relativePath,
      contentType: 'image/png',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      label,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Save an accessibility tree snapshot. */
  saveAccessibilityTree(tree: string, label?: string): BrowserArtifact {
    const ts = Date.now();
    const name = label ? `${label}_${ts}.json` : `a11y_tree_${ts}.json`;
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, 'accessibility', name);
    const result = this.writeArtifactFile(relativePath, tree);

    const artifact: BrowserArtifact = {
      type: 'accessibility_tree',
      relativePath,
      contentType: 'application/json',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      label,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Save console logs collected during the session. */
  saveConsoleLogs(logs: string): BrowserArtifact {
    const ts = Date.now();
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, 'console', `console_${ts}.json`);
    const result = this.writeArtifactFile(relativePath, logs);

    const artifact: BrowserArtifact = {
      type: 'console_log',
      relativePath,
      contentType: 'application/json',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Save network request log. */
  saveNetworkRequests(requests: string): BrowserArtifact {
    const ts = Date.now();
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, 'network', `network_${ts}.json`);
    const result = this.writeArtifactFile(relativePath, requests);

    const artifact: BrowserArtifact = {
      type: 'network_request',
      relativePath,
      contentType: 'application/json',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Save the parsed verification verdict JSON. */
  saveVerdict(verdict: Record<string, unknown>): BrowserArtifact {
    const ts = Date.now();
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, `verdict_${ts}.json`);
    const result = this.writeArtifactFile(relativePath, JSON.stringify(verdict, null, 2));

    const artifact: BrowserArtifact = {
      type: 'verdict',
      relativePath,
      contentType: 'application/json',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Save the full agent transcript (all messages). */
  saveTranscript(messages: unknown[]): BrowserArtifact {
    const ts = Date.now();
    const relativePath = path.join(this.runId, 'browser-verification', this.verifierId, `transcript_${ts}.json`);
    const result = this.writeArtifactFile(relativePath, JSON.stringify(messages, null, 2));

    const artifact: BrowserArtifact = {
      type: 'transcript',
      relativePath,
      contentType: 'application/json',
      sizeBytes: result.sizeBytes,
      checksumSha256: result.checksumSha256,
      timestamp: ts,
    };
    this.artifacts.push(artifact);
    return artifact;
  }

  /** Get all collected artifacts. */
  getArtifacts(): BrowserArtifact[] {
    return [...this.artifacts];
  }

  /** Get screenshot paths for before/after comparison. */
  getScreenshotPaths(): string[] {
    return this.artifacts
      .filter(a => a.type === 'screenshot')
      .map(a => path.join(this.artifactRoot, a.relativePath));
  }
}
