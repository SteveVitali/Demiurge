import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { RunMode } from '@/api/types';

interface PreferencesState {
  theme: 'system' | 'light' | 'dark';
  fontSize: number;
  logLineLimit: number;
  autoConnectOnLaunch: boolean;
  showSystemTrayNotifications: boolean;
  defaultRepoPath: string | null;
  defaultRunMode: RunMode;
  defaultMaxAttempts: number;
  defaultRunTimeoutMs: number;
  anthropicApiKeySet: boolean;
  hasCompletedOnboarding: boolean;

  setTheme: (theme: PreferencesState['theme']) => void;
  setFontSize: (size: number) => void;
  setLogLineLimit: (limit: number) => void;
  setAutoConnectOnLaunch: (enabled: boolean) => void;
  setShowSystemTrayNotifications: (enabled: boolean) => void;
  setDefaultRepoPath: (path: string | null) => void;
  setDefaultRunMode: (mode: RunMode) => void;
  setDefaultMaxAttempts: (max: number) => void;
  setDefaultRunTimeoutMs: (ms: number) => void;
  setAnthropicApiKeySet: (set: boolean) => void;
  setHasCompletedOnboarding: (done: boolean) => void;
}

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set) => ({
      theme: 'dark',
      fontSize: 14,
      logLineLimit: 10000,
      autoConnectOnLaunch: true,
      showSystemTrayNotifications: true,
      defaultRepoPath: null,
      defaultRunMode: 'Full',
      defaultMaxAttempts: 5,
      defaultRunTimeoutMs: 1800000,
      anthropicApiKeySet: false,
      hasCompletedOnboarding: false,

      setTheme: (theme) => set({ theme }),
      setFontSize: (fontSize) => set({ fontSize }),
      setLogLineLimit: (logLineLimit) => set({ logLineLimit }),
      setAutoConnectOnLaunch: (autoConnectOnLaunch) => set({ autoConnectOnLaunch }),
      setShowSystemTrayNotifications: (showSystemTrayNotifications) => set({ showSystemTrayNotifications }),
      setDefaultRepoPath: (defaultRepoPath) => set({ defaultRepoPath }),
      setDefaultRunMode: (defaultRunMode) => set({ defaultRunMode }),
      setDefaultMaxAttempts: (defaultMaxAttempts) => set({ defaultMaxAttempts }),
      setDefaultRunTimeoutMs: (defaultRunTimeoutMs) => set({ defaultRunTimeoutMs }),
      setAnthropicApiKeySet: (anthropicApiKeySet) => set({ anthropicApiKeySet }),
      setHasCompletedOnboarding: (hasCompletedOnboarding) => set({ hasCompletedOnboarding }),
    }),
    {
      name: 'demiurge-preferences',
    },
  ),
);
