'use client';

import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';

interface DocsContentProps {
  content: string;
}

export function DocsContent({ content }: DocsContentProps) {
  return (
    <article className="prose prose-invert max-w-none">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          h2: ({ children, ...props }) => {
            const text = typeof children === 'string' ? children : '';
            const id = text
              .toLowerCase()
              .replace(/[^\w\s-]/g, '')
              .replace(/\s+/g, '-');
            return (
              <h2 id={id} className="scroll-mt-24" {...props}>
                {children}
              </h2>
            );
          },
          h3: ({ children, ...props }) => {
            const text = typeof children === 'string' ? children : '';
            const id = text
              .toLowerCase()
              .replace(/[^\w\s-]/g, '')
              .replace(/\s+/g, '-');
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
