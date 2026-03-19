import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';
import { slugify } from './slugify';

const DOCS_DIR = path.join(process.cwd(), '..', 'docs');

const PUBLISHED_DOCS = [
  'architecture.md',
  'cli-reference.md',
  'configuration.md',
  'api-reference.md',
  'getting-started.md',
  'troubleshooting.md',
  'development.md',
];

export interface DocPage {
  slug: string;
  title: string;
  content: string;
  order: number;
}

export function getAllDocs(): DocPage[] {
  return PUBLISHED_DOCS
    .filter((filename) => {
      const filePath = path.join(DOCS_DIR, filename);
      return fs.existsSync(filePath);
    })
    .map((filename, index) => {
      const filePath = path.join(DOCS_DIR, filename);
      const raw = fs.readFileSync(filePath, 'utf-8');
      const { content } = matter(raw);

      const titleMatch = content.match(/^#\s+(.+)$/m);
      const title = titleMatch ? titleMatch[1] : filename.replace('.md', '');

      return {
        slug: filename.replace('.md', ''),
        title,
        content,
        order: index,
      };
    });
}

export function getDocBySlug(slug: string): DocPage | undefined {
  return getAllDocs().find((doc) => doc.slug === slug);
}

export interface TocEntry {
  id: string;
  text: string;
  level: number;
}

export function extractToc(markdown: string): TocEntry[] {
  const headingRegex = /^(#{2,3})\s+(.+)$/gm;
  const entries: TocEntry[] = [];
  let match;

  while ((match = headingRegex.exec(markdown)) !== null) {
    const level = match[1].length;
    const text = match[2].trim();
    const id = slugify(text);
    entries.push({ id, text, level });
  }

  return entries;
}
