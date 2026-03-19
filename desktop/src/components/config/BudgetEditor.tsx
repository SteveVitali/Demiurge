
interface BudgetValues {
  maxAttempts: number;
  runTimeoutMs: number;
  attemptTimeoutMs: number;
  maxPatchLines: number;
  maxArtifactDiskBytes: number;
  allowGitPush: boolean;
  allowDbDrop: boolean;
  allowedHosts: string[];
  browserAllowedOrigins: string[];
}

const DEFAULT_BUDGET: BudgetValues = {
  maxAttempts: 5,
  runTimeoutMs: 1800000,
  attemptTimeoutMs: 300000,
  maxPatchLines: 2000,
  maxArtifactDiskBytes: 104857600,
  allowGitPush: false,
  allowDbDrop: false,
  allowedHosts: [],
  browserAllowedOrigins: [],
};

interface BudgetEditorProps {
  budget: Partial<BudgetValues>;
  onChange: (budget: Partial<BudgetValues>) => void;
  readOnly?: boolean;
}

export function BudgetEditor({ budget, onChange, readOnly }: BudgetEditorProps) {
  const values = { ...DEFAULT_BUDGET, ...budget };

  const update = (key: keyof BudgetValues, value: unknown) => {
    onChange({ ...budget, [key]: value });
  };

  return (
    <div className="flex flex-col gap-6">
      {/* Numeric Controls */}
      <section>
        <h3 className="mb-3 text-sm font-medium">Limits</h3>
        <div className="flex flex-col gap-4">
          <SliderField
            label="Max Attempts"
            value={values.maxAttempts}
            min={1}
            max={10}
            step={1}
            format={(v) => `${v}`}
            onChange={(v) => update('maxAttempts', v)}
            readOnly={readOnly}
          />
          <SliderField
            label="Run Timeout"
            value={values.runTimeoutMs}
            min={60000}
            max={7200000}
            step={60000}
            format={(v) => `${Math.round(v / 60000)}m`}
            onChange={(v) => update('runTimeoutMs', v)}
            readOnly={readOnly}
          />
          <SliderField
            label="Attempt Timeout"
            value={values.attemptTimeoutMs}
            min={60000}
            max={1200000}
            step={60000}
            format={(v) => `${Math.round(v / 60000)}m`}
            onChange={(v) => update('attemptTimeoutMs', v)}
            readOnly={readOnly}
          />
          <SliderField
            label="Max Patch Lines"
            value={values.maxPatchLines}
            min={100}
            max={10000}
            step={100}
            format={(v) => `${v}`}
            onChange={(v) => update('maxPatchLines', v)}
            readOnly={readOnly}
          />
          <SliderField
            label="Max Artifact Disk"
            value={values.maxArtifactDiskBytes}
            min={10485760}
            max={1073741824}
            step={10485760}
            format={(v) => `${Math.round(v / 1048576)} MB`}
            onChange={(v) => update('maxArtifactDiskBytes', v)}
            readOnly={readOnly}
          />
        </div>
      </section>

      {/* Toggles */}
      <section>
        <h3 className="mb-3 text-sm font-medium">Permissions</h3>
        <div className="flex flex-col gap-3">
          <ToggleField
            label="Allow Git Push"
            description="Allow the agent to push commits to remote"
            checked={values.allowGitPush}
            onChange={(v) => update('allowGitPush', v)}
            readOnly={readOnly}
          />
          <ToggleField
            label="Allow DB Drop"
            description="Allow the agent to drop/reset databases"
            checked={values.allowDbDrop}
            onChange={(v) => update('allowDbDrop', v)}
            readOnly={readOnly}
          />
        </div>
      </section>

      {/* Lists */}
      <section>
        <h3 className="mb-3 text-sm font-medium">Network</h3>
        <div className="flex flex-col gap-3">
          <ListField
            label="Allowed Hosts"
            values={values.allowedHosts}
            onChange={(v) => update('allowedHosts', v)}
            readOnly={readOnly}
          />
          <ListField
            label="Browser Allowed Origins"
            values={values.browserAllowedOrigins}
            onChange={(v) => update('browserAllowedOrigins', v)}
            readOnly={readOnly}
          />
        </div>
      </section>
    </div>
  );
}

function SliderField({
  label, value, min, max, step, format, onChange, readOnly,
}: {
  label: string; value: number; min: number; max: number; step: number;
  format: (v: number) => string; onChange: (v: number) => void; readOnly?: boolean;
}) {
  return (
    <div className="flex items-center gap-4">
      <label className="w-36 text-xs text-muted-foreground">{label}</label>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        disabled={readOnly}
        className="flex-1"
      />
      <span className="w-16 text-right text-xs font-mono text-foreground">{format(value)}</span>
    </div>
  );
}

function ToggleField({
  label, description, checked, onChange, readOnly,
}: {
  label: string; description: string; checked: boolean;
  onChange: (v: boolean) => void; readOnly?: boolean;
}) {
  return (
    <label className="flex items-start gap-3">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={readOnly}
        className="mt-0.5"
      />
      <div>
        <span className="text-sm">{label}</span>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
    </label>
  );
}

function ListField({
  label, values, onChange, readOnly,
}: {
  label: string; values: string[]; onChange: (v: string[]) => void;
  readOnly?: boolean;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs text-muted-foreground">{label}</label>
      <div className="flex flex-col gap-1">
        {values.map((v, i) => (
          <div key={i} className="flex items-center gap-2">
            <input
              type="text"
              value={v}
              onChange={(e) => {
                const next = [...values];
                next[i] = e.target.value;
                onChange(next);
              }}
              readOnly={readOnly}
              className="flex-1 rounded-md border border-border bg-background px-2 py-1 text-xs"
            />
            {!readOnly && (
              <button
                onClick={() => onChange(values.filter((_, j) => j !== i))}
                className="text-xs text-muted-foreground hover:text-red-400"
              >
                ✕
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <button
            onClick={() => onChange([...values, ''])}
            className="mt-1 text-xs text-blue-400 hover:text-blue-300"
          >
            + Add
          </button>
        )}
      </div>
    </div>
  );
}
