import { Check, Minus } from 'lucide-react';
import { CheckoutButton } from './CheckoutButton';

export interface PricingTier {
  name: string;
  monthlyPrice: number | null;
  annualPrice: number | null;
  priceLabel: string;
  annualPriceLabel: string;
  priceSuffix?: string;
  monthlyPriceId: string | null;
  annualPriceId: string | null;
  features: { label: string; included: boolean }[];
  cta: string;
  highlighted?: boolean;
}

interface PricingCardProps {
  tier: PricingTier;
  annual: boolean;
}

export function PricingCard({ tier, annual }: PricingCardProps) {
  const price = annual ? tier.annualPriceLabel : tier.priceLabel;
  const priceId = annual ? tier.annualPriceId : tier.monthlyPriceId;

  return (
    <div
      className={`relative flex flex-col rounded-xl border p-6 ${
        tier.highlighted
          ? 'border-primary bg-surface shadow-lg shadow-primary/10'
          : 'border-border bg-surface'
      }`}
    >
      {tier.highlighted && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2 rounded-full bg-primary px-3 py-0.5 text-xs font-medium text-white">
          Most popular
        </div>
      )}

      <div className="mb-6">
        <h3 className="text-lg font-semibold text-text-primary">{tier.name}</h3>
        <div className="mt-3 flex items-baseline gap-1">
          <span className="text-3xl font-bold text-text-primary">{price}</span>
          {tier.priceSuffix && (
            <span className="text-sm text-text-muted">{tier.priceSuffix}</span>
          )}
        </div>
        {annual && tier.monthlyPrice && tier.annualPrice && (
          <p className="mt-1 text-xs text-text-muted">
            Billed annually
          </p>
        )}
      </div>

      <ul className="flex-1 space-y-3 mb-6">
        {tier.features.map((feature) => (
          <li key={feature.label} className="flex items-start gap-2.5">
            {feature.included ? (
              <Check className="h-4 w-4 mt-0.5 text-success shrink-0" />
            ) : (
              <Minus className="h-4 w-4 mt-0.5 text-text-muted shrink-0" />
            )}
            <span
              className={`text-sm ${feature.included ? 'text-text-secondary' : 'text-text-muted'}`}
            >
              {feature.label}
            </span>
          </li>
        ))}
      </ul>

      <CheckoutButton
        priceId={priceId}
        label={tier.cta}
        highlighted={tier.highlighted}
      />
    </div>
  );
}
