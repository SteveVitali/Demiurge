'use client';

import { useState } from 'react';
import { useUser, SignInButton } from '@clerk/nextjs';

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
      <div style={containerStyle}>
        <p>Loading...</p>
      </div>
    );
  }

  if (!isSignedIn) {
    return (
      <div style={containerStyle}>
        <h1 style={headingStyle}>Activate Demiurge CLI</h1>
        <p style={subStyle}>Sign in to link your CLI to your account.</p>
        <SignInButton mode="modal">
          <button style={buttonStyle}>Sign In</button>
        </SignInButton>
      </div>
    );
  }

  if (status === 'success') {
    return (
      <div style={containerStyle}>
        <h1 style={headingStyle}>CLI Activated</h1>
        <p style={subStyle}>
          Your CLI is now linked to <strong>{user?.primaryEmailAddress?.emailAddress}</strong>.
          You can close this page and return to your terminal.
        </p>
      </div>
    );
  }

  return (
    <div style={containerStyle}>
      <h1 style={headingStyle}>Activate Demiurge CLI</h1>
      <p style={subStyle}>
        Enter the code shown in your terminal to link the CLI to your account.
      </p>
      <form onSubmit={handleSubmit} style={formStyle}>
        <input
          type="text"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="XXXX-XXXX"
          maxLength={9}
          style={inputStyle}
          autoFocus
          autoComplete="off"
        />
        <button
          type="submit"
          disabled={status === 'loading' || !code.trim()}
          style={{
            ...buttonStyle,
            opacity: status === 'loading' || !code.trim() ? 0.6 : 1,
          }}
        >
          {status === 'loading' ? 'Activating...' : 'Activate'}
        </button>
      </form>
      {status === 'error' && (
        <p style={{ color: '#dc2626', marginTop: '0.5rem', fontSize: '0.875rem' }}>
          {errorMessage}
        </p>
      )}
    </div>
  );
}

const containerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'center',
  alignItems: 'center',
  minHeight: '100vh',
  fontFamily: 'system-ui, -apple-system, sans-serif',
  padding: '2rem',
};

const headingStyle: React.CSSProperties = {
  fontSize: '1.5rem',
  fontWeight: 600,
  marginBottom: '0.5rem',
};

const subStyle: React.CSSProperties = {
  color: '#666',
  marginBottom: '1.5rem',
  textAlign: 'center',
  maxWidth: '400px',
};

const formStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '0.75rem',
  width: '100%',
  maxWidth: '300px',
};

const inputStyle: React.CSSProperties = {
  padding: '0.75rem 1rem',
  fontSize: '1.5rem',
  textAlign: 'center',
  letterSpacing: '0.15em',
  fontFamily: 'monospace',
  border: '2px solid #e5e7eb',
  borderRadius: '8px',
  outline: 'none',
  textTransform: 'uppercase',
};

const buttonStyle: React.CSSProperties = {
  padding: '0.75rem 1.5rem',
  fontSize: '1rem',
  fontWeight: 500,
  backgroundColor: '#000',
  color: '#fff',
  border: 'none',
  borderRadius: '8px',
  cursor: 'pointer',
};
