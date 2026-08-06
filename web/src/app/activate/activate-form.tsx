'use client';

import { useState } from 'react';
import { useUser, SignInButton } from '@clerk/nextjs';
import { CheckCircle } from 'lucide-react';

/**
 * Client-side device code activation form.
 * Users sign in via Clerk, enter the code from their terminal, and the CLI poll detects authorization.
 */
export function ActivateForm() {
  const { user, isSignedIn, isLoaded } = useUser();
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!code.trim()) return;

    setStatus('loading');
    setErrorMessage('');

    try {
      const res = await fetch('/api/auth/device-authorize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ user_code: code.trim().toUpperCase() }),
      });

      if (res.ok) {
        setStatus('success');
      } else {
        const data = await res.json();
        setErrorMessage(data.error ?? 'Invalid or expired code. Please try again.');
        setStatus('error');
      }
    } catch {
      setErrorMessage('Network error. Please try again.');
      setStatus('error');
    }
  }

  if (!isLoaded) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] p-8">
        <p className="text-text-secondary">Loading...</p>
      </div>
    );
  }

  if (!isSignedIn) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] p-8">
        <h1 className="text-2xl font-bold text-text-primary mb-2">Activate Demiurge CLI</h1>
        <p className="text-text-secondary mb-6 text-center max-w-md">
          Sign in to link your CLI to your account.
        </p>
        <SignInButton mode="modal">
          <button className="rounded-lg bg-primary px-6 py-2.5 text-sm font-medium text-white hover:bg-primary-dark transition-colors cursor-pointer">
            Sign In
          </button>
        </SignInButton>
      </div>
    );
  }

  if (status === 'success') {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] p-8">
        <CheckCircle className="h-12 w-12 text-success mb-4" />
        <h1 className="text-2xl font-bold text-text-primary mb-2">CLI Activated</h1>
        <p className="text-text-secondary text-center max-w-md">
          Your CLI is now linked to <strong className="text-text-primary">{user?.primaryEmailAddress?.emailAddress}</strong>.
          You can close this page and return to your terminal.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] p-8">
      <h1 className="text-2xl font-bold text-text-primary mb-2">Activate Demiurge CLI</h1>
      <p className="text-text-secondary mb-6 text-center max-w-md">
        Enter the code shown in your terminal to link the CLI to your account.
      </p>
      <form onSubmit={handleSubmit} className="flex flex-col gap-3 w-full max-w-xs">
        <input
          type="text"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="XXXX-XXXX"
          maxLength={9}
          className="px-4 py-3 text-2xl text-center tracking-widest font-mono border-2 border-border rounded-lg bg-bg text-text-primary outline-none focus:border-primary uppercase"
          autoFocus
          autoComplete="off"
        />
        <button
          type="submit"
          disabled={status === 'loading' || !code.trim()}
          className="rounded-lg bg-primary px-6 py-2.5 text-sm font-medium text-white hover:bg-primary-dark transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {status === 'loading' ? 'Activating...' : 'Activate'}
        </button>
      </form>
      {status === 'error' && (
        <p className="text-error text-sm mt-2">{errorMessage}</p>
      )}
    </div>
  );
}
