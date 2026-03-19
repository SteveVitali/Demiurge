'use client';

import Link from 'next/link';
import { motion } from 'framer-motion';
import { ArrowRight, Play } from 'lucide-react';

export function Hero() {
  return (
    <section className="relative overflow-hidden py-24 sm:py-32 lg:py-40">
      {/* Background gradient */}
      <div className="absolute inset-0 -z-10">
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[800px] h-[600px] bg-primary/10 rounded-full blur-[120px]" />
      </div>

      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 text-center">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
        >
          <div className="inline-flex items-center gap-2 rounded-full border border-border bg-surface px-4 py-1.5 text-sm text-text-secondary mb-8">
            <span className="inline-block h-2 w-2 rounded-full bg-success animate-pulse" />
            Now in public beta
          </div>
        </motion.div>

        <motion.h1
          className="text-4xl sm:text-5xl lg:text-7xl font-bold tracking-tight text-text-primary leading-tight"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
        >
          Ship verified software
          <br />
          <span className="text-primary">with AI agents</span>
        </motion.h1>

        <motion.p
          className="mt-6 text-lg sm:text-xl text-text-secondary max-w-2xl mx-auto leading-relaxed"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.2 }}
        >
          Demiurge automatically boots, verifies, and repairs your web applications.
          Stop debugging — start shipping.
        </motion.p>

        <motion.div
          className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.3 }}
        >
          <Link
            href="/download"
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-6 py-3 text-base font-medium text-white hover:bg-primary-dark transition-colors"
          >
            Download Free
            <ArrowRight className="h-4 w-4" />
          </Link>
          <Link
            href="/pricing"
            className="inline-flex items-center gap-2 rounded-lg border border-border px-6 py-3 text-base font-medium text-text-secondary hover:text-text-primary hover:border-border-light transition-colors"
          >
            View Pricing
          </Link>
        </motion.div>

        {/* Hero visual placeholder */}
        <motion.div
          className="mt-16 mx-auto max-w-5xl"
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8, delay: 0.4 }}
        >
          <div className="relative rounded-xl border border-border bg-surface overflow-hidden shadow-2xl shadow-primary/5">
            <div className="flex items-center gap-2 px-4 py-3 border-b border-border bg-surface-light">
              <div className="flex gap-1.5">
                <div className="h-3 w-3 rounded-full bg-error/60" />
                <div className="h-3 w-3 rounded-full bg-warning/60" />
                <div className="h-3 w-3 rounded-full bg-success/60" />
              </div>
              <span className="text-xs text-text-muted ml-2 font-mono">demiurge run</span>
            </div>
            <div className="p-6 sm:p-8 font-mono text-sm leading-relaxed">
              <div className="text-text-muted">$ demiurge run</div>
              <div className="mt-2 text-text-secondary">
                <span className="text-primary">▸</span> Booting services...
              </div>
              <div className="text-text-secondary">
                <span className="text-success">✓</span> Server started on port 3000
              </div>
              <div className="text-text-secondary">
                <span className="text-primary">▸</span> Running verifiers...
              </div>
              <div className="text-text-secondary">
                <span className="text-success">✓</span> health-endpoint: 200 OK
              </div>
              <div className="text-text-secondary">
                <span className="text-error">✗</span> api-contract: POST /api/users returned 500
              </div>
              <div className="text-text-secondary">
                <span className="text-primary">▸</span> Agent repair started...
              </div>
              <div className="text-text-secondary">
                <span className="text-primary">▸</span> Analyzing failure, reading source files...
              </div>
              <div className="text-text-secondary">
                <span className="text-success">✓</span> Patch applied: fixed null check in UserController.ts
              </div>
              <div className="text-text-secondary">
                <span className="text-primary">▸</span> Re-verifying...
              </div>
              <div className="mt-1 text-success font-semibold">
                ✓ All 5 verifiers passed after 1 repair. Ready to ship.
              </div>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
