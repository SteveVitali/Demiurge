'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { FileText } from 'lucide-react';
import { clsx } from 'clsx';

interface DocLink {
  slug: string;
  title: string;
}

interface DocsSidebarProps {
  docs: DocLink[];
}

export function DocsSidebar({ docs }: DocsSidebarProps) {
  const pathname = usePathname();

  return (
    <nav className="w-full">
      <h3 className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-4">
        Documentation
      </h3>
      <ul className="space-y-1">
        {docs.map((doc) => {
          const href = `/docs/${doc.slug}`;
          const active = pathname === href;

          return (
            <li key={doc.slug}>
              <Link
                href={href}
                className={clsx(
                  'flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors',
                  active
                    ? 'bg-primary/10 text-primary font-medium'
                    : 'text-text-secondary hover:text-text-primary hover:bg-surface-light',
                )}
              >
                <FileText className="h-4 w-4 shrink-0" />
                {doc.title}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
