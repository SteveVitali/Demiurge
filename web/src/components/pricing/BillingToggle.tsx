'use client';

interface BillingToggleProps {
  annual: boolean;
  onToggle: (annual: boolean) => void;
}

export function BillingToggle({ annual, onToggle }: BillingToggleProps) {
  return (
    <div className="flex items-center justify-center gap-4">
      <span
        className={`text-sm font-medium transition-colors ${!annual ? 'text-text-primary' : 'text-text-muted cursor-pointer'}`}
        onClick={() => onToggle(false)}
      >
        Monthly
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={annual}
        onClick={() => onToggle(!annual)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${annual ? 'bg-primary' : 'bg-border-light'}`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${annual ? 'translate-x-6' : 'translate-x-1'}`}
        />
      </button>
      <span
        className={`text-sm font-medium transition-colors ${annual ? 'text-text-primary' : 'text-text-muted cursor-pointer'}`}
        onClick={() => onToggle(true)}
      >
        Annual
        <span className="ml-1.5 inline-block rounded-full bg-success/10 px-2 py-0.5 text-xs font-medium text-success">
          Save 17%
        </span>
      </span>
    </div>
  );
}
