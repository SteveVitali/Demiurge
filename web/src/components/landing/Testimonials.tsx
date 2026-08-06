'use client';

import { Star } from 'lucide-react';
import { FadeIn } from '@/components/ui/FadeIn';

const TESTIMONIALS = [
  {
    quote: 'Demiurge caught a critical API contract regression that our test suite missed. The AI agent fixed it in under 30 seconds.',
    author: 'Early Beta User',
    role: 'Full-Stack Developer',
  },
  {
    quote: 'Smart init analyzed our monorepo and generated a perfect config on the first try. Saved me hours of setup.',
    author: 'Early Beta User',
    role: 'Senior Engineer',
  },
  {
    quote: 'The git worktree isolation is brilliant. I can run Demiurge without worrying about it touching my working directory.',
    author: 'Early Beta User',
    role: 'DevOps Engineer',
  },
];

export function Testimonials() {
  return (
    <section className="py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <FadeIn className="text-center mb-16">
          <h2 className="text-3xl sm:text-4xl font-bold text-text-primary">
            Trusted by developers
          </h2>
          <p className="mt-4 text-lg text-text-secondary">
            See what early adopters are saying about Demiurge.
          </p>
        </FadeIn>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          {TESTIMONIALS.map((testimonial, index) => (
            <FadeIn
              key={index}
              className="rounded-xl border border-border bg-surface p-6"
              duration={0.4}
              delay={index * 0.1}
            >
              <div className="flex gap-1 mb-4">
                {Array.from({ length: 5 }).map((_, i) => (
                  <Star key={i} className="h-4 w-4 text-warning fill-warning" />
                ))}
              </div>
              <blockquote className="text-sm text-text-secondary leading-relaxed mb-4">
                &ldquo;{testimonial.quote}&rdquo;
              </blockquote>
              <div>
                <div className="text-sm font-medium text-text-primary">
                  {testimonial.author}
                </div>
                <div className="text-xs text-text-muted">
                  {testimonial.role}
                </div>
              </div>
            </FadeIn>
          ))}
        </div>
      </div>
    </section>
  );
}
