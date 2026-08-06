'use client';

import { type ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { slugify } from '@/lib/slugify';

interface DocsContentProps {
  content: string;
}

function extractText(node: ReactNode): string {
  if (typeof node === 'string') return node;
  if (typeof node === 'number') return String(node);
  if (!node) return '';
  if (Array.isArray(node)) return node.map(extractText).join('');
  if (typeof node === 'object' && 'props' in node) {
    const element = node as { props: { children?: ReactNode } };
    return extractText(element.props.children);
  }
  return '';
}

export function DocsContent({ content }: DocsContentProps) {
  return (
    <article className="prose prose-invert max-w-none">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          h2: ({ children, ...props }) => {
            const id = slugify(extractText(children));
            return (
              <h2 id={id} className="scroll-mt-24" {...props}>
                {children}
              </h2>
            );
          },
          h3: ({ children, ...props }) => {
            const id = slugify(extractText(children));
            return (
              <h3 id={id} className="scroll-mt-24" {...props}>
                {children}
              </h3>
            );
          },
          a: ({ href, children, ...props }) => (
            <a
              href={href}
              target={href?.startsWith('http') ? '_blank' : undefined}
              rel={href?.startsWith('http') ? 'noopener noreferrer' : undefined}
              {...props}
            >
              {children}
            </a>
          ),
        }}
      />
    </article>
  );
}
