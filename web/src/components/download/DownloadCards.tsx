'use client';

import { Apple, Monitor, Download } from 'lucide-react';
import { type Platform, useDetectedPlatform } from './PlatformDetector';
import type { ReleaseAsset } from '@/lib/github-releases';
import { findAsset, formatBytes } from '@/lib/github-releases';

interface DownloadCardsProps {
  assets: ReleaseAsset[];
}

interface CardConfig {
  platform: Platform;
  label: string;
  icon: typeof Apple;
  primaryPattern: RegExp;
  primaryLabel: string;
  secondaryPattern?: RegExp;
  secondaryLabel?: string;
  altText?: string;
}

const CARDS: CardConfig[] = [
  {
    platform: 'mac-arm',
    label: 'macOS (Apple Silicon)',
    icon: Apple,
    primaryPattern: /\.dmg.*arm64|arm64.*\.dmg/i,
    primaryLabel: 'Download .dmg (ARM64)',
    secondaryPattern: /\.dmg.*x64|x64.*\.dmg|\.dmg.*intel/i,
    secondaryLabel: 'Intel .dmg',
    altText: 'Also available via Homebrew',
  },
  {
    platform: 'mac-intel',
    label: 'macOS (Intel)',
    icon: Apple,
    primaryPattern: /\.dmg.*x64|x64.*\.dmg|\.dmg.*intel/i,
    primaryLabel: 'Download .dmg (x64)',
    secondaryPattern: /\.dmg.*arm64|arm64.*\.dmg/i,
    secondaryLabel: 'ARM64 .dmg',
    altText: 'Also available via Homebrew',
  },
  {
    platform: 'windows',
    label: 'Windows',
    icon: Monitor,
    primaryPattern: /\.exe$|\.msi$/i,
    primaryLabel: 'Download Installer (.exe)',
  },
  {
    platform: 'linux',
    label: 'Linux',
    icon: Monitor,
    primaryPattern: /\.appimage$/i,
    primaryLabel: 'Download AppImage',
    secondaryPattern: /\.deb$/i,
    secondaryLabel: '.deb package',
  },
];

export function DownloadCards({ assets }: DownloadCardsProps) {
  const detected = useDetectedPlatform();

  // Sort so detected platform is first
  const sorted = [...CARDS].sort((a, b) => {
    if (a.platform === detected) return -1;
    if (b.platform === detected) return 1;
    return 0;
  });

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
      {sorted.map((card) => {
        const primary = findAsset(assets, card.primaryPattern);
        const secondary = card.secondaryPattern
          ? findAsset(assets, card.secondaryPattern)
          : undefined;
        const isDetected = card.platform === detected;

        return (
          <div
            key={card.platform}
            className={`relative rounded-xl border p-6 transition-colors ${
              isDetected
                ? 'border-primary bg-surface shadow-lg shadow-primary/10'
                : 'border-border bg-surface'
            }`}
          >
            {isDetected && (
              <div className="absolute -top-2.5 left-4 rounded-full bg-primary px-2.5 py-0.5 text-xs font-medium text-white">
                Detected
              </div>
            )}

            <div className="flex items-center gap-3 mb-4">
              <card.icon className="h-6 w-6 text-text-secondary" />
              <h3 className="text-base font-semibold text-text-primary">
                {card.label}
              </h3>
            </div>

            {primary ? (
              <a
                href={primary.url}
                className="inline-flex items-center gap-2 w-full justify-center rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
              >
                <Download className="h-4 w-4" />
                {card.primaryLabel}
                <span className="text-xs text-white/70">
                  ({formatBytes(primary.size)})
                </span>
              </a>
            ) : (
              <div className="inline-flex items-center gap-2 w-full justify-center rounded-lg border border-border px-4 py-2.5 text-sm text-text-muted">
                {card.primaryLabel} — coming soon
              </div>
            )}

            {secondary && (
              <a
                href={secondary.url}
                className="mt-2 inline-flex items-center gap-2 w-full justify-center rounded-lg border border-border px-4 py-2 text-xs text-text-secondary hover:text-text-primary hover:border-border-light transition-colors"
              >
                {card.secondaryLabel} ({formatBytes(secondary.size)})
              </a>
            )}

            {card.altText && (
              <p className="mt-2 text-xs text-text-muted text-center">
                {card.altText}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}
