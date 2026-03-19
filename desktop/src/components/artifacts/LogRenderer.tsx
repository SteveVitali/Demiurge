import { useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';

// Desktop Phase 5: Enhanced log renderer with xterm.js for ANSI color support.
// Falls back to plain <pre> if xterm.js fails to load.

interface LogRendererProps {
  content: string;
  className?: string;
}

export function LogRenderer({ content, className }: LogRendererProps) {
  const termRef = useRef<HTMLDivElement>(null);
  const xtermRef = useRef<import('@xterm/xterm').Terminal | null>(null);
  const initRef = useRef(false);

  useEffect(() => {
    if (initRef.current) return;
    initRef.current = true;

    let mounted = true;

    async function init() {
      try {
        const [{ Terminal }, { FitAddon }] = await Promise.all([
          import('@xterm/xterm'),
          import('@xterm/addon-fit'),
        ]);
        await import('@xterm/xterm/css/xterm.css');

        if (!mounted || !termRef.current) return;

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

        // Write all content
        const lines = content.split('\n');
        for (const line of lines) {
          term.writeln(line);
        }

        xtermRef.current = term;
      } catch {
        // xterm.js failed to load — fallback is already rendered
      }
    }

    init();

    return () => {
      mounted = false;
      xtermRef.current?.dispose();
      xtermRef.current = null;
    };
  }, [content]);

  // Fallback: detect if content has ANSI codes
  const hasAnsi = /\x1b\[/.test(content);

  return (
    <div className={cn('relative', className)}>
      {/* xterm.js container (hidden if no ANSI codes — use plain pre instead) */}
      <div
        ref={termRef}
        className={cn(
          'min-h-[200px]',
          !hasAnsi && 'hidden',
        )}
      />
      {/* Plain text fallback for non-ANSI content */}
      {!hasAnsi && (
        <pre className="overflow-auto whitespace-pre-wrap p-4 text-xs font-mono text-foreground bg-zinc-950 rounded">
          {content}
        </pre>
      )}
    </div>
  );
}
