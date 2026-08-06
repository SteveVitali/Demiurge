import type { Metadata } from 'next';
import Link from 'next/link';
import { FileText } from 'lucide-react';
import { getAllDocs } from '@/lib/docs';

export const metadata: Metadata = {
  title: 'Documentation',
  description: 'Demiurge documentation — architecture, CLI reference, configuration, API reference, and more.',
};

export default function DocsIndexPage() {
  const docs = getAllDocs();

  return (
    <div className="py-24">
      <div className="mx-auto max-w-4xl px-4 sm:px-6 lg:px-8">
        <div className="mb-12">
          <h1 className="text-4xl font-bold text-text-primary">Documentation</h1>
          <p className="mt-4 text-lg text-text-secondary">
            Learn how to configure, run, and extend Demiurge.
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {docs.map((doc) => (
            <Link
              key={doc.slug}
              href={`/docs/${doc.slug}`}
              className="flex items-start gap-3 rounded-xl border border-border bg-surface p-5 hover:border-border-light transition-colors group"
            >
              <FileText className="h-5 w-5 text-primary mt-0.5 shrink-0" />
              <div>
                <h2 className="text-base font-semibold text-text-primary group-hover:text-primary transition-colors">
                  {doc.title}
                </h2>
                <p className="mt-1 text-sm text-text-muted line-clamp-2">
                  {doc.content.slice(0, 150).replace(/^#.*\n/, '').trim()}...
                </p>
              </div>
            </Link>
          ))}
        </div>
      </div>
    </div>
  );
}
