'use client';

import { motion } from 'framer-motion';
import { Settings, Play, Rocket } from 'lucide-react';

const STEPS = [
  {
    icon: Settings,
    number: '01',
    title: 'Configure',
    description: 'Run `demiurge init --smart` or write a simple YAML manifest. The AI agent inspects your repo and generates the configuration automatically.',
    code: 'demiurge init --smart',
  },
  {
    icon: Play,
    number: '02',
    title: 'Run',
    description: 'Demiurge boots your services in an isolated git worktree, runs verifiers, and detects issues — HTTP checks, API contracts, browser tests, and more.',
    code: 'demiurge run',
  },
  {
    icon: Rocket,
    number: '03',
    title: 'Ship',
    description: 'AI agents fix failures automatically. Review the changes, merge, and deploy with confidence. Every fix is verified before it reaches your branch.',
    code: 'git merge && git push',
  },
];

export function HowItWorks() {
  return (
    <section className="py-24 bg-surface/50">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <motion.div
          className="text-center mb-16"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.6 }}
        >
          <h2 className="text-3xl sm:text-4xl font-bold text-text-primary">
            How it works
          </h2>
          <p className="mt-4 text-lg text-text-secondary max-w-2xl mx-auto">
            Three steps from broken to shipped. Demiurge handles the rest.
          </p>
        </motion.div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 lg:gap-12">
          {STEPS.map((step, index) => (
            <motion.div
              key={step.title}
              className="relative"
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true }}
              transition={{ duration: 0.5, delay: index * 0.15 }}
            >
              {/* Connector line (desktop only) */}
              {index < STEPS.length - 1 && (
                <div className="hidden md:block absolute top-12 left-full w-full h-px bg-gradient-to-r from-border to-transparent -translate-x-4" />
              )}

              <div className="flex flex-col items-center text-center">
                <div className="flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 text-primary mb-6">
                  <step.icon className="h-6 w-6" />
                </div>
                <div className="text-xs font-mono text-primary mb-2">
                  Step {step.number}
                </div>
                <h3 className="text-xl font-semibold text-text-primary mb-3">
                  {step.title}
                </h3>
                <p className="text-sm text-text-secondary leading-relaxed mb-4">
                  {step.description}
                </p>
                <code className="inline-block px-3 py-1.5 rounded-md bg-surface border border-border text-xs font-mono text-text-secondary">
                  {step.code}
                </code>
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
