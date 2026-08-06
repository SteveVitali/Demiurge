'use client';

import Link from 'next/link';
import { SignInButton } from '@clerk/nextjs';
import { Github } from 'lucide-react';
import { NAV_LINKS, GITHUB_URL } from './nav-config';

interface MobileMenuProps {
  open: boolean;
  onClose: () => void;
  isSignedIn: boolean;
}

export function MobileMenu({ open, onClose, isSignedIn }: MobileMenuProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-40 md:hidden">
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />

      {/* Slide-out panel */}
      <div className="fixed top-16 right-0 bottom-0 w-64 bg-surface border-l border-border p-6">
        <nav className="flex flex-col gap-4">
          {NAV_LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              onClick={onClose}
              className="text-base text-text-secondary hover:text-text-primary transition-colors py-2"
            >
              {link.label}
            </Link>
          ))}
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noopener noreferrer"
            onClick={onClose}
            className="flex items-center gap-2 text-base text-text-secondary hover:text-text-primary transition-colors py-2"
          >
            <Github className="h-5 w-5" />
            GitHub
          </a>
        </nav>

        <div className="mt-8 flex flex-col gap-3">
          {!isSignedIn && (
            <SignInButton mode="modal">
              <button
                onClick={onClose}
                className="w-full rounded-lg border border-border px-4 py-2 text-sm text-text-secondary hover:text-text-primary transition-colors cursor-pointer"
              >
                Sign In
              </button>
            </SignInButton>
          )}
          <Link
            href="/download"
            onClick={onClose}
            className="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white text-center hover:bg-primary-dark transition-colors"
          >
            Download
          </Link>
        </div>
      </div>
    </div>
  );
}
