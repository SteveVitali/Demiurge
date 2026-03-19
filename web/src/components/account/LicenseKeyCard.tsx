'use client';

import { useState } from 'react';
import { Copy, Check, Key } from 'lucide-react';

interface LicenseKeyCardProps {
  licenseKey: string | null;
}

export function LicenseKeyCard({ licenseKey }: LicenseKeyCardProps) {
  const [copied, setCopied] = useState(false);

  function handleCopy() {
    if (!licenseKey) return;
    navigator.clipboard.writeText(licenseKey);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-6">
      <div className="flex items-center gap-2 mb-4">
        <Key className="h-5 w-5 text-primary" />
        <h2 className="text-base font-semibold text-text-primary">License Key</h2>
      </div>
      {licenseKey ? (
        <div className="flex items-center gap-3">
          <code className="flex-1 rounded-lg bg-bg border border-border px-4 py-2.5 text-sm font-mono text-text-secondary">
            {licenseKey}
          </code>
          <button
            onClick={handleCopy}
            className="shrink-0 rounded-lg border border-border p-2.5 text-text-muted hover:text-text-primary hover:border-border-light transition-colors cursor-pointer"
            aria-label="Copy license key"
          >
            {copied ? <Check className="h-4 w-4 text-success" /> : <Copy className="h-4 w-4" />}
          </button>
        </div>
      ) : (
        <p className="text-sm text-text-muted">
          No license key found. Start a free trial or subscribe to get one.
        </p>
      )}
    </div>
  );
}
