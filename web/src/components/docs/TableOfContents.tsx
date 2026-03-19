'use client';

import { clsx } from 'clsx';
import type { TocEntry } from '@/lib/docs';

interface TableOfContentsProps {
  entries: TocEntry[];
}

export function TableOfContents({ entries }: TableOfContentsProps) {
  if (entries.length === 0) return null;

  return (
    <nav className="w-full">
      <h3 className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-4">
        On this page
      </h3>
      <ul className="space-y-1">
        {entries.map((entry) => (
          <li key={entry.id}>
            <a
              href={`#${entry.id}`}
              className={clsx(
                'block text-sm text-text-muted hover:text-text-secondary transition-colors py-1',
                entry.level === 3 && 'pl-4',
              )}
            >
              {entry.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
