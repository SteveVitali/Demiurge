'use client';

import { useState } from 'react';
import { BillingToggle } from './BillingToggle';
import { PricingCard, type PricingTier } from './PricingCard';

const TIERS: PricingTier[] = [
  {
    name: 'Trial',
    monthlyPrice: 0,
    annualPrice: null,
    priceLabel: 'Free',
    annualPriceLabel: 'Free',
    monthlyPriceId: null,
    annualPriceId: null,
    features: [
      { label: '5 runs total', included: true },
      { label: '50K agent tokens', included: true },
      { label: '1 machine', included: true },
      { label: 'Agent repair', included: true },
      { label: 'Build mode', included: false },
      { label: 'Browser verification', included: false },
      { label: 'Priority support', included: false },
    ],
    cta: 'Start Free Trial',
  },
  {
    name: 'Starter',
    monthlyPrice: 29,
    annualPrice: 24,
    priceLabel: '$29',
    annualPriceLabel: '$24',
    priceSuffix: '/mo',
    monthlyPriceId: process.env.NEXT_PUBLIC_STRIPE_STARTER_PRICE_ID ?? null,
    annualPriceId: process.env.NEXT_PUBLIC_STRIPE_STARTER_ANNUAL_PRICE_ID ?? null,
    features: [
      { label: '50 runs/mo', included: true },
      { label: '500K agent tokens/mo', included: true },
      { label: '2 machines', included: true },
      { label: 'Agent repair', included: true },
      { label: 'Build mode', included: true },
      { label: 'Browser verification', included: true },
      { label: 'Priority support', included: false },
    ],
    cta: 'Get Starter',
  },
  {
    name: 'Pro',
    monthlyPrice: 79,
    annualPrice: 66,
    priceLabel: '$79',
    annualPriceLabel: '$66',
    priceSuffix: '/mo',
    monthlyPriceId: process.env.NEXT_PUBLIC_STRIPE_PRO_PRICE_ID ?? null,
    annualPriceId: process.env.NEXT_PUBLIC_STRIPE_PRO_ANNUAL_PRICE_ID ?? null,
    features: [
      { label: '200 runs/mo', included: true },
      { label: '2M agent tokens/mo', included: true },
      { label: '3 machines', included: true },
      { label: 'Agent repair', included: true },
      { label: 'Build mode', included: true },
      { label: 'Browser verification', included: true },
      { label: 'Priority support', included: true },
    ],
    cta: 'Get Pro',
    highlighted: true,
  },
  {
    name: 'Team',
    monthlyPrice: 49,
    annualPrice: 41,
    priceLabel: '$49',
    annualPriceLabel: '$41',
    priceSuffix: '/user/mo',
    monthlyPriceId: process.env.NEXT_PUBLIC_STRIPE_TEAM_PRICE_ID ?? null,
    annualPriceId: process.env.NEXT_PUBLIC_STRIPE_TEAM_ANNUAL_PRICE_ID ?? null,
    features: [
      { label: '150 runs/user/mo', included: true },
      { label: '1.5M tokens/user/mo', included: true },
      { label: '5 machines/user', included: true },
      { label: 'Agent repair', included: true },
      { label: 'Build mode', included: true },
      { label: 'Browser verification', included: true },
      { label: 'Priority support', included: true },
    ],
    cta: 'Get Team',
  },
];

export function PricingTable() {
  const [annual, setAnnual] = useState(false);

  return (
    <div>
      <div className="mb-10">
        <BillingToggle annual={annual} onToggle={setAnnual} />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
        {TIERS.map((tier) => (
          <PricingCard key={tier.name} tier={tier} annual={annual} />
        ))}
      </div>

      {/* Enterprise callout */}
      <div className="mt-12 rounded-xl border border-border bg-surface p-8 text-center">
        <h3 className="text-lg font-semibold text-text-primary mb-2">
          Need more?
        </h3>
        <p className="text-sm text-text-secondary max-w-xl mx-auto mb-4">
          Contact us for custom plans with unlimited runs, SSO, audit logs, and dedicated support.
        </p>
        <a
          href="mailto:enterprise@demiurge.dev"
          className="inline-flex items-center rounded-lg border border-border px-6 py-2.5 text-sm font-medium text-text-secondary hover:text-text-primary hover:border-border-light transition-colors"
        >
          Contact Sales
        </a>
      </div>
    </div>
  );
}
