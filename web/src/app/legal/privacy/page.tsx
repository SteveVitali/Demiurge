import type { Metadata } from 'next';
import fs from 'fs';
import path from 'path';
import { DocsContent } from '@/components/docs/DocsContent';

export const metadata: Metadata = {
  title: 'Privacy Policy',
  description: 'Demiurge Privacy Policy.',
};

export default function PrivacyPage() {
  const filePath = path.join(process.cwd(), 'src', 'content', 'legal', 'privacy.md');
  const content = fs.readFileSync(filePath, 'utf-8');

  return (
    <div className="py-24">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8">
        <DocsContent content={content} />
      </div>
    </div>
  );
}
