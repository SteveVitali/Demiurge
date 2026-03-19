'use client';

import { motion } from 'framer-motion';
import {
  ShieldCheck,
  Bot,
  Globe,
  GitBranch,
  Monitor,
  Wand2,
  Key,
  Sparkles,
} from 'lucide-react';

const FEATURES = [
  {
    icon: ShieldCheck,
    title: 'Verifier-First',
    description:
      'Every task is backed by executable verifiers — HTTP, TCP, browser, API contracts, state assertions.',
  },
  {
    icon: Bot,
    title: 'Agentic Repair',
    description:
      'When verification fails, Claude Code agents autonomously fix your code with multi-turn reasoning.',
  },
  {
    icon: Globe,
    title: 'Browser Automation',
    description:
      'Full Playwright-powered browser verification with visual regression, viewport testing, and screenshot evidence.',
  },
  {
    icon: GitBranch,
    title: 'Git Isolation',
    description:
      'Every run operates in a dedicated git worktree. Your working directory is never modified.',
  },
  {
    icon: Monitor,
    title: 'Desktop + CLI',
    description:
      'Native desktop app with real-time observability, or a powerful CLI for headless environments.',
  },
  {
    icon: Wand2,
    title: 'Build Mode',
    description:
      'Describe a feature in natural language — Demiurge generates, verifies, and repairs the code.',
  },
  {
    icon: Key,
    title: 'BYOK',
    description:
      'Bring your own Anthropic or OpenAI API key. You control costs and model selection.',
  },
  {
    icon: Sparkles,
    title: 'Smart Init',
    description:
      'Point Demiurge at any repo — an AI agent generates the configuration automatically.',
  },
];

export function FeaturesGrid() {
  return (
    <section className="py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <motion.div
          className="text-center mb-16"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.6 }}
        >
          <h2 className="text-3xl sm:text-4xl font-bold text-text-primary">
            Everything you need to ship with confidence
          </h2>
          <p className="mt-4 text-lg text-text-secondary max-w-2xl mx-auto">
            A complete automation platform that boots, verifies, and repairs your applications.
          </p>
        </motion.div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          {FEATURES.map((feature, index) => (
            <motion.div
              key={feature.title}
              className="rounded-xl border border-border bg-surface p-6 hover:border-border-light transition-colors"
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true }}
              transition={{ duration: 0.4, delay: index * 0.05 }}
            >
              <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-primary/10 text-primary mb-4">
                <feature.icon className="h-5 w-5" />
              </div>
              <h3 className="text-base font-semibold text-text-primary mb-2">
                {feature.title}
              </h3>
              <p className="text-sm text-text-secondary leading-relaxed">
                {feature.description}
              </p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
