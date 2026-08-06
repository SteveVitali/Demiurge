'use client';

import Link from 'next/link';
import { ArrowRight, Github } from 'lucide-react';
import { FadeIn } from '@/components/ui/FadeIn';
import { GITHUB_URL } from '@/components/layout/nav-config';

export function CTABanner() {
  return (
    <section className="py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <FadeIn className="relative rounded-2xl border border-border bg-surface overflow-hidden">
          {/* Background gradient */}
          <div className="absolute inset-0 -z-10">
            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[400px] bg-primary/8 rounded-full blur-[100px]" />
          </div>

          <div className="px-8 py-16 sm:px-16 sm:py-20 text-center">
            <h2 className="text-3xl sm:text-4xl font-bold text-text-primary mb-4">
              Ready to automate your last mile?
            </h2>
            <p className="text-lg text-text-secondary max-w-xl mx-auto mb-8">
              Download Demiurge for free and start shipping verified software today.
            </p>
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link
                href="/download"
                className="inline-flex items-center gap-2 rounded-lg bg-primary px-6 py-3 text-base font-medium text-white hover:bg-primary-dark transition-colors"
              >
                Download for Free
                <ArrowRight className="h-4 w-4" />
              </Link>
              <a
                href={GITHUB_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-border px-6 py-3 text-base font-medium text-text-secondary hover:text-text-primary hover:border-border-light transition-colors"
              >
                <Github className="h-4 w-4" />
                View on GitHub
              </a>
            </div>
          </div>
        </FadeIn>
      </div>
    </section>
  );
}
