'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useUser, UserButton, SignInButton } from '@clerk/nextjs';
import { Menu, X, Github, Hexagon } from 'lucide-react';
import { MobileMenu } from './MobileMenu';
import { NAV_LINKS, GITHUB_URL } from './nav-config';

export function Navbar() {
  const { isSignedIn, isLoaded } = useUser();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  return (
    <>
      <nav className="fixed top-0 left-0 right-0 z-50 border-b border-border backdrop-blur-xl bg-bg/80">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-16 items-center justify-between">
            {/* Logo */}
            <Link href="/" className="flex items-center gap-2 text-text-primary hover:opacity-80 transition-opacity">
              <Hexagon className="h-7 w-7 text-primary" strokeWidth={2.5} />
              <span className="text-lg font-semibold tracking-tight">Demiurge</span>
            </Link>

            {/* Desktop nav links */}
            <div className="hidden md:flex items-center gap-8">
              {NAV_LINKS.map((link) => (
                <Link
                  key={link.href}
                  href={link.href}
                  className="text-sm text-text-secondary hover:text-text-primary transition-colors"
                >
                  {link.label}
                </Link>
              ))}
              <a
                href={GITHUB_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="text-text-secondary hover:text-text-primary transition-colors"
              >
                <Github className="h-5 w-5" />
              </a>
            </div>

            {/* Right side actions */}
            <div className="hidden md:flex items-center gap-4">
              {isLoaded && isSignedIn ? (
                <UserButton
                  afterSignOutUrl="/"
                  appearance={{
                    elements: {
                      avatarBox: 'w-8 h-8',
                    },
                  }}
                />
              ) : isLoaded ? (
                <>
                  <SignInButton mode="modal">
                    <button className="text-sm text-text-secondary hover:text-text-primary transition-colors cursor-pointer">
                      Sign In
                    </button>
                  </SignInButton>
                  <Link
                    href="/download"
                    className="inline-flex items-center rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
                  >
                    Download
                  </Link>
                </>
              ) : null}
            </div>

            {/* Mobile hamburger */}
            <button
              className="md:hidden text-text-secondary hover:text-text-primary transition-colors"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              aria-label="Toggle menu"
            >
              {mobileMenuOpen ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
            </button>
          </div>
        </div>
      </nav>

      <MobileMenu
        open={mobileMenuOpen}
        onClose={() => setMobileMenuOpen(false)}
        isSignedIn={isSignedIn ?? false}
      />

      {/* Spacer for fixed navbar */}
      <div className="h-16" />
    </>
  );
}
