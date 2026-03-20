import { create } from 'zustand';
import type { BackendStatus, ScreenId } from '@/api/types';

interface AppState {
  activeRepoPath: string | null;
  activeRunId: string | null;
  backendStatus: BackendStatus;
  backendVersion: string | null;
  sidebarCollapsed: boolean;
  activeScreen: ScreenId;
  commandPaletteOpen: boolean;

  setActiveRepo: (path: string | null) => void;
  setActiveRun: (runId: string | null) => void;
  setBackendStatus: (status: BackendStatus) => void;
  setBackendVersion: (version: string | null) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setActiveScreen: (screen: ScreenId) => void;
  setCommandPaletteOpen: (open: boolean) => void;
}

export const useAppStore = create<AppState>((set) => ({
  activeRepoPath: null,
  activeRunId: null,
  backendStatus: 'connecting',
  backendVersion: null,
  sidebarCollapsed: false,
  activeScreen: 'dashboard',
  commandPaletteOpen: false,

  setActiveRepo: (path) => set({ activeRepoPath: path }),
  setActiveRun: (runId) => set({ activeRunId: runId }),
  setBackendStatus: (status) => set({ backendStatus: status }),
  setBackendVersion: (version) => set({ backendVersion: version }),
  setSidebarCollapsed: (collapsed) => set({ sidebarCollapsed: collapsed }),
  setActiveScreen: (screen) => set({ activeScreen: screen }),
  setCommandPaletteOpen: (open) => set({ commandPaletteOpen: open }),
}));
