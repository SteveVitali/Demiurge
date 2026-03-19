import { useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';

// Desktop Phase 5: Enhanced log renderer with xterm.js for ANSI color support.
// Falls back to plain <pre> if xterm.js fails to load or content has no ANSI codes.

interface LogRendererProps {
  content: string;
  className?: string;
}

const ANSI_REGEX = /\x1b\[/;

export function LogRenderer({ content, className }: LogRendererProps) {
  const termRef = useRef<HTMLDivElement>(null);
  const xtermRef = useRef<import('@xterm/xterm').Terminal | null>(null);

  const hasAnsi = ANSI_REGEX.test(content);

  // Initialize xterm.js once, then write content on each change
  useEffect(() => {
    if (!hasAnsi) return;

    let mounted = true;

    async function init() {
      try {
        const [{ Terminal }, { FitAddon }] = await Promise.all([
          import('@xterm/xterm'),
          import('@xterm/addon-fit'),
        ]);
        await import('@xterm/xterm/css/xterm.css');

        if (!mounted || !termRef.current) return;

        // Dispose previous instance if content changed
        if (xtermRef.current) {
          xtermRef.current.dispose();
          xtermRef.current = null;
        }

        const fitAddon = new FitAddon();
        const term = new Terminal({
          fontSize: 12,
          fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          theme: {
            background: '#0a0a0a',
            foreground: '#e4e4e7',
            cursor: '#e4e4e7',
          },
          scrollback: 50000,
          convertEol: true,
          disableStdin: true,
          cursorBlink: false,
          cursorStyle: 'underline',
        });

        term.loadAddon(fitAddon);
        term.open(termRef.current);
        try { fitAddon.fit(); } catch { /* ignore */ }

        const lines = content.split('\n');
        for (const line of lines) {
          term.writeln(line);
        }

        xtermRef.current = term;
      } catch {
        // xterm.js failed to load — plain text fallback will show
      }
    }

    init();

    return () => {
      mounted = false;
      xtermRef.current?.dispose();
      xtermRef.current = null;
    };
  }, [content, hasAnsi]);

  return (
    <div className={cn('relative', className)}>
      {hasAnsi ? (
        <div ref={termRef} className="min-h-[200px]" />
      ) : (
        <pre className="overflow-auto whitespace-pre-wrap p-4 text-xs font-mono text-foreground bg-zinc-950 rounded">
          {content}
        </pre>
      )}
    </div>
  );
}
