import { create } from 'zustand';
import type { RunStatus, VerdictStatus, SystemEvent, SSEStatus } from '@/api/types';

interface RunState {
  currentStatus: RunStatus | null;
  currentAttempt: number;
  events: SystemEvent[];
  latestVerdicts: Map<string, VerdictStatus>;

  runStartedAt: number | null;
  attemptStartedAt: number | null;
  sseStatus: SSEStatus;

  handleEvent: (event: SystemEvent) => void;
  setSSEStatus: (status: SSEStatus) => void;
  reset: () => void;
}

const MAX_EVENTS = 500;

export const useRunStore = create<RunState>((set, get) => ({
  currentStatus: null,
  currentAttempt: 1,
  events: [],
  latestVerdicts: new Map(),

  runStartedAt: null,
  attemptStartedAt: null,
  sseStatus: 'disconnected',

  handleEvent: (event: SystemEvent) => {
    const state = get();
    const events = [...state.events, event].slice(-MAX_EVENTS);

    let currentStatus = state.currentStatus;
    let currentAttempt = state.currentAttempt;
    const latestVerdicts = new Map(state.latestVerdicts);

    if (event.eventType === 'state_transition') {
      const toStatus = event.payload['to_status'] as RunStatus | undefined;
      if (toStatus) {
        currentStatus = toStatus;
      }
    }

    if (event.eventType === 'verdict_produced') {
      const reqId = event.payload['requirementId'] as string | undefined;
      const status = event.payload['status'] as VerdictStatus | undefined;
      if (reqId && status) {
        latestVerdicts.set(reqId, status);
      }
    }

    if (event.attemptNumber !== null && event.attemptNumber > currentAttempt) {
      currentAttempt = event.attemptNumber;
    }

    set({
      events,
      currentStatus,
      currentAttempt,
      latestVerdicts,
    });
  },

  setSSEStatus: (status) => set({ sseStatus: status }),

  reset: () => set({
    currentStatus: null,
    currentAttempt: 1,
    events: [],
    latestVerdicts: new Map(),
    runStartedAt: null,
    attemptStartedAt: null,
    sseStatus: 'disconnected',
  }),
}));
