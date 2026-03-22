import { create } from 'zustand';
import type { AgentTranscriptMessage, AgentCost } from '@/api/types';

// Desktop Phase 3 — §8.4: Agent transcript state for live streaming.

const MAX_TRANSCRIPT_MESSAGES = 5000;

interface AgentState {
  messages: AgentTranscriptMessage[];
  cost: AgentCost;
  isActive: boolean;
  isPaused: boolean;

  appendMessage: (msg: AgentTranscriptMessage) => void;
  setMessages: (msgs: AgentTranscriptMessage[]) => void;
  updateCost: (cost: Partial<AgentCost>) => void;
  setActive: (active: boolean) => void;
  setPaused: (paused: boolean) => void;
  reset: () => void;
}

const DEFAULT_COST: AgentCost = {
  inputTokens: 0,
  outputTokens: 0,
  costUsd: 0,
  numTurns: 0,
  durationMs: 0,
};

export const useAgentStore = create<AgentState>((set, get) => ({
  messages: [],
  cost: { ...DEFAULT_COST },
  isActive: false,
  isPaused: false,

  appendMessage: (msg) => {
    const state = get();
    const messages = [...state.messages, msg];
    if (messages.length > MAX_TRANSCRIPT_MESSAGES) {
      messages.splice(0, messages.length - MAX_TRANSCRIPT_MESSAGES);
    }
    set({ messages });
  },

  setMessages: (msgs) => set({ messages: msgs.slice(-MAX_TRANSCRIPT_MESSAGES) }),

  updateCost: (partial) => {
    const state = get();
    set({ cost: { ...state.cost, ...partial } });
  },

  setActive: (active) => set({ isActive: active }),
  setPaused: (paused) => set({ isPaused: paused }),

  reset: () => set({
    messages: [],
    cost: { ...DEFAULT_COST },
    isActive: false,
    isPaused: false,
  }),
}));
