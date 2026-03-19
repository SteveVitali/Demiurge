import { create } from 'zustand';

interface AuthState {
  licenseKey: string | null;
  planTier: string | null;
  userEmail: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;

  loadCredentials: () => Promise<void>;
  setCredentials: (key: string, email?: string, tier?: string) => Promise<void>;
  clearCredentials: () => Promise<void>;
}

async function getTauriStore() {
  try {
    const { load } = await import('@tauri-apps/plugin-store');
    return await load('.credentials.json');
  } catch {
    return null;
  }
}

export const useAuthStore = create<AuthState>((set) => ({
  licenseKey: null,
  planTier: null,
  userEmail: null,
  isAuthenticated: false,
  isLoading: true,

  loadCredentials: async () => {
    try {
      const store = await getTauriStore();
      if (!store) {
        set({ isLoading: false });
        return;
      }
      const key = await store.get<string>('license_key');
      const tier = await store.get<string>('plan_tier');
      const email = await store.get<string>('user_email');
      set({
        licenseKey: key ?? null,
        planTier: tier ?? null,
        userEmail: email ?? null,
        isAuthenticated: !!key,
        isLoading: false,
      });
    } catch {
      set({ isLoading: false });
    }
  },

  setCredentials: async (key, email, tier) => {
    try {
      const store = await getTauriStore();
      if (store) {
        await store.set('license_key', key);
        if (email) await store.set('user_email', email);
        if (tier) await store.set('plan_tier', tier);
        await store.save();
      }
    } catch {
      // Fallback: store in memory only (dev mode without Tauri)
    }
    set({
      licenseKey: key,
      userEmail: email ?? null,
      planTier: tier ?? null,
      isAuthenticated: true,
    });
  },

  clearCredentials: async () => {
    try {
      const store = await getTauriStore();
      if (store) {
        await store.delete('license_key');
        await store.delete('plan_tier');
        await store.delete('user_email');
        await store.save();
      }
    } catch {
      // Fallback: clear in memory only
    }
    set({
      licenseKey: null, planTier: null, userEmail: null,
      isAuthenticated: false,
    });
  },
}));
