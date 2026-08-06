import Link from 'next/link';
import { Hexagon, Github } from 'lucide-react';
import { GITHUB_URL } from './nav-config';

const PRODUCT_LINKS = [
  { href: '/download', label: 'Download' },
  { href: '/pricing', label: 'Pricing' },
  { href: '/docs', label: 'Documentation' },
  { href: '/docs/cli-reference', label: 'CLI Reference' },
];

const COMPANY_LINKS = [
  { href: '/blog', label: 'Blog' },
  { href: '/legal/terms', label: 'Terms of Service' },
  { href: '/legal/privacy', label: 'Privacy Policy' },
];

const COMMUNITY_LINKS = [
  { href: GITHUB_URL, label: 'GitHub', external: true },
  { href: `${GITHUB_URL}/releases`, label: 'Changelog', external: true },
  { href: `${GITHUB_URL}/issues`, label: 'Issues', external: true },
];

export function Footer() {
  return (
    <footer className="border-t border-border bg-bg">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-12">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
          {/* Brand */}
          <div className="col-span-2 md:col-span-1">
            <Link href="/" className="flex items-center gap-2 text-text-primary mb-4">
              <Hexagon className="h-6 w-6 text-primary" strokeWidth={2.5} />
              <span className="text-base font-semibold">Demiurge</span>
            </Link>
            <p className="text-sm text-text-muted leading-relaxed">
              AI-powered web development automation. Boot, verify, and repair your applications with AI agents.
            </p>
            <div className="flex items-center gap-4 mt-4">
              <a
                href={GITHUB_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="text-text-muted hover:text-text-primary transition-colors"
                aria-label="Demiurge on GitHub"
              >
                <Github className="h-5 w-5" />
              </a>
            </div>
          </div>

          {/* Product */}
          <div>
            <h3 className="text-sm font-semibold text-text-primary mb-4">Product</h3>
            <ul className="space-y-3">
              {PRODUCT_LINKS.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-sm text-text-muted hover:text-text-secondary transition-colors"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Company */}
          <div>
            <h3 className="text-sm font-semibold text-text-primary mb-4">Company</h3>
            <ul className="space-y-3">
              {COMPANY_LINKS.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    className="text-sm text-text-muted hover:text-text-secondary transition-colors"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Community */}
          <div>
            <h3 className="text-sm font-semibold text-text-primary mb-4">Community</h3>
            <ul className="space-y-3">
              {COMMUNITY_LINKS.map((link) => (
                <li key={link.href}>
                  <a
                    href={link.href}
                    target={link.external ? '_blank' : undefined}
                    rel={link.external ? 'noopener noreferrer' : undefined}
                    className="text-sm text-text-muted hover:text-text-secondary transition-colors"
                  >
                    {link.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </div>

        <div className="mt-12 pt-8 border-t border-border flex flex-col sm:flex-row items-center justify-between gap-4">
          <p className="text-xs text-text-muted">
            &copy; {new Date().getFullYear()} Demiurge. All rights reserved.
          </p>
          <div className="flex items-center gap-6">
            <Link href="/legal/terms" className="text-xs text-text-muted hover:text-text-secondary transition-colors">
              Terms
            </Link>
            <Link href="/legal/privacy" className="text-xs text-text-muted hover:text-text-secondary transition-colors">
              Privacy
            </Link>
          </div>
        </div>
      </div>
    </footer>
  );
}
