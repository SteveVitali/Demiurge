import { useEffect, useRef, useState } from 'react';
import { Search, Trash2, ArrowDownToLine } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useLogsStore } from '@/stores/logs.store';

// Desktop Phase 3 — §9.5: xterm.js-based log tailing component.
// ANSI-colored terminal output with virtual scrolling, search, auto-scroll.

interface LogTailerProps {
  serviceId: string;
  className?: string;
}

export function LogTailer({ serviceId, className }: LogTailerProps) {
  const termRef = useRef<HTMLDivElement>(null);
  const xtermRef = useRef<import('@xterm/xterm').Terminal | null>(null);
  const fitAddonRef = useRef<import('@xterm/addon-fit').FitAddon | null>(null);
  const searchAddonRef = useRef<import('@xterm/addon-search').SearchAddon | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const lastWrittenRef = useRef(0);
  const buffer = useLogsStore((s) => s.buffers.get(serviceId));

  // Initialize xterm.js (lazy loaded)
  useEffect(() => {
    let mounted = true;

    async function init() {
      const [{ Terminal }, { FitAddon }, { SearchAddon }] = await Promise.all([
        import('@xterm/xterm'),
        import('@xterm/addon-fit'),
        import('@xterm/addon-search'),
      ]);

      // Also load the CSS
      await import('@xterm/xterm/css/xterm.css');

      if (!mounted || !termRef.current) return;

      const fitAddon = new FitAddon();
      const searchAddon = new SearchAddon();

      const term = new Terminal({
        fontSize: 12,
        fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
        theme: {
          background: '#0a0a0a',
          foreground: '#e4e4e7',
          cursor: '#e4e4e7',
          selectionBackground: '#3b82f633',
        },
        scrollback: 10000,
        convertEol: true,
        disableStdin: true,
        cursorBlink: false,
        cursorStyle: 'underline',
      });

      term.loadAddon(fitAddon);
      term.loadAddon(searchAddon);
      term.open(termRef.current);

      try { fitAddon.fit(); } catch { /* ignore initial fit errors */ }

      xtermRef.current = term;
      fitAddonRef.current = fitAddon;
      searchAddonRef.current = searchAddon;
      lastWrittenRef.current = 0;
    }

    init();

    return () => {
      mounted = false;
      xtermRef.current?.dispose();
      xtermRef.current = null;
      fitAddonRef.current = null;
      searchAddonRef.current = null;
      lastWrittenRef.current = 0;
    };
  }, [serviceId]);

  // Handle resize
  useEffect(() => {
    const observer = new ResizeObserver(() => {
      try { fitAddonRef.current?.fit(); } catch { /* ignore */ }
    });
    if (termRef.current) {
      observer.observe(termRef.current);
    }
    return () => observer.disconnect();
  }, []);

  // Track the last known totalCount to detect backfill resets (e.g. reconnect)
  const lastTotalCountRef = useRef(0);

  // Write new lines to terminal
  useEffect(() => {
    const term = xtermRef.current;
    if (!term || !buffer) return;

    const lines = buffer.lines;
    const startIdx = lastWrittenRef.current;

    // Detect backfill reset: totalCount went backwards (new subscription/reconnect)
    const isBackfillReset = buffer.totalCount < lastTotalCountRef.current;
    lastTotalCountRef.current = buffer.totalCount;

    if ((startIdx === 0 || isBackfillReset) && lines.length > 0) {
      // Initial backfill or re-backfill after reconnect — clear and write all
      term.clear();
      for (const line of lines) {
        term.writeln(line);
      }
      lastWrittenRef.current = lines.length;
    } else if (lines.length > startIdx) {
      // Append new lines only
      for (let i = startIdx; i < lines.length; i++) {
        term.writeln(lines[i] ?? '');
      }
      lastWrittenRef.current = lines.length;
    }

    if (autoScroll) {
      term.scrollToBottom();
    }
  }, [buffer, autoScroll]);

  // Search
  useEffect(() => {
    if (searchTerm && searchAddonRef.current) {
      searchAddonRef.current.findNext(searchTerm);
    }
  }, [searchTerm]);

  const handleClear = () => {
    xtermRef.current?.clear();
    lastWrittenRef.current = 0;
  };

  return (
    <div className={cn('flex flex-col', className)}>
      {/* Toolbar */}
      <div className="flex items-center justify-between border-b border-border px-2 py-1 bg-zinc-950/50">
        <span className="text-xs text-muted-foreground">
          {buffer ? `${buffer.totalCount} lines` : 'No logs'}
        </span>
        <div className="flex items-center gap-1">
          {searchOpen && (
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  searchAddonRef.current?.findNext(searchTerm);
                } else if (e.key === 'Escape') {
                  setSearchOpen(false);
                  setSearchTerm('');
                }
              }}
              placeholder="Search..."
              className="h-5 w-32 rounded border border-border bg-background px-1 text-xs"
              autoFocus
            />
          )}
          <button
            onClick={() => setSearchOpen(!searchOpen)}
            className="rounded p-1 text-muted-foreground hover:text-foreground hover:bg-accent"
            title="Search (Ctrl+F)"
          >
            <Search className="h-3 w-3" />
          </button>
          <button
            onClick={handleClear}
            className="rounded p-1 text-muted-foreground hover:text-foreground hover:bg-accent"
            title="Clear"
          >
            <Trash2 className="h-3 w-3" />
          </button>
          <button
            onClick={() => setAutoScroll(!autoScroll)}
            className={cn(
              'rounded p-1 hover:bg-accent',
              autoScroll ? 'text-blue-400' : 'text-muted-foreground hover:text-foreground',
            )}
            title={autoScroll ? 'Auto-scroll ON' : 'Auto-scroll OFF'}
          >
            <ArrowDownToLine className="h-3 w-3" />
          </button>
        </div>
      </div>

      {/* Terminal */}
      <div ref={termRef} className="flex-1 min-h-[200px]" />
    </div>
  );
}
