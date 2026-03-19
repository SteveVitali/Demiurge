'use client';

import { motion } from 'framer-motion';
import { Play } from 'lucide-react';

export function DemoVideo() {
  return (
    <section className="py-24 bg-surface/50">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <motion.div
          className="text-center mb-12"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.6 }}
        >
          <h2 className="text-3xl sm:text-4xl font-bold text-text-primary">
            See it in action
          </h2>
          <p className="mt-4 text-lg text-text-secondary max-w-2xl mx-auto">
            Watch Demiurge boot a web app, detect issues, and autonomously repair them — all in under a minute.
          </p>
        </motion.div>

        <motion.div
          className="mx-auto max-w-4xl"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.6, delay: 0.2 }}
        >
          <div className="relative aspect-video rounded-xl border border-border bg-surface overflow-hidden group cursor-pointer">
            {/* Placeholder — replace with real video embed */}
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-4">
              <div className="flex items-center justify-center w-16 h-16 rounded-full bg-primary/20 text-primary group-hover:bg-primary/30 transition-colors">
                <Play className="h-7 w-7 ml-1" />
              </div>
              <p className="text-sm text-text-muted">Demo video coming soon</p>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
