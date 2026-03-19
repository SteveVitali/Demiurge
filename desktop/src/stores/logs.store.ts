import { create } from 'zustand';

// Desktop Phase 3 — §8.3: Per-service log ring buffers for xterm.js display.

interface ServiceLogBuffer {
  lines: string[];
  totalCount: number;
}

interface LogsState {
  buffers: Map<string, ServiceLogBuffer>;
  activeServiceId: string | null;

  setActiveService: (serviceId: string | null) => void;
  backfill: (serviceId: string, lines: string[]) => void;
  appendLine: (serviceId: string, line: string) => void;
  clearBuffer: (serviceId: string) => void;
  reset: () => void;
}

const LOG_LINE_LIMIT = 10_000;

export const useLogsStore = create<LogsState>((set, get) => ({
  buffers: new Map(),
  activeServiceId: null,

  setActiveService: (serviceId) => set({ activeServiceId: serviceId }),

  backfill: (serviceId, lines) => {
    const buffers = new Map(get().buffers);
    buffers.set(serviceId, {
      lines: lines.slice(-LOG_LINE_LIMIT),
      totalCount: lines.length,
    });
    set({ buffers });
  },

  appendLine: (serviceId, line) => {
    const buffers = new Map(get().buffers);
    const existing = buffers.get(serviceId) ?? { lines: [], totalCount: 0 };
    const newLines = [...existing.lines, line];
    if (newLines.length > LOG_LINE_LIMIT) {
      newLines.splice(0, newLines.length - LOG_LINE_LIMIT);
    }
    buffers.set(serviceId, {
      lines: newLines,
      totalCount: existing.totalCount + 1,
    });
    set({ buffers });
  },

  clearBuffer: (serviceId) => {
    const state = get();
    const buffers = new Map(state.buffers);
    buffers.delete(serviceId);
    set({ buffers });
  },

  reset: () => set({
    buffers: new Map(),
    activeServiceId: null,
  }),
}));
