import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Blog',
  description: 'Demiurge blog — updates, tutorials, and engineering insights.',
};

export default function BlogPage() {
  return (
    <div className="py-24">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 text-center">
        <h1 className="text-4xl font-bold text-text-primary">Blog</h1>
        <p className="mt-4 text-lg text-text-secondary">
          Coming soon. In the meantime, check out our{' '}
          <a
            href="https://github.com/SteveVitali/Demiurge/releases"
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary hover:text-primary-light transition-colors underline underline-offset-2"
          >
            changelog on GitHub
          </a>
          .
        </p>
      </div>
    </div>
  );
}
