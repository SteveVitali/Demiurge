import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { getAllDocs, getDocBySlug, extractToc } from '@/lib/docs';
import { DocsSidebar } from '@/components/docs/DocsSidebar';
import { DocsContent } from '@/components/docs/DocsContent';
import { TableOfContents } from '@/components/docs/TableOfContents';

interface DocPageProps {
  params: Promise<{ slug: string }>;
}

export async function generateStaticParams() {
  const docs = getAllDocs();
  return docs.map((doc) => ({ slug: doc.slug }));
}

export async function generateMetadata({ params }: DocPageProps): Promise<Metadata> {
  const { slug } = await params;
  const doc = getDocBySlug(slug);
  if (!doc) return { title: 'Not Found' };

  return {
    title: doc.title,
    description: `Demiurge documentation — ${doc.title}`,
  };
}

export default async function DocPage({ params }: DocPageProps) {
  const { slug } = await params;
  const doc = getDocBySlug(slug);
  if (!doc) notFound();

  const allDocs = getAllDocs();
  const toc = extractToc(doc.content);

  return (
    <div className="py-12">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex gap-8">
          {/* Left sidebar */}
          <aside className="hidden lg:block w-64 shrink-0">
            <div className="sticky top-24">
              <DocsSidebar
                docs={allDocs.map((d) => ({ slug: d.slug, title: d.title }))}
              />
            </div>
          </aside>

          {/* Main content */}
          <div className="flex-1 min-w-0">
            <DocsContent content={doc.content} />
          </div>

          {/* Right sidebar — TOC */}
          <aside className="hidden xl:block w-56 shrink-0">
            <div className="sticky top-24">
              <TableOfContents entries={toc} />
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}
