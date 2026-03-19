'use client';

import { useState } from 'react';
import { useUser, SignInButton } from '@clerk/nextjs';

interface CheckoutButtonProps {
  priceId: string | null;
  label: string;
  highlighted?: boolean;
}

export function CheckoutButton({ priceId, label, highlighted = false }: CheckoutButtonProps) {
  const { isSignedIn } = useUser();
  const [loading, setLoading] = useState(false);

  async function handleClick() {
    if (!priceId) return;

    setLoading(true);
    try {
      const res = await fetch('/api/checkout/create-session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ priceId }),
      });

      const data = await res.json();
      if (data.url) {
        window.location.href = data.url;
      }
    } catch {
      // Silently fail — user can retry
    } finally {
      setLoading(false);
    }
  }

  if (!isSignedIn && priceId) {
    return (
      <SignInButton mode="modal" forceRedirectUrl="/pricing">
        <button
          className={`w-full rounded-lg px-4 py-2.5 text-sm font-medium transition-colors cursor-pointer ${
            highlighted
              ? 'bg-primary text-white hover:bg-primary-dark'
              : 'border border-border text-text-secondary hover:text-text-primary hover:border-border-light'
          }`}
        >
          {label}
        </button>
      </SignInButton>
    );
  }

  return (
    <button
      onClick={handleClick}
      disabled={loading || !priceId}
      className={`w-full rounded-lg px-4 py-2.5 text-sm font-medium transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed ${
        highlighted
          ? 'bg-primary text-white hover:bg-primary-dark'
          : 'border border-border text-text-secondary hover:text-text-primary hover:border-border-light'
      }`}
    >
      {loading ? 'Redirecting...' : label}
    </button>
  );
}
