// Spec §12.3: Artifact writer — temp-file-then-rename strategy
// All artifacts written to temp file first, then atomically renamed to final path.
// SHA-256 checksum computed on write.

import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';
import * as zlib from 'zlib';
import { ArtifactMetadata } from '../rpc/types';

// Spec §12.3: 1 MB threshold for gzip compression
const COMPRESSION_THRESHOLD = 1024 * 1024;

export class ArtifactWriter {
  private artifactRoot: string;
  private runId: string;

  constructor(artifactRoot: string, runId: string) {
    this.artifactRoot = artifactRoot;
    this.runId = runId;
  }

  // Spec §12.3: Write artifact using temp-file-then-rename
  async writeArtifact(
    artifactType: string,
    content: Buffer | string,
    relativePath: string,
    contentType: string,
    label?: string,
  ): Promise<ArtifactMetadata> {
    const data = typeof content === 'string' ? Buffer.from(content, 'utf-8') : content;
    const fullDir = path.dirname(path.join(this.artifactRoot, relativePath));
    fs.mkdirSync(fullDir, { recursive: true });

    // Spec §12.3: Compress artifacts > 1 MB
    let writeData = data;
    let compressed = false;
    let finalRelativePath = relativePath;
    if (data.length > COMPRESSION_THRESHOLD) {
      writeData = zlib.gzipSync(data);
      compressed = true;
      finalRelativePath = relativePath + '.gz';
    }

    const finalPath = path.join(this.artifactRoot, finalRelativePath);
    const tmpPath = finalPath + '.tmp.' + crypto.randomBytes(4).toString('hex');

    // Temp-file-then-rename
    fs.writeFileSync(tmpPath, writeData);
    fs.renameSync(tmpPath, finalPath);

    const checksum = crypto.createHash('sha256').update(writeData).digest('hex');

    return {
      artifactType,
      relativePath: finalRelativePath,
      contentType: compressed ? 'application/gzip' : contentType,
      sizeBytes: writeData.length,
      checksumSha256: checksum,
      label,
    };
  }

  // Convenience methods for common artifact types

  async writeScreenshot(page: { screenshot: (opts?: any) => Promise<Buffer> }, label: string, taskId: string): Promise<ArtifactMetadata> {
    const buffer = await page.screenshot({ fullPage: true, type: 'png' });
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/screenshots/${label}_${ts}.png`;
    return this.writeArtifact('Screenshot', buffer, relPath, 'image/png', label);
  }

  async writeTrace(traceBuffer: Buffer, taskId: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/traces/trace_${ts}.zip`;
    return this.writeArtifact('BrowserTrace', traceBuffer, relPath, 'application/zip', 'trace');
  }

  async writeConsoleLog(logs: string, taskId: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/console/console_${ts}.json`;
    return this.writeArtifact('ConsoleLog', logs, relPath, 'application/json', 'console-log');
  }

  async writeNetworkSummary(summary: string, taskId: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/network/network_${ts}.json`;
    return this.writeArtifact('NetworkSummary', summary, relPath, 'application/json', 'network-summary');
  }

  async writeDomSnapshot(html: string, taskId: string, label: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/dom/${label}_${ts}.html`;
    return this.writeArtifact('DomSnapshot', html, relPath, 'text/html', label);
  }

  async writeAccessibilitySnapshot(tree: string, taskId: string, label: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/accessibility/${label}_${ts}.json`;
    return this.writeArtifact('AccessibilitySnapshot', tree, relPath, 'application/json', label);
  }

  async writeStorageState(content: string, taskId: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/auth/storage_state_${ts}.json`;
    return this.writeArtifact('AuthStorageState', content, relPath, 'application/json', 'storage-state');
  }

  async writeApiRequestResponse(content: string, taskId: string, label: string): Promise<ArtifactMetadata> {
    const ts = Date.now();
    const relPath = `${this.runId}/${taskId}/api/${label}_${ts}.json`;
    return this.writeArtifact('ApiRequestResponse', content, relPath, 'application/json', label);
  }
}
