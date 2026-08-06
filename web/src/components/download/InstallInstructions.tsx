'use client';

import { useState } from 'react';
import { Copy, Check } from 'lucide-react';

const INSTALL_METHODS = [
  {
    id: 'homebrew',
    label: 'Homebrew',
    commands: [
      'brew tap SteveVitali/demiurge',
      'brew install demiurge',
    ],
  },
  {
    id: 'curl',
    label: 'Direct (curl)',
    commands: [
      'curl -fsSL https://demiurge.dev/install.sh | bash',
    ],
  },
  {
    id: 'verify',
    label: 'Verify',
    commands: [
      'demiurge doctor',
    ],
  },
];

export function InstallInstructions() {
  const [copied, setCopied] = useState<string | null>(null);

  function copyToClipboard(text: string, id: string) {
    navigator.clipboard.writeText(text);
    setCopied(id);
    setTimeout(() => setCopied(null), 2000);
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-6">
      <h3 className="text-base font-semibold text-text-primary mb-4">
        CLI Installation
      </h3>
      <div className="space-y-4">
        {INSTALL_METHODS.map((method) => {
          const allCommands = method.commands.join('\n');
          return (
            <div key={method.id}>
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-medium text-text-muted uppercase tracking-wider">
                  {method.label}
                </span>
                <button
                  onClick={() => copyToClipboard(allCommands, method.id)}
                  className="flex items-center gap-1 text-xs text-text-muted hover:text-text-secondary transition-colors cursor-pointer"
                >
                  {copied === method.id ? (
                    <>
                      <Check className="h-3 w-3" />
                      Copied
                    </>
                  ) : (
                    <>
                      <Copy className="h-3 w-3" />
                      Copy
                    </>
                  )}
                </button>
              </div>
              <pre className="rounded-lg bg-bg border border-border p-3 overflow-x-auto">
                <code className="text-sm font-mono text-text-secondary">
                  {method.commands.map((cmd, i) => (
                    <div key={i}>
                      <span className="text-text-muted select-none">$ </span>
                      {cmd}
                    </div>
                  ))}
                </code>
              </pre>
            </div>
          );
        })}
      </div>
    </div>
  );
}
