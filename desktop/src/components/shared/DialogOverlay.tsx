import type { ReactNode } from 'react';

interface DialogOverlayProps {
  onClose: () => void;
  children: ReactNode;
  maxWidth?: string;
}

export function DialogOverlay({ onClose, children, maxWidth = 'max-w-lg' }: DialogOverlayProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className={`w-full ${maxWidth} rounded-lg border border-border bg-background p-6 shadow-xl`}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
