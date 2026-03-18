// Spec §12.3: SHA-256 checksum for artifact integrity verification

import * as crypto from 'crypto';
import * as fs from 'fs';

export function sha256(data: Buffer): string {
  return crypto.createHash('sha256').update(data).digest('hex');
}

export function sha256File(filePath: string): string {
  const data = fs.readFileSync(filePath);
  return sha256(data);
}
