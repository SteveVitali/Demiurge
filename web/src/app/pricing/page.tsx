import type { Metadata } from 'next';
import { PricingTable } from '@/components/pricing/PricingTable';

export const metadata: Metadata = {
  title: 'Pricing',
  description:
    'Simple, transparent pricing for Demiurge. Start with a free trial, upgrade when you need more.',
};

export default function PricingPage() {
  return (
    <div className="py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="text-center mb-16">
          <h1 className="text-4xl sm:text-5xl font-bold text-text-primary">
            Simple, transparent pricing
          </h1>
          <p className="mt-4 text-lg text-text-secondary max-w-2xl mx-auto">
            Start with a free trial. Upgrade when you need more runs, tokens, or machines.
          </p>
        </div>

        <PricingTable />
      </div>
    </div>
  );
}
