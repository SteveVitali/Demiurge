'use client';

interface BillingToggleProps {
  annual: boolean;
  onToggle: (annual: boolean) => void;
}

export function BillingToggle({ annual, onToggle }: BillingToggleProps) {
  return (
    <div className="flex items-center justify-center gap-4">
      <button
        type="button"
        className={`text-sm font-medium transition-colors cursor-pointer ${!annual ? 'text-text-primary' : 'text-text-muted'}`}
        onClick={() => onToggle(false)}
      >
        Monthly
      </button>
      <button
        type="button"
        role="switch"
        aria-checked={annual}
        aria-label="Toggle annual billing"
        onClick={() => onToggle(!annual)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors cursor-pointer ${annual ? 'bg-primary' : 'bg-border-light'}`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${annual ? 'translate-x-6' : 'translate-x-1'}`}
        />
      </button>
      <button
        type="button"
        className={`text-sm font-medium transition-colors cursor-pointer ${annual ? 'text-text-primary' : 'text-text-muted'}`}
        onClick={() => onToggle(true)}
      >
        Annual
        <span className="ml-1.5 inline-block rounded-full bg-success/10 px-2 py-0.5 text-xs font-medium text-success">
          Save 17%
        </span>
      </button>
    </div>
  );
}
