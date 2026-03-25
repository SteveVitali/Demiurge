import { useState, useEffect, useCallback } from 'react';
import { FolderOpen, Save, CheckCircle, Sparkles } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { useAppStore } from '@/stores/app.store';
import { usePreferencesStore } from '@/stores/preferences.store';
import { getConfig, saveManifest, saveRequirements } from '@/api/endpoints';
import { queryKeys } from '@/lib/query-keys';
import { ManifestEditor } from '@/components/config/ManifestEditor';
import { RequirementsEditor } from '@/components/config/RequirementsEditor';
import { BudgetEditor } from '@/components/config/BudgetEditor';
import { ProvenanceView } from '@/components/config/ProvenanceView';
import { cn } from '@/lib/utils';

type ConfigTab = 'manifest' | 'requirements' | 'budget';

export function ConfigScreen() {
  const activeRepoPath = useAppStore((s) => s.activeRepoPath);
  const setActiveRepo = useAppStore((s) => s.setActiveRepo);
  const setSmartInitWizardOpen = useAppStore((s) => s.setSmartInitWizardOpen);
  const defaultRepoPath = usePreferencesStore((s) => s.defaultRepoPath);

  const repoPath = activeRepoPath ?? defaultRepoPath ?? '';
  const [activeTab, setActiveTab] = useState<ConfigTab>('manifest');
  const [manifestYaml, setManifestYaml] = useState('');
  const [requirementsYaml, setRequirementsYaml] = useState('');
  const [budget, setBudget] = useState({});
  const [saving, setSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'success' | 'error'>('idle');
  const [saveError, setSaveError] = useState<string | null>(null);

  const { data: config, isLoading } = useQuery({
    queryKey: queryKeys.config.resolved(repoPath),
    queryFn: () => getConfig(repoPath),
    enabled: !!repoPath,
  });

  useEffect(() => {
    if (config) {
      setManifestYaml(config.manifestYaml ?? '');
      setRequirementsYaml(config.requirementsYaml ?? '');
    }
  }, [config]);

  const handleFolderPick = useCallback(async () => {
    try {
      const { open: openDialog } = await import('@tauri-apps/plugin-dialog');
      const selected = await openDialog({ directory: true, multiple: false });
      if (selected) setActiveRepo(selected as string);
    } catch {
      // Tauri not available
    }
  }, [setActiveRepo]);

  const handleSave = useCallback(async () => {
    if (!repoPath) return;
    setSaving(true);
    setSaveStatus('idle');
    setSaveError(null);
    try {
      if (activeTab === 'manifest') {
        await saveManifest(repoPath, manifestYaml);
      } else if (activeTab === 'requirements') {
        await saveRequirements(repoPath, requirementsYaml);
      }
      setSaveStatus('success');
      setTimeout(() => setSaveStatus('idle'), 2000);
    } catch (err) {
      setSaveStatus('error');
      setSaveError(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [repoPath, activeTab, manifestYaml, requirementsYaml]);

  const tabs: { id: ConfigTab; label: string }[] = [
    { id: 'manifest', label: 'Manifest' },
    { id: 'requirements', label: 'Requirements' },
    { id: 'budget', label: 'Budget' },
  ];

  return (
    <div className="flex flex-1 flex-col gap-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Configuration</h1>
      </div>

      {/* Repo Selector */}
      <div className="flex items-center gap-2">
        <span className="text-sm text-muted-foreground">Repo:</span>
        <code className="rounded-md bg-accent px-2 py-1 text-sm">{repoPath || 'No repo selected'}</code>
        <button
          onClick={() => void handleFolderPick()}
          className="rounded-md border border-border p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <FolderOpen className="h-4 w-4" />
        </button>
      </div>

      {!repoPath ? (
        <div className="flex flex-1 items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-muted-foreground">
            <FolderOpen className="h-10 w-10" />
            <p className="text-sm">Select a repo folder to view and edit configuration</p>
          </div>
        </div>
      ) : isLoading ? (
        <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
          Loading configuration...
        </div>
      ) : (
        <>
          {/* Tabs */}
          <div className="flex items-center gap-1 border-b border-border">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  'px-4 py-2 text-sm transition-colors',
                  activeTab === tab.id
                    ? 'border-b-2 border-blue-500 text-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {/* Tab Content */}
          <div className="flex-1 overflow-y-auto">
            {activeTab === 'manifest' && (
              <ManifestEditor yaml={manifestYaml} onChange={setManifestYaml} />
            )}
            {activeTab === 'requirements' && (
              <RequirementsEditor yaml={requirementsYaml} onChange={setRequirementsYaml} />
            )}
            {activeTab === 'budget' && (
              <BudgetEditor budget={budget} onChange={setBudget} />
            )}
          </div>

          {/* Provenance + Actions */}
          {config && <ProvenanceView provenance={config.provenance} />}

          <div className="flex items-center justify-between border-t border-border pt-4">
            <div className="flex items-center gap-2">
              {saveStatus === 'success' && (
                <span className="flex items-center gap-1 text-xs text-green-400">
                  <CheckCircle className="h-3.5 w-3.5" /> Saved
                </span>
              )}
              {saveStatus === 'error' && saveError && (
                <span className="text-xs text-red-400">{saveError}</span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setSmartInitWizardOpen(true)}
                className="flex items-center gap-1.5 rounded-md border border-border px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <Sparkles className="h-4 w-4" />
                Smart Init
              </button>
              {activeTab !== 'budget' && (
                <button
                  onClick={() => void handleSave()}
                  disabled={saving || !repoPath}
                  className={cn(
                    'flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium',
                    saving ? 'cursor-not-allowed bg-blue-500/30 text-blue-400/50' : 'bg-blue-600 text-white hover:bg-blue-700',
                  )}
                >
                  <Save className="h-4 w-4" />
                  {saving ? 'Saving...' : 'Save'}
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
